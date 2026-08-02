#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 构建并发布到 deploy 分支
#==============================================================
# 在开发机器上运行此脚本,构建全部产物并推送到 deploy 分支。
# 服务器只需跟踪 deploy 分支,git pull + docker restart 即可更新,
# 无需安装 Maven / Node.js,不会出现构建失败。
#
# 用法:
#   ./scripts/publish.sh              # 构建全部并发布
#   ./scripts/publish.sh backend      # 仅构建并发布后端
#   ./scripts/publish.sh frontend     # 仅构建并发布前端
#
# deploy 分支结构(orphan 分支,独立历史):
#   deploy/                    # 构建产物(直接可用)
#   docker-compose.yml         # 编排配置
#   .env.example               # 环境变量模板
#   canteen.sh                 # 管理面板
#   scripts/upgrade.sh         # 服务器升级脚本(免构建版)
#   scripts/snapshot.sh        # 快照脚本
#   scripts/backup.sh          # 备份脚本
#   scripts/restore.sh         # 恢复脚本
#   VERSIONS.json              # 版本信息
#==============================================================
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

SCOPE="${1:-all}"
DEPLOY_BRANCH="deploy"
WORKTREE_DIR=""

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[发布]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }
step()  { echo -e "\n${BLUE}========== $1 ==========${NC}"; }

cleanup() {
    if [ -n "$WORKTREE_DIR" ] && [ -d "$WORKTREE_DIR" ]; then
        info "清理临时工作区..."
        git worktree remove "$WORKTREE_DIR" --force 2>/dev/null || rm -rf "$WORKTREE_DIR"
    fi
}
trap cleanup EXIT

#==============================================================
# 前置检查
#==============================================================
step "步骤 0/5 前置检查"

# 检查是否在 main 分支
CURRENT_BRANCH=$(git branch --show-current 2>/dev/null || echo "")
if [ "$CURRENT_BRANCH" != "main" ]; then
    error "当前不在 main 分支(当前: ${CURRENT_BRANCH:-detached}),请在 main 分支运行此脚本"
    exit 1
fi
info "当前分支: main"

# 检查工作区是否干净
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    error "工作区不干净,请先提交或 stash 修改"
    git status --short
    exit 1
fi
info "工作区干净"

# 检查 build.sh 可用
if [ ! -f "$PROJECT_DIR/scripts/build.sh" ]; then
    error "scripts/build.sh 不存在"
    exit 1
fi

#==============================================================
# 步骤 1:构建产物
#==============================================================
step "步骤 1/5 构建产物"
chmod +x "$PROJECT_DIR/scripts/build.sh"

case "$SCOPE" in
    backend)
        info "构建后端..."
        "$PROJECT_DIR/scripts/build.sh" backend
        ;;
    frontend)
        info "构建前端..."
        "$PROJECT_DIR/scripts/build.sh" admin-web
        "$PROJECT_DIR/scripts/build.sh" h5
        ;;
    all)
        info "构建全部..."
        "$PROJECT_DIR/scripts/build.sh" all
        ;;
    *)
        echo "用法: $0 [backend|frontend|all]"
        exit 1
        ;;
esac

# 验证产物存在
if [ "$SCOPE" = "backend" ] || [ "$SCOPE" = "all" ]; then
    if [ ! -f "$PROJECT_DIR/deploy/backend/app.jar" ]; then
        error "后端产物不存在: deploy/backend/app.jar"
        exit 1
    fi
fi
if [ "$SCOPE" = "frontend" ] || [ "$SCOPE" = "all" ]; then
    if [ ! -f "$PROJECT_DIR/deploy/admin-web/html/index.html" ]; then
        error "admin-web 产物不存在: deploy/admin-web/html/index.html"
        exit 1
    fi
    if [ ! -f "$PROJECT_DIR/deploy/h5/html/index.html" ]; then
        error "h5 产物不存在: deploy/h5/html/index.html"
        exit 1
    fi
fi
info "产物构建完成"

#==============================================================
# 步骤 2:准备 deploy 分支(worktree)
#==============================================================
step "步骤 2/5 准备 deploy 分支"

WORKTREE_DIR=$(mktemp -d /tmp/canteen-deploy.XXXXXX)

# 检查 deploy 分支是否存在(本地或远程)
DEPLOY_EXISTS=$(git rev-parse --verify "refs/heads/$DEPLOY_BRANCH" 2>/dev/null || \
                git rev-parse --verify "refs/remotes/origin/$DEPLOY_BRANCH" 2>/dev/null || \
                echo "")

if [ -z "$DEPLOY_EXISTS" ]; then
    info "deploy 分支不存在,创建 orphan 分支..."
    # 创建 orphan 分支(无历史)
    git worktree add --detach "$WORKTREE_DIR" HEAD 2>/dev/null
    cd "$WORKTREE_DIR"
    git checkout --orphan "$DEPLOY_BRANCH"
    git rm -rf . 2>/dev/null || true
else
    info "deploy 分支已存在,检出..."
    # 使用已有分支
    git worktree add "$WORKTREE_DIR" "$DEPLOY_BRANCH" 2>/dev/null || {
        # 如果本地分支不存在但远程存在,基于远程创建
        git worktree add "$WORKTREE_DIR" -b "$DEPLOY_BRANCH" "origin/$DEPLOY_BRANCH" 2>/dev/null
    }
    cd "$WORKTREE_DIR"
