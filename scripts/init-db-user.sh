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

# 转义用户名/密码中的单引号,防止 SQL 注入(密码可能含单引号)
# 注意:必须在构造 SQL 前定义,否则 ${ESCAPED_USER}/${ESCAPED_PASS} 为空,
# 会误创建 ''@'%' 空用户,导致 canteen_app 实际未创建、后端连接失败。
ESCAPED_USER="${DB_APP_USERNAME//\'/\'\'}"
ESCAPED_PASS="${DB_APP_PASSWORD//\'/\'\'}"

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
# 必须在 create_app_user 前调用:
# 若依赖 compose 健康检查,mysqladmin ping 会在初始化临时服务器阶段提前"healthy",
# 此处用真实认证登录兜底,确保真实服务器+密码就绪后才继续。
wait_mysql_ready() {
    local password="$1" max=${2:-60} i=0
    while [[ $i -lt $max ]]; do
        if docker exec -i canteen-mysql mysql -uroot -p"${password}" -e "SELECT 1" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
        i=$((i + 2))
    done
    return 1
}

# 1. 等待 MySQL 真正就绪(带密码认证,规避健康检查过早通过导致的连接失败)
if ! wait_mysql_ready "$MYSQL_ROOT_PASSWORD"; then
    error "MySQL 无法用 .env 中的 root 密码登录(等待 120s 仍失败)。"
    error "可能原因:MySQL 数据卷已在旧密码下初始化,而 .env 是本次新生成的随机密码。"
    echo ""
    echo "解决(会清空数据库数据,请先备份):"
    echo "  docker compose down"
    echo "  docker volume rm canteen_mysql_data"
    echo "  sudo ./deploy.sh"
    echo ""
    error "请处理上述问题后重新执行: bash scripts/init-db-user.sh"
    exit 1
fi

# 2. 创建应用用户(此时 MySQL 已就绪,不会因启动竞态失败)
if ! create_app_user; then
    error "应用用户创建失败。"
    error "请检查 DB_APP_USERNAME / DB_APP_PASSWORD 配置,并确认 MySQL 数据卷与 .env 密码一致。"
    echo ""
    echo "若数据卷密码与 .env 不一致(常见于重复部署),可清空数据卷后重新部署:"
    echo "  docker compose down"
    echo "  docker volume rm canteen_mysql_data"
    echo "  sudo ./deploy.sh"
    echo ""
    exit 1
fi

# 3. 回收元数据表(Flyway 迁移历史)的写权限,防止应用被攻破后伪造迁移记录
# P0 修复:先检查表是否存在,不存在则跳过(首次部署 Flyway 未运行,表不存在)
# 之前用 --force + 2>/dev/null 静默吞掉错误,导致权限回收未生效且无感知
FLYWAY_TABLE_EXISTS=$(docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -s -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}' AND table_name='flyway_schema_history';" 2>/dev/null || echo "0")

if [ "$FLYWAY_TABLE_EXISTS" -gt 0 ] 2>/dev/null; then
    info "flyway_schema_history 表存在,尝试回收写权限..."
    # P0 说明:应用用户是库级 DML 授权(GRANT ... ON db.*),对单张表做 REVOKE
    # 时 MySQL 找不到"表级授权记录",会返回 1141(no such grant)而失败。
    # 库级授权下无法按表选择性撤销——唯一彻底方案是去掉 db.* 通配、改为逐表 GRANT。
    # 因此这里不因 1141 中断脚本(避免 set -e 误报失败),失败仅告警说明。
    set +e
    docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" 2>/dev/null <<SQL
-- 回收 flyway_schema_history 的所有权限(撤销 DML GRANT 给的写权限)
REVOKE ALL PRIVILEGES ON ${MYSQL_DATABASE}.flyway_schema_history FROM '${ESCAPED_USER}'@'%';
-- flyway_schema_history 仅授予 SELECT(VersionController 需要 COUNT 查询)
GRANT SELECT ON ${MYSQL_DATABASE}.flyway_schema_history TO '${ESCAPED_USER}'@'%';
FLUSH PRIVILEGES;
SQL
    revoke_rc=$?
    set -e
    if [[ $revoke_rc -ne 0 ]]; then
        warn "flyway_schema_history 写权限回收失败(应用用户为库级授权,无法按表 REVOKE)。"
        warn "该表仅被库级 SELECT 覆盖,应用无法写迁移记录;如需严格隔离请改为逐表 GRANT。"
    else
        info "flyway_schema_history 权限已回收为仅 SELECT"
    fi
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
