#!/bin/bash
#==============================================================
# 更新脚本 - 拉取代码 + 重新构建产物 + 重启服务(不重建镜像)
#==============================================================
# 用法:
#   ./scripts/update.sh              # 更新全部
#   ./scripts/update.sh backend      # 仅更新后端
#   ./scripts/update.sh admin-web    # 仅更新管理后台
#   ./scripts/update.sh h5           # 仅更新 H5
#
# 此脚本不会重建 Docker 镜像,仅:
#   1. git pull 拉取最新代码
#   2. 在 Docker 容器中重新构建产物
#   3. 重启对应服务(卷映射自动加载新产物)
#
# 注意:X86 终端不在 Docker 中部署,需在 Windows 上单独打包
#       (运行 src-python/build_installer.py)
#==============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
info() { echo -e "${GREEN}[更新]${NC} $1"; }
warn() { echo -e "${YELLOW}[警告]${NC} $1"; }

TARGET=${1:-all}

# 1. 拉取最新代码
info "拉取最新代码..."
git pull

# 2. 构建产物
info "构建产物..."
chmod +x scripts/build.sh
if [[ "$TARGET" == "all" ]]; then
    ./scripts/build.sh all
else
    ./scripts/build.sh "$TARGET"
fi

# 3. 重启服务(不重建镜像)
info "重启服务..."
if [[ "$TARGET" == "all" ]]; then
    docker compose restart backend admin-web h5
elif [[ "$TARGET" == "backend" ]]; then
    docker compose restart backend
else
    docker compose restart "$TARGET"
fi

# 4. 健康检查
info "健康检查..."
sleep 5
if [[ "$TARGET" == "all" ]] || [[ "$TARGET" == "backend" ]]; then
    if curl -sf http://localhost:18082/api/system/health &> /dev/null; then
        info "后端 API: 正常"
    else
        warn "后端启动中,请稍等..."
    fi
fi

echo ""
info "更新完成!"
echo ""
echo "如需查看日志: docker compose logs -f $TARGET"
