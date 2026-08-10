#!/bin/bash
#==============================================================
# 后台定时自愈 cron 脚本
# -------------------------------------------------------------
# 用途:周期性地检测数据库/项目是否异常,发现严重问题时自动修复
#   - MySQL data 损坏 / crash-loop           → 自动走 self_heal.py fix-mysql
#   - 项目关键文件缺失/损坏                  → 自动走 self_heal.py fix-files
#   - 一切正常                                → 仅记录一行日志,不做任何操作
#
# 配置方法(在服务器上执行,或通过 canteen 菜单 18 一键安装):
#   crontab -e
#     每 5 分钟检测一次:  */5 * * * * /opt/canteen/scripts/cron_self_heal.sh
#     每 30 分钟检测一次: */30 * * * * /opt/canteen/scripts/cron_self_heal.sh
#
# 安全设计:
#   - flock 互斥锁:同一时刻只跑一个实例,避免重复触发修复互相打架
#   - 冷却时间:修复动作(尤其 MySQL 重建)后 10 分钟内不再重复修复,
#     防止"边修边崩"时 cron 反复破坏性重建拖垮服务
#   - 自动修复以 --auto 模式运行:默认禁止删除 MySQL 数据卷重建(防手滑/误判删库),
#     仅做"温和重启→备份优先恢复";确需自动重建须在 .env 设置 SELF_HEAL_MYSQL_REBUILD=on
#   - 所有输出写入 logs/self_heal.log,不往终端刷屏
#==============================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
HEAL_SCRIPT="$SCRIPT_DIR/self_heal.py"

LOG_DIR="$PROJECT_DIR/logs"
LOG_FILE="$LOG_DIR/self_heal.log"
LOCK_FILE="$LOG_DIR/.self_heal.lock"
STATE_FILE="$LOG_DIR/.self_heal.state"

# 修复后的冷却时间(秒):10 分钟内不重复执行破坏性修复
COOLDOWN=600

mkdir -p "$LOG_DIR" 2>/dev/null || true

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# 互斥锁:拿不到锁说明上一个实例还在跑,直接退出
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    log "[定时自愈] 上一个实例仍在运行,本次跳过"
    exit 0
fi

# 前置检查:自愈脚本必须存在
if [ ! -f "$HEAL_SCRIPT" ]; then
    log "[定时自愈] ⚠ 缺少 $HEAL_SCRIPT,跳过(请先部署或 canteen/selfheal)"
    exit 0
fi

log "[定时自愈] 开始自检..."

# ---------- 1. 仅自检 ----------
CHECK_OUTPUT=$(python3 "$HEAL_SCRIPT" check 2>&1)
CHECK_RC=$?
log "[定时自愈] 自检完成(exit=$CHECK_RC)"

# 自检返回 0 = 无严重问题(仅提示级),无需修复
if [ "$CHECK_RC" -eq 0 ]; then
    log "[定时自愈] 未发现严重问题,无需修复"
    exit 0
fi

# ---------- 2. 有严重问题:检查冷却时间 ----------
NOW=$(date +%s)
LAST_FIX=""
if [ -f "$STATE_FILE" ]; then
    LAST_FIX=$(cat "$STATE_FILE" 2>/dev/null || echo "")
fi

if [ -n "$LAST_FIX" ] && [ "$LAST_FIX" -ge 0 ] 2>/dev/null \
   && [ $((NOW - LAST_FIX)) -lt "$COOLDOWN" ]; then
    log "[定时自愈] 距上次修复不足 ${COOLDOWN}s,进入冷却,本次不重复修复"
    echo "$CHECK_OUTPUT" | sed 's/^/[自检] /' >> "$LOG_FILE"
    exit 0
fi

# ---------- 3. 执行自动修复 ----------
log "[定时自愈] 检测到严重问题,开始自动修复..."
echo "$CHECK_OUTPUT" | sed 's/^/[自检] /' >> "$LOG_FILE"

FIX_OUTPUT=$(python3 "$HEAL_SCRIPT" fix --yes --auto 2>&1)
FIX_RC=$?
log "[定时自愈] 自动修复完成(exit=$FIX_RC)"
echo "$FIX_OUTPUT" | sed 's/^/[修复] /' >> "$LOG_FILE"

# 记录本次修复时间(用于冷却)
echo "$NOW" > "$STATE_FILE"

exit "$FIX_RC"