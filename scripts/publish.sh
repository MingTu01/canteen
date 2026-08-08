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
#   deploy.sh                  # 一键部署 CLI(服务器用)
#   scripts/upgrade.sh         # 服务器升级脚本(免构建版)
#   scripts/snapshot.sh        # 快照脚本
#   scripts/backup.sh          # 备份脚本
#   scripts/restore.sh         # 恢复脚本
#   scripts/clean-redeploy.sh  # 完全清理重部署脚本
#   scripts/init-db-user.sh    # 应用数据库用户初始化(deploy.sh 分阶段启动依赖)
#   scripts/cron_backup.sh     # 定时备份脚本
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
        rm -rf "$WORKTREE_DIR"
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

# 构建步骤完成后,恢复 MSYS 路径转换(构建时禁用以避免 docker 挂载路径被篡改,
# 但 git/文件操作需要路径转换以正确处理中文路径)
unset MSYS_NO_PATHCONV
unset MSYS2_ARG_CONV_EXCL

# 捕获主仓库信息(此时仍在 PROJECT_DIR 目录,避免中文路径 + MSYS_NO_PATHCONV 冲突)
REMOTE_URL=$(git remote get-url origin 2>/dev/null || echo "")
if [ -z "$REMOTE_URL" ]; then
    error "无法获取远程仓库地址(origin remote 未配置)"
    exit 1
fi
MAIN_SHA=$(git rev-parse --short HEAD)
info "远程仓库: $REMOTE_URL  主分支 SHA: $MAIN_SHA"

#==============================================================
# 步骤 2:准备 deploy 分支(本地 clone)
#==============================================================
# 使用 git clone 代替 git worktree:
#   - worktree 不能放在主仓库工作树内部,Windows 沙箱又限制项目外部目录
#   - clone 到项目内部临时目录(.deploy-tmp),可绕过两个限制
step "步骤 2/5 准备 deploy 分支"

WORKTREE_DIR="$PROJECT_DIR/.deploy-tmp"
rm -rf "$WORKTREE_DIR"

# 检查 deploy 分支是否存在(本地或远程)
DEPLOY_EXISTS=$(git rev-parse --verify "refs/heads/$DEPLOY_BRANCH" 2>/dev/null || \
                git rev-parse --verify "refs/remotes/origin/$DEPLOY_BRANCH" 2>/dev/null || \
                echo "")

# 不克隆远程 deploy 分支(含大量构建产物,克隆极慢)
# 直接创建空仓库 + orphan 分支,复制文件后 force push 覆盖远程
info "创建空 orphan 分支(无需克隆远程 deploy 分支)..."
mkdir -p "$WORKTREE_DIR"
cd "$WORKTREE_DIR"
git init --quiet
git remote add origin "$REMOTE_URL"
git checkout --orphan "$DEPLOY_BRANCH" 2>/dev/null || git checkout -B "$DEPLOY_BRANCH"
# 清空 orphan 分支的暂存区(初始状态下可能残留 main 分支内容)
git rm -rf . 2>/dev/null || true

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

# install.sh(一键安装脚本,GitHub 一行命令部署入口)
cp "$PROJECT_DIR/install.sh" .
chmod +x install.sh

# deploy.sh(一键部署 CLI,服务器克隆 deploy 分支后依赖它)
cp "$PROJECT_DIR/deploy.sh" .
chmod +x deploy.sh

# VERSIONS.json
cp "$PROJECT_DIR/VERSIONS.json" .

# backend/Dockerfile.runtime(docker-compose.yml 构建后端基础镜像需要)
mkdir -p backend
cp "$PROJECT_DIR/backend/Dockerfile.runtime" backend/

# 运行时脚本(仅复制服务器需要的,不含 build.sh / publish.sh)
# 注意:init-db-user.sh 必须包含,deploy.sh 分阶段启动依赖它创建应用数据库用户
mkdir -p scripts
for script in upgrade.sh snapshot.sh backup.sh restore.sh clean-redeploy.sh init-db-user.sh cron_backup.sh; do
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

# 强制为所有 shell 脚本设置可执行位(100755)。
# 原因:Windows 上 git 默认不保留 +x,服务器 git pull 后文件会丢失执行权限,
# 导致 `sudo ./deploy.sh` 报"权限不够 (os error 13)"。
# git update-index --chmod=+x 会把执行位写进索引,Windows 下同样生效。
find . -name '*.sh' -not -path './.git/*' | while read -r f; do
    git update-index --chmod=+x "$f"
done

git commit -m "deploy: v${VERSION} (${SCOPE})"

后端: v${BE_VER}
管理后台: v${HW_VER}
H5: v${H5_VER}

发布时间: $(date '+%Y-%m-%d %H:%M:%S')
源码: main@${MAIN_SHA}" --allow-empty

info "提交完成"

# 推送(clone 的 origin 已指向 GitHub 远程)
info "推送 deploy 分支到远程..."
git push origin "$DEPLOY_BRANCH" --force 2>/dev/null || {
    warn "force 推送失败,尝试普通推送..."
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
echo "  源码提交: main@${MAIN_SHA}"
echo ""
echo "  服务器更新方式:"
echo "    canteen → 1) 升级全部"
echo "    (服务器跟踪 deploy 分支,无需构建,秒级更新)"
echo ""

cd "$PROJECT_DIR"
