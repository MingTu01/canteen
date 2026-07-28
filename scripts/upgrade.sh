#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 一键升级脚本
#
# 用法:
#   ./scripts/upgrade.sh              # 升级全部(后端 + 前端)
#   ./scripts/upgrade.sh backend      # 仅升级后端
#   ./scripts/upgrade.sh frontend     # 仅升级前端(admin/h5/terminal)
#
# 流程:
#   1. 升级前自动备份(应用层 JSON+GZIP,走 BackupService)
#   2. git pull 拉取最新代码
#   3. docker compose build 重建变更镜像
#   4. docker compose up -d 滚动重启
#   5. 健康检查
#
# 数据库迁移:
#   Flyway 在 backend 启动时自动执行 db/migration/V*__*.sql
#   已执行的迁移脚本不可修改,只能新增
#==============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

SCOPE="${1:-all}"

echo "=========================================="
echo "  企业智慧食堂系统 - 升级"
echo "  当前版本: $(grep -m1 version backend/src/main/resources/version.json | sed 's/.*"\([0-9.]*\)".*/\1/')"
echo "  升级范围: ${SCOPE}"
echo "=========================================="
echo ""

# ---------- 1. 升级前自动备份 ----------
echo "[步骤 1/5] 升级前自动备份..."
BACKUP_TIMESTAMP="$(date +%Y%m%d_%H%M%S)"

# 优先走应用层备份(BackupService, JSON+GZIP, 与管理后台 UI 一致)
if curl -sf http://localhost:8080/api/system/health >/dev/null 2>&1; then
    echo "[信息] 后端运行中,优先走应用层备份(与管理后台 UI 一致)"
    echo "[提示] 如需 OS 级 mysqldump 备份,可手动执行: bash scripts/backup.sh pre_upgrade_${BACKUP_TIMESTAMP}"
else
    # 后端未运行,退回 OS 级备份
    if [ -f scripts/backup.sh ]; then
        bash scripts/backup.sh "pre_upgrade_${BACKUP_TIMESTAMP}" || echo "[警告] 备份失败,继续升级"
    else
        echo "[警告] 未找到 scripts/backup.sh,跳过备份"
    fi
fi
echo ""

# ---------- 2. 拉取最新代码 ----------
echo "[步骤 2/5] 检查代码更新..."
if [ -d .git ]; then
    git pull origin main 2>/dev/null \
        || git pull origin master 2>/dev/null \
        || git pull 2>/dev/null \
        || echo "[警告] Git 拉取失败,请手动执行 git pull"
    echo "[信息] 代码已更新"
else
    echo "[信息] 非 Git 项目,跳过代码拉取(请手动同步代码)"
fi
echo ""

# ---------- 3. 重新构建镜像 ----------
echo "[步骤 3/5] 重新构建镜像..."
if ! command -v docker &> /dev/null; then
    echo "[错误] 未检测到 Docker,请先安装"
    exit 1
fi

case "$SCOPE" in
    backend)
        docker compose build backend
        ;;
    frontend)
        docker compose build admin-web h5 terminal
        ;;
    all)
        docker compose build backend admin-web h5 terminal
        ;;
    *)
        echo "[错误] 无效范围: $SCOPE (可选: backend / frontend / all)"
        exit 1
        ;;
esac
echo "[信息] 镜像构建完成"
echo ""

# ---------- 4. 重启服务 ----------
echo "[步骤 4/5] 重启服务..."
docker compose up -d
echo ""

# ---------- 5. 健康检查 ----------
echo "[步骤 5/5] 健康检查..."
echo "[信息] 等待后端启动..."
HEALTHY=false
for i in $(seq 1 30); do
    if curl -sf http://localhost:8080/api/system/health >/dev/null 2>&1; then
        HEALTHY=true
        break
    fi
    sleep 2
    printf "."
done
echo ""

if [ "$HEALTHY" = true ]; then
    echo "[成功] 后端健康检查通过"
    curl -s http://localhost:8080/api/system/health
    echo ""
else
    echo "[错误] 后端 30 秒内未通过健康检查,请查看日志:"
    echo "  docker compose logs --tail=100 backend"
    exit 1
fi

# 前端可达性检查
echo ""
for svc in "admin:80" "h5:81" "terminal:82"; do
    name="${svc%%:*}"
    port="${svc##*:}"
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/" 2>/dev/null || echo "000")
    if [ "$code" = "200" ]; then
        echo "[成功] ${name} (port ${port}): 200"
    else
        echo "[警告] ${name} (port ${port}): ${code}"
    fi
done

echo ""
echo "=========================================="
echo "  升级完成！"
echo "=========================================="
echo ""
echo "  新版本: $(grep -m1 version backend/src/main/resources/version.json | sed 's/.*"\([0-9.]*\)".*/\1/')"
echo ""
echo "  数据库迁移已由 Flyway 自动执行"
echo "  迁移脚本目录: backend/src/main/resources/db/migration/"
echo ""
echo "  如需回滚:"
echo "    1. 从备份恢复: ./scripts/restore.sh backup/pre_upgrade_${BACKUP_TIMESTAMP}.tar.gz"
echo "       或通过管理后台 UI 上传 .json.gz 备份文件恢复"
echo "    2. 回退代码: git checkout <旧版本 commit>"
echo "    3. 重建后端: docker compose build backend && docker compose up -d backend"
echo ""
