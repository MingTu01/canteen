#!/bin/bash
#==============================================================
# 数据库备份脚本
# 用法: ./backup.sh [backup_name]
#==============================================================

set -e

BACKUP_DIR="${1:-$(date +%Y%m%d_%H%M%S)}"
BACKUP_PATH="/app/backup/${BACKUP_DIR}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# 从环境变量或默认值获取数据库配置
DB_HOST="${SPRING_DATASOURCE_HOST:-localhost}"
DB_PORT="${SPRING_DATASOURCE_PORT:-3306}"
DB_NAME="${MYSQL_DATABASE:-canteen}"
DB_USER="${SPRING_DATASOURCE_USERNAME:-root}"
DB_PASS="${SPRING_DATASOURCE_PASSWORD:-canteen2026}"

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
    # Docker 模式：在容器内执行备份
    docker exec canteen-mysql sh -c \
        "mysqldump -u${DB_USER} -p${DB_PASS} --single-transaction --routines --triggers --events ${DB_NAME}" \
        > "$BACKUP_PATH/database.sql"
else
    # 本地模式
    mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
        --single-transaction --routines --triggers --events "$DB_NAME" \
        > "$BACKUP_PATH/database.sql"
fi

# 压缩备份
cd "$(dirname "$BACKUP_PATH")"
tar -czf "${BACKUP_DIR}.tar.gz" "$BACKUP_DIR"
rm -rf "$BACKUP_DIR"

BACKUP_FILE="$(dirname "$BACKUP_PATH")/${BACKUP_DIR}.tar.gz"
FILE_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)

echo ""
echo "=========================================="
echo "  备份完成！"
echo "=========================================="
echo "  备份文件: $BACKUP_FILE"
echo "  文件大小: $FILE_SIZE"
echo "  备份时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# 清理超过30天的旧备份
find "$(dirname "$BACKUP_PATH")" -name "*.tar.gz" -mtime +30 -delete 2>/dev/null || true
echo "[信息] 已自动清理30天前的旧备份"
