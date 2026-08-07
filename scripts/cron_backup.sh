#!/bin/bash
#==============================================================
# 定时备份 cron 脚本
# 配置方法: crontab -e
#   每天凌晨2点备份: 0 2 * * * /app/scripts/cron_backup.sh
#   每12小时备份:   0 */12 * * * /app/scripts/cron_backup.sh
#==============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# 执行备份（静默模式，不要求交互）
BACKUP_NAME="auto_$(date +%Y%m%d_%H%M%S)"
bash "$SCRIPT_DIR/backup.sh" "$BACKUP_NAME" >> "$PROJECT_DIR/logs/backup.log" 2>&1

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 自动备份完成: $BACKUP_NAME" >> "$PROJECT_DIR/logs/backup.log"
