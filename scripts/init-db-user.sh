#!/bin/bash
#==============================================================
# P1-3 MySQL 应用专用用户初始化脚本(宿主机执行版)
#==============================================================
# 此脚本在宿主机上运行,通过 docker exec 在 MySQL 容器中创建应用专用用户。
#
# 权限策略(最小权限原则):
#   - canteen_app: 仅 SELECT/INSERT/UPDATE/DELETE(运行时 DML)
#   - flyway_schema_history: 仅 SELECT(VersionController 需要 COUNT 查询)
#   - schema_version: 已回收所有权限
#   - Flyway 迁移使用 root 账号(通过 SPRING_FLYWAY_USER 配置)
#
# 安全特性:
#   - P0 修复:转义密码中的单引号,防止 SQL 注入
#   - P0 修复:REVOKE 元数据表写权限,防止应用被攻破后伪造迁移记录
#   - 从 .env 安全读取配置(不 source,避免特殊字符导致 shell 展开)
#==============================================================
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
info()  { echo -e "${GREEN}[DB用户]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }

# 安全读取 .env 变量(不执行 source,避免密码含 $/空格/#/反引号 时 shell 展开导致值篡改)
read_env_var() {
    local key="$1" envfile="${2:-$PROJECT_DIR/.env}"
    [[ -f "$envfile" ]] || return 1
    local line value
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line// }" ]] && continue
        if [[ "$line" =~ ^${key}= ]]; then
            value="${line#*=}"
            if [[ "$value" =~ ^\'.*\'$ ]]; then
                value="${value:1:-1}"
            elif [[ "$value" =~ ^\".*\"$ ]]; then
                value="${value:1:-1}"
            fi
            # 去除行尾 Windows 换行符残留(\r)
            # 原因:.env 若在 Windows 上创建/编辑,行尾会带 \r,read 不会剥离,
            # 导致密码尾部多一个回车符(如 canteen2026\r),MySQL 认证失败 Access denied。
            # docker-compose 解析 .env 时会自动去掉 \r,而本脚本需手动剥离,否则两者结果不一致。
            value="${value//$'\r'/}"
            printf '%s' "$value"
            return 0
        fi
    done < "$envfile"
    return 1
}

# 读取配置(优先环境变量,其次从 .env 读取,最后用默认值)
DB_APP_USERNAME="${DB_APP_USERNAME:-$(read_env_var "DB_APP_USERNAME" 2>/dev/null || echo "canteen_app")}"
DB_APP_PASSWORD="${DB_APP_PASSWORD:-$(read_env_var "DB_APP_PASSWORD" 2>/dev/null || echo "")}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-$(read_env_var "MYSQL_ROOT_PASSWORD" 2>/dev/null || echo "")}"
MYSQL_DATABASE="${MYSQL_DATABASE:-$(read_env_var "MYSQL_DATABASE" 2>/dev/null || echo "canteen")}"

if [ -z "$DB_APP_PASSWORD" ]; then
    error "DB_APP_PASSWORD 未配置,请在 .env 中设置"
    exit 1
fi
if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
    error "MYSQL_ROOT_PASSWORD 未配置,请在 .env 中设置"
    exit 1
fi

info "创建应用数据库用户: ${DB_APP_USERNAME}@% (数据库: ${MYSQL_DATABASE})"

# 创建应用用户并授予 DML 权限(必须成功,不忽略错误)
# P1 修复:函数内临时禁用 set -e 以捕获退出码(避免错误处理成为死代码)
create_app_user() {
    set +e
    docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
-- P1-3 创建应用专用用户(最小权限:仅 DML,无 DDL/GRANT)
-- 幂等:已存在则更新密码
CREATE USER IF NOT EXISTS '${ESCAPED_USER}'@'%' IDENTIFIED BY '${ESCAPED_PASS}';
ALTER USER '${ESCAPED_USER}'@'%' IDENTIFIED BY '${ESCAPED_PASS}';

-- 授予业务表 DML 权限(SELECT/INSERT/UPDATE/DELETE)
-- 不授予 CREATE/ALTER/DROP/INDEX/GRANT(防止应用被攻破后修改表结构)
GRANT SELECT, INSERT, UPDATE, DELETE ON ${MYSQL_DATABASE}.* TO '${ESCAPED_USER}'@'%';

FLUSH PRIVILEGES;
SQL
    local rc=$?
    set -e
    return $rc
}

# 等待 MySQL 容器就绪(用指定 root 密码验证可连接)
wait_mysql_ready() {
    local password="$1" max=${2:-30} i=0
    while [[ $i -lt $max ]]; do
        if docker exec -i canteen-mysql mysql -uroot -p"${password}" -e "SELECT 1" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
        i=$((i + 2))
    done
    return 1
}

# 重置 MySQL root 密码使其与 .env 一致(保留数据卷中的数据)
# 场景:重复部署时 .env 被重新生成新密码,但 mysql 数据卷保留了首次部署的旧密码,
# 容器(数据卷已初始化)会忽略新密码,导致 root 认证失败(Access denied)。
# 解决:用 --skip-grant-tables 临时启动一个隔离容器,将 root 密码改为 .env 值。
reset_root_password() {
    local newpwd="$MYSQL_ROOT_PASSWORD"
    local escaped_root_pass="${newpwd//\'/\\\'}"
    warn "root 认证失败,尝试重置 MySQL root 密码以匹配 .env..."
    warn "此操作仅修改 root 密码,数据卷中的数据将保留。"

    # 清理可能遗留的恢复容器(异常退出时 --rm 未必生效)
    docker rm -f canteen-mysql-recover >/dev/null 2>&1 || true

    # 定位 mysql 数据卷的实际卷名(跨 compose 项目名鲁棒,避免硬编码)
    local vol_src img
    vol_src=$(docker inspect canteen-mysql --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}' 2>/dev/null || echo "")
    if [[ -z "$vol_src" ]]; then
        error "无法定位 MySQL 数据卷,请手动重置 root 密码"
        return 1
    fi
    img=$(docker inspect canteen-mysql --format '{{.Config.Image}}' 2>/dev/null || echo "mysql:8.0")

    # 停止正常运行中的 MySQL 容器(数据卷会被临时容器复用)
    docker stop canteen-mysql >/dev/null 2>&1 || true

    # 以 skip-grant-tables 临时启动(隔离网络,仅 docker exec 使用)
    if ! docker run --rm -d --name canteen-mysql-recover \
        -v "${vol_src}:/var/lib/mysql" \
        "$img" --skip-grant-tables --skip-networking >/dev/null 2>&1; then
        error "恢复容器启动失败"
        docker start canteen-mysql >/dev/null 2>&1 || true
        return 1
    fi

    # 等待恢复容器可连接(skip-grant-tables 下需先 FLUSH PRIVILEGES 才能 ALTER USER)
    local ok=false i=0
    while [[ $i -lt 30 ]]; do
        if docker exec canteen-mysql-recover mysql -uroot -e "FLUSH PRIVILEGES" >/dev/null 2>&1; then
            ok=true
            break
        fi
        sleep 1
        i=$((i + 1))
    done
    if [[ "$ok" != "true" ]]; then
        error "恢复容器启动超时"
        docker stop canteen-mysql-recover >/dev/null 2>&1 || true
        docker start canteen-mysql >/dev/null 2>&1 || true
        return 1
    fi

    # 重置 root 密码(localhost 与 % 均覆盖,保证后端/Flyway 远程连接可用)
    # 临时禁用 set -e 以捕获退出码(避免失败时脚本直接退出,错误处理失效)
    set +e
    docker exec canteen-mysql-recover mysql -uroot <<SQL
FLUSH PRIVILEGES;
ALTER USER 'root'@'localhost' IDENTIFIED BY '${escaped_root_pass}';
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY '${escaped_root_pass}';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL
    local rc=$?
    set -e
    docker stop canteen-mysql-recover >/dev/null 2>&1 || true

    if [[ $rc -ne 0 ]]; then
        error "重置 root 密码失败"
        docker start canteen-mysql >/dev/null 2>&1 || true
        return 1
    fi

    info "root 密码已重置,与 .env 一致"
    # 重新启动正常运行容器并等待其就绪(用新密码验证)
    docker start canteen-mysql >/dev/null 2>&1 || true
    if ! wait_mysql_ready "$newpwd"; then
        error "MySQL 重置后未就绪"
        return 1
    fi
    return 0
}

# 1. 创建应用用户;若 root 认证失败(密码与数据卷不匹配),自动恢复后重试
ESCAPED_USER="${DB_APP_USERNAME//\'/\\\'}"
ESCAPED_PASS="${DB_APP_PASSWORD//\'/\\\'}"
if ! create_app_user; then
    error "应用用户创建失败,尝试重置 root 密码以匹配 .env..."
    if reset_root_password; then
        info "root 密码已对齐,重新创建应用用户..."
        if ! create_app_user; then
            error "应用用户仍创建失败,请检查 DB_APP_PASSWORD 与数据库连接"
            exit 1
        fi
    else
        error "root 密码重置失败,请手动处理(见: docs/SERVER_HARDENING.md)"
        exit 1
    fi
fi

# 2. 回收元数据表(Flyway 迁移历史)的写权限,防止应用被攻破后伪造迁移记录
# P0 修复:先检查表是否存在,不存在则跳过(首次部署 Flyway 未运行,表不存在)
# 之前用 --force + 2>/dev/null 静默吞掉错误,导致权限回收未生效且无感知
FLYWAY_TABLE_EXISTS=$(docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -s -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}' AND table_name='flyway_schema_history';" 2>/dev/null || echo "0")

if [ "$FLYWAY_TABLE_EXISTS" -gt 0 ] 2>/dev/null; then
    info "flyway_schema_history 表存在,回收写权限..."
    docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" 2>/dev/null <<SQL
-- 回收 flyway_schema_history 的所有权限(撤销 DML GRANT 给的写权限)
REVOKE ALL PRIVILEGES ON ${MYSQL_DATABASE}.flyway_schema_history FROM '${ESCAPED_USER}'@'%';
-- flyway_schema_history 仅授予 SELECT(VersionController 需要 COUNT 查询)
GRANT SELECT ON ${MYSQL_DATABASE}.flyway_schema_history TO '${ESCAPED_USER}'@'%';
FLUSH PRIVILEGES;
SQL
    info "flyway_schema_history 权限已回收为仅 SELECT"
else
    warn "flyway_schema_history 表不存在(首次部署,Flyway 未运行),跳过权限回收"
    warn "请在 backend 启动后重新执行本脚本: bash scripts/init-db-user.sh"
fi

# schema_version 表(历史遗留,可能不存在)
SCHEMA_VER_EXISTS=$(docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -s -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}' AND table_name='schema_version';" 2>/dev/null || echo "0")
if [ "$SCHEMA_VER_EXISTS" -gt 0 ] 2>/dev/null; then
    docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" 2>/dev/null <<SQL
REVOKE ALL PRIVILEGES ON ${MYSQL_DATABASE}.schema_version FROM '${ESCAPED_USER}'@'%';
FLUSH PRIVILEGES;
SQL
fi

info "应用用户 ${DB_APP_USERNAME} 创建成功"
info "权限: 业务表 SELECT/INSERT/UPDATE/DELETE, flyway_schema_history 仅 SELECT(如存在)"
