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

# 加载 .env(若存在),获取 MYSQL_ROOT_PASSWORD 等配置
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    . "$PROJECT_DIR/.env"
    set +a
fi

# 从环境变量获取数据库配置
DB_HOST="${SPRING_DATASOURCE_HOST:-localhost}"
DB_PORT="${SPRING_DATASOURCE_PORT:-3306}"
DB_NAME="${MYSQL_DATABASE:-canteen}"
DB_USER="${SPRING_DATASOURCE_USERNAME:-root}"
# P0-2 安全修复:移除弱默认密码,未配置则失败退出
DB_PASS="${SPRING_DATASOURCE_PASSWORD:-${MYSQL_ROOT_PASSWORD:-}}"
if [ -z "$DB_PASS" ]; then
    echo "[错误] 数据库密码未配置,请检查 .env 文件中的 MYSQL_ROOT_PASSWORD 或 SPRING_DATASOURCE_PASSWORD" >&2
    exit 1
fi

# P1-1: 备份加密密钥(可选,未配置则仅 gzip 不加密)
ENCRYPTION_KEY="${BACKUP_ENCRYPTION_KEY:-}"

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
    # Docker 模式:在容器内执行备份
    docker exec canteen-mysql sh -c \
        "mysqldump -u\"${DB_USER}\" -p\"${DB_PASS}\" --single-transaction --routines --triggers --events \"${DB_NAME}\"" \
        > "$BACKUP_PATH/database.sql"
else
    # 本地模式
    mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
        --single-transaction --routines --triggers --events "$DB_NAME" \
        > "$BACKUP_PATH/database.sql"
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
