#!/bin/bash
#==============================================================
# 数据库恢复脚本
# 用法: ./restore.sh <backup_file>
#==============================================================

set -e

if [ -z "$1" ]; then
    echo "用法: $0 <backup_file.tar.gz>"
    echo ""
    echo "可用备份文件："
    ls -lh /app/backup/*.tar.gz 2>/dev/null || ls -lh ./backup/*.tar.gz 2>/dev/null || echo "  无备份文件"
    exit 1
fi

BACKUP_FILE="$1"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# 从环境变量或默认值获取数据库配置
DB_HOST="${SPRING_DATASOURCE_HOST:-localhost}"
DB_PORT="${SPRING_DATASOURCE_PORT:-3306}"
DB_NAME="${MYSQL_DATABASE:-canteen}"
DB_USER="${SPRING_DATASOURCE_USERNAME:-root}"
DB_PASS="${SPRING_DATASOURCE_PASSWORD:-canteen2026}"

# Docker 环境检测
if command -v docker &> /dev/null && docker ps | grep -q canteen-mysql; then
    DOCKER_MODE=true
else
    DOCKER_MODE=false
fi

echo "=========================================="
echo "  数据库恢复"
echo "=========================================="
echo "  备份文件: $BACKUP_FILE"
echo ""

# 确认恢复
read -p "恢复将覆盖当前数据库数据，确定继续？[y/N]: " confirm
if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
    echo "[信息] 已取消恢复操作"
    exit 0
fi

# 检查文件是否存在
if [ ! -f "$BACKUP_FILE" ]; then
    echo "[错误] 备份文件不存在: $BACKUP_FILE"
    exit 1
fi

# 解压备份
TEMP_DIR=$(mktemp -d)
tar -xzf "$BACKUP_FILE" -C "$TEMP_DIR"
SQL_FILE="$TEMP_DIR/database.sql"

if [ ! -f "$SQL_FILE" ]; then
    echo "[错误] 备份文件中未找到 database.sql"
    rm -rf "$TEMP_DIR"
    exit 1
fi

echo "[信息] 开始恢复数据库..."

if [ "$DOCKER_MODE" = true ]; then
    # Docker 模式
    docker exec -i canteen-mysql mysql -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" < "$SQL_FILE"
else
    # 本地模式
    mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" < "$SQL_FILE"
fi

# 清理临时文件
rm -rf "$TEMP_DIR"

echo ""
echo "=========================================="
echo "  恢复完成！"
echo "=========================================="
echo "  恢复时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "  建议重启后端服务以刷新缓存"
if [ "$DOCKER_MODE" = true ]; then
    echo "  重启命令: docker compose restart backend"
fi
echo ""
