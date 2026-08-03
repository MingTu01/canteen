#!/bin/bash
#==============================================================
# 数据库备份脚本
# 用法: ./backup.sh [backup_name]
#
# 安全特性:
#   - P0-2: 移除弱默认密码,未配置则失败退出
#   - P1-1: 备份文件 AES-256-CBC 加密(若 BACKUP_ENCRYPTION_KEY 配置)
#==============================================================

set -e

BACKUP_DIR="${1:-$(date +%Y%m%d_%H%M%S)}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_PATH="$PROJECT_DIR/backup/${BACKUP_DIR}"

# 安全读取 .env 变量(不执行 source,避免密码含 $/空格/#/反引号 时 shell 展开导致崩溃)
# 用法: read_env_var "KEY" [envfile] -> 输出值(去除外层引号),失败返回非 0
read_env_var() {
    local key="$1" envfile="${2:-$PROJECT_DIR/.env}"
    [[ -f "$envfile" ]] || return 1
    local line value
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line// }" ]] && continue
        if [[ "$line" =~ ^${key}= ]]; then
            value="${line#*=}"
            # 去除外层引号(单引号或双引号)
            if [[ "$value" =~ ^\'.*\'$ ]]; then
                value="${value:1:-1}"
            elif [[ "$value" =~ ^\".*\"$ ]]; then
                value="${value:1:-1}"
            fi
            printf '%s' "$value"
            return 0
        fi
    done < "$envfile"
    return 1
}

# 从 .env 读取数据库配置(兼容已导出的环境变量优先)
DB_HOST="${SPRING_DATASOURCE_HOST:-localhost}"
DB_PORT="${SPRING_DATASOURCE_PORT:-3306}"
DB_NAME="${MYSQL_DATABASE:-canteen}"
DB_USER="${SPRING_DATASOURCE_USERNAME:-root}"
# P0-2 安全修复:移除弱默认密码,未配置则失败退出
# 优先用环境变量,其次从 .env 读取(用 read_env_var 避免 source 崩溃)
DB_PASS="${SPRING_DATASOURCE_PASSWORD:-}"
if [ -z "$DB_PASS" ]; then
    DB_PASS=$(read_env_var "MYSQL_ROOT_PASSWORD" 2>/dev/null) || DB_PASS=""
    # 兼容 .env 中用 SPRING_DATASOURCE_PASSWORD 的情况
    if [ -z "$DB_PASS" ]; then
        DB_PASS=$(read_env_var "SPRING_DATASOURCE_PASSWORD" 2>/dev/null) || DB_PASS=""
    fi
fi
if [ -z "$DB_PASS" ]; then
    echo "[错误] 数据库密码未配置,请检查 .env 文件中的 MYSQL_ROOT_PASSWORD 或 SPRING_DATASOURCE_PASSWORD" >&2
    exit 1
fi

# P1-1: 备份加密密钥(可选,未配置则仅 gzip 不加密)
# 优先用环境变量,其次从 .env 读取
ENCRYPTION_KEY="${BACKUP_ENCRYPTION_KEY:-}"
if [ -z "$ENCRYPTION_KEY" ]; then
    ENCRYPTION_KEY=$(read_env_var "BACKUP_ENCRYPTION_KEY" 2>/dev/null) || ENCRYPTION_KEY=""
fi

# Docker 环境检测
if command -v docker &> /dev/null && docker ps | grep -q canteen-mysql; then
    echo "[信息] 检测到 Docker 环境，使用容器内备份"
    DOCKER_MODE=true
else
    DOCKER_MODE=false
fi

echo "=========================================="
echo "  数据库备份 - ${BACKUP_DIR}"
echo "=========================================="

# 创建备份目录
mkdir -p "$BACKUP_PATH"

if [ "$DOCKER_MODE" = true ]; then
    # P0 修复:去掉 sh -c,直接调用 mysqldump,避免容器内 shell 二次展开密码中的 $
    # 密码由本机 shell 展开后作为单个 argv 传入容器内的 mysqldump
    set +e
    docker exec canteen-mysql mysqldump -u"${DB_USER}" -p"${DB_PASS}" \
        --single-transaction --routines --triggers --events "${DB_NAME}" \
        > "$BACKUP_PATH/database.sql" 2>/dev/null
    dump_rc=$?
    set -e
    if [ $dump_rc -ne 0 ] || [ ! -s "$BACKUP_PATH/database.sql" ]; then
        echo "[错误] 数据库备份失败(mysqldump退出码: $dump_rc)" >&2
        rm -f "$BACKUP_PATH/database.sql"
        exit 1
    fi
else
    # 本地模式
    set +e
    mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
        --single-transaction --routines --triggers --events "$DB_NAME" \
        > "$BACKUP_PATH/database.sql" 2>/dev/null
    dump_rc=$?
    set -e
    if [ $dump_rc -ne 0 ] || [ ! -s "$BACKUP_PATH/database.sql" ]; then
        echo "[错误] 数据库备份失败(mysqldump退出码: $dump_rc)" >&2
        rm -f "$BACKUP_PATH/database.sql"
        exit 1
    fi
fi

# 压缩备份 + 可选加密(P1-1)
cd "$(dirname "$BACKUP_PATH")"
if [ -n "$ENCRYPTION_KEY" ]; then
    # 加密模式:gzip → AES-256-CBC 加密 → .tar.gz.enc
    # 使用 pbkdf2 派生密钥,比单纯 salt 更抗暴力破解
    tar -czf - "$BACKUP_DIR" | openssl enc -aes-256-cbc -salt -pbkdf2 \
        -pass pass:"$ENCRYPTION_KEY" \
        -out "${BACKUP_DIR}.tar.gz.enc"
    rm -rf "$BACKUP_DIR"
    BACKUP_FILE="$(dirname "$BACKUP_PATH")/${BACKUP_DIR}.tar.gz.enc"
    ENCRYPTED="已加密(AES-256-CBC)"
else
    # 明文模式(向后兼容,生产环境建议配置加密密钥)
    tar -czf "${BACKUP_DIR}.tar.gz" "$BACKUP_DIR"
    rm -rf "$BACKUP_DIR"
    BACKUP_FILE="$(dirname "$BACKUP_PATH")/${BACKUP_DIR}.tar.gz"
    ENCRYPTED="未加密(建议配置 BACKUP_ENCRYPTION_KEY)"
fi

FILE_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)

echo ""
echo "=========================================="
echo "  备份完成!"
echo "=========================================="
echo "  备份文件: $BACKUP_FILE"
echo "  文件大小: $FILE_SIZE"
echo "  加密状态: $ENCRYPTED"
echo "  备份时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""
if [ -n "$ENCRYPTION_KEY" ]; then
    echo "  [解密命令]"
    echo "  openssl enc -d -aes-256-cbc -pbkdf2 -pass pass:'\$BACKUP_ENCRYPTION_KEY' -in ${BACKUP_FILE} | tar -xzf -"
    echo ""
    echo "  [警告] 请妥善保管 BACKUP_ENCRYPTION_KEY,丢失将无法恢复备份!"
fi

# 清理超过30天的旧备份(加密和明文都清理)
find "$(dirname "$BACKUP_PATH")" -name "*.tar.gz" -mtime +30 -delete 2>/dev/null || true
find "$(dirname "$BACKUP_PATH")" -name "*.tar.gz.enc" -mtime +30 -delete 2>/dev/null || true
echo "[信息] 已自动清理30天前的旧备份"