fi

info "deploy 工作区就绪: $WORKTREE_DIR"

#==============================================================
# 步骤 3:复制产物和运行时文件到 deploy 分支
#==============================================================
step "步骤 3/5 复制产物到 deploy 分支"

cd "$WORKTREE_DIR"

# 清空工作区(保留 .git)
find . -maxdepth 1 -not -name '.git' -not -name '.' -exec rm -rf {} + 2>/dev/null || true

# 复制构建产物
info "复制 deploy/ 产物..."
case "$SCOPE" in
    backend)
        # 仅更新后端
        mkdir -p deploy/backend
        cp "$PROJECT_DIR/deploy/backend/app.jar" deploy/backend/
        # 保留已有的前端产物(如果存在)
        if [ -d "deploy/admin-web" ]; then
            info "保留已有 admin-web 产物"
        fi
        if [ -d "deploy/h5" ]; then
            info "保留已有 h5 产物"
        fi
        ;;
    frontend)
        # 仅更新前端
        rm -rf deploy/admin-web deploy/h5
        cp -r "$PROJECT_DIR/deploy/admin-web" deploy/
        cp -r "$PROJECT_DIR/deploy/h5" deploy/
        # 保留已有后端产物
        if [ -f "deploy/backend/app.jar" ]; then
            info "保留已有后端产物"
        fi
        ;;
    all)
        # 全部更新
        rm -rf deploy
        cp -r "$PROJECT_DIR/deploy" .
        ;;
esac

# 复制运行时文件(每次都更新)
info "复制运行时文件..."

# docker-compose.yml
cp "$PROJECT_DIR/docker-compose.yml" .

# .env.example
cp "$PROJECT_DIR/.env.example" .

# canteen.sh
cp "$PROJECT_DIR/canteen.sh" .
chmod +x canteen.sh

# VERSIONS.json
cp "$PROJECT_DIR/VERSIONS.json" .

# backend/Dockerfile.runtime(docker-compose.yml 构建后端基础镜像需要)
mkdir -p backend
cp "$PROJECT_DIR/backend/Dockerfile.runtime" backend/

# 运行时脚本(仅复制服务器需要的,不含 build.sh / publish.sh)
mkdir -p scripts
for script in upgrade.sh snapshot.sh backup.sh restore.sh; do
    if [ -f "$PROJECT_DIR/scripts/$script" ]; then
        cp "$PROJECT_DIR/scripts/$script" scripts/
        chmod +x "scripts/$script"
    fi
done

# 验证关键文件
if [ ! -f "docker-compose.yml" ]; then
    error "docker-compose.yml 复制失败"
    exit 1
fi
if [ ! -f "canteen.sh" ]; then
    error "canteen.sh 复制失败"
    exit 1
fi

info "文件复制完成"

# 显示 deploy 目录结构
echo ""
info "deploy 分支内容:"
find . -maxdepth 2 -not -path './.git/*' -not -name '.git' | head -30
echo ""

#==============================================================
# 步骤 4:提交并推送
#==============================================================
step "步骤 4/5 提交并推送 deploy 分支"

cd "$WORKTREE_DIR"

# 读取版本号
VERSION=$(python3 -c "import json; print(json.load(open('VERSIONS.json'))['system']['version'])" 2>/dev/null || echo "unknown")
BE_VER=$(python3 -c "import json; print(json.load(open('VERSIONS.json'))['backend']['version'])" 2>/dev/null || echo "?")
HW_VER=$(python3 -c "import json; print(json.load(open('VERSIONS.json'))['admin-web']['version'])" 2>/dev/null || echo "?")
H5_VER=$(python3 -c "import json; print(json.load(open('VERSIONS.json'))['h5']['version'])" 2>/dev/null || echo "?")

git add -A
git commit -m "deploy: v${VERSION} (${SCOPE})

后端: v${BE_VER}
管理后台: v${HW_VER}
H5: v${H5_VER}

发布时间: $(date '+%Y-%m-%d %H:%M:%S')
源码: main@$(git -C "$PROJECT_DIR" rev-parse --short HEAD)" --allow-empty

info "提交完成"

# 推送
info "推送 deploy 分支到远程..."
git push origin "$DEPLOY_BRANCH" --force-with-lease 2>/dev/null || {
    warn "force-with-lease 失败,尝试普通推送..."
    git push origin "$DEPLOY_BRANCH"
}
info "推送完成"

#==============================================================
# 步骤 5:完成
#==============================================================
step "步骤 5/5 发布完成"

echo ""
echo -e "${GREEN}==========================================${NC}"
echo -e "${GREEN}  发布完成!${NC}"
echo -e "${GREEN}==========================================${NC}"
echo "  系统版本: v${VERSION}"
echo "  后端: v${BE_VER}  管理后台: v${HW_VER}  H5: v${H5_VER}"
echo "  发布范围: ${SCOPE}"
echo "  源码提交: main@$(git -C "$PROJECT_DIR" rev-parse --short HEAD)"
echo ""
echo "  服务器更新方式:"
echo "    canteen → 1) 升级全部"
echo "    (服务器跟踪 deploy 分支,无需构建,秒级更新)"
echo ""

cd "$PROJECT_DIR"
