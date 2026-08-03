#!/bin/bash
#==============================================================
# 数据库恢复脚本
# 用法: ./restore.sh <backup_file>
#
# 安全特性:
#   - P0-2: 移除弱默认密码,未配置则失败退出
#   - P1-1: 支持加密备份(.tar.gz.enc)自动解密
#==============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "$1" ]; then
    echo "用法: $0 <backup_file.tar.gz | backup_file.tar.gz.enc>"
    echo ""
    echo "可用备份文件:"
    ls -lh "$PROJECT_DIR/backup/"*.tar.gz "$PROJECT_DIR/backup/"*.tar.gz.enc 2>/dev/null || echo "  无备份文件"
    exit 1
fi

BACKUP_FILE="$1"

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

# P1-1: 备份加密密钥(可选,用于解密 .tar.gz.enc 文件)
ENCRYPTION_KEY="${BACKUP_ENCRYPTION_KEY:-}"

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
read -p "恢复将覆盖当前数据库数据,确定继续?[y/N]: " confirm
if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
    echo "[信息] 已取消恢复操作"
    exit 0
fi

# 检查文件是否存在
if [ ! -f "$BACKUP_FILE" ]; then
    echo "[错误] 备份文件不存在: $BACKUP_FILE"
    exit 1
fi

# 解压备份(支持加密和明文两种格式)
TEMP_DIR=$(mktemp -d)

case "$BACKUP_FILE" in
    *.tar.gz.enc)
        # P1-1: 加密备份,需解密
        if [ -z "$ENCRYPTION_KEY" ]; then
            echo "[错误] 加密备份需要 BACKUP_ENCRYPTION_KEY,请在 .env 中配置" >&2
            rm -rf "$TEMP_DIR"
            exit 1
        fi
        echo "[信息] 解密备份文件(AES-256-CBC)..."
        openssl enc -d -aes-256-cbc -pbkdf2 \
            -pass pass:"$ENCRYPTION_KEY" \
            -in "$BACKUP_FILE" | tar -xzf - -C "$TEMP_DIR"
        ;;
    *.tar.gz)
        # 明文备份
        tar -xzf "$BACKUP_FILE" -C "$TEMP_DIR"
        ;;
    *)
        echo "[错误] 不支持的备份格式,期望 .tar.gz 或 .tar.gz.enc" >&2
        rm -rf "$TEMP_DIR"
        exit 1
        ;;
esac

# 查找 database.sql(可能在子目录中)
SQL_FILE=$(find "$TEMP_DIR" -name "database.sql" -type f | head -1)

if [ -z "$SQL_FILE" ]; then
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
echo "  恢复完成!"
echo "=========================================="
echo "  恢复时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "  建议重启后端服务以刷新缓存"
if [ "$DOCKER_MODE" = true ]; then
    echo "  重启命令: docker compose restart backend"
fi
echo ""
