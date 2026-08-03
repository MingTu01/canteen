#!/bin/bash
#==============================================================
# 一键清理并重新部署(不备份,全部清除)
#==============================================================
# 用法: sudo bash clean-redeploy.sh
#
# 做什么:
#   1. 停止并删除所有容器/镜像/卷/网络
#   2. 删除 /opt/canteen 整个目录
#   3. 重新克隆 deploy 分支
#   4. 运行 deploy.sh 向导(自动配置 .env + 启动服务 + 安全加固)
#
# 适合场景: 服务器状态混乱,想全部推倒重来
# ⚠️ 警告: 数据库数据将全部丢失,不可恢复!
#==============================================================
set -e

PROJECT_DIR="/opt/canteen"
REPO_URL="https://github.com/MingTu01/canteen.git"
BRANCH="deploy"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[清理]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }
step()  { echo -e "\n${CYAN}========== $1 ==========${NC}"; }

# 必须用 root 或 sudo 执行
if [ "$(id -u)" -ne 0 ]; then
    error "请用 sudo 执行: sudo bash clean-redeploy.sh"
    exit 1
fi

# 如果脚本在 PROJECT_DIR 内,先复制到 /tmp 再执行(否则会删除自身)
SCRIPT_PATH="$(readlink -f "$0")"
if echo "$SCRIPT_PATH" | grep -q "^${PROJECT_DIR}"; then
    info "脚本在 ${PROJECT_DIR} 内,复制到 /tmp 执行..."
    cp "$SCRIPT_PATH" /tmp/clean-redeploy.sh
    exec bash /tmp/clean-redeploy.sh
fi

# 确认
echo -e "${RED}======================================================${NC}"
echo -e "${RED}  ⚠️  此操作将删除所有数据,不可恢复!${NC}"
echo -e "${RED}  - 所有数据库数据${NC}"
echo -e "${RED}  - 所有上传的图片${NC}"
echo -e "${RED}  - 所有备份文件${NC}"
echo -e "${RED}  - 所有容器和镜像${NC}"
echo -e "${RED}======================================================${NC}"
echo ""
read -p "确认全部清除并重新部署? (输入 YES 继续): " confirm
if [ "$confirm" != "YES" ]; then
    info "已取消"
    exit 0
fi

#==============================================================
# 步骤 1:停止并删除所有容器/卷/镜像
#==============================================================
step "步骤 1/4 停止并删除所有 Docker 资源"

cd "$PROJECT_DIR" 2>/dev/null || true

# 停止所有服务
if [ -f docker-compose.yml ]; then
    info "停止 docker compose 服务..."
    docker compose down --remove-orphans 2>/dev/null || true
fi

# 删除所有 canteen 相关容器(兜底)
info "删除残留容器..."
docker ps -a --filter "name=canteen-" -q | xargs -r docker rm -f 2>/dev/null || true

# 删除所有卷(数据库数据 + redis 数据)
info "删除 Docker 卷..."
docker volume ls --filter "name=canteen" -q | xargs -r docker volume rm -f 2>/dev/null || true
docker volume ls --filter "name=enterprise-canteen" -q | xargs -r docker volume rm -f 2>/dev/null || true

# 删除 canteen 相关镜像
info "删除 Docker 镜像..."
docker images --filter "reference=canteen*" -q | xargs -r docker rmi -f 2>/dev/null || true
docker images --filter "reference=*canteen*" -q | xargs -r docker rmi -f 2>/dev/null || true

# 清理悬挂资源
info "清理悬挂 Docker 资源..."
docker system prune -f --volumes 2>/dev/null || true

info "Docker 资源清理完成"

#==============================================================
# 步骤 2:删除旧项目目录
#==============================================================
step "步骤 2/4 删除旧项目目录"

info "删除 $PROJECT_DIR ..."
rm -rf "$PROJECT_DIR"

info "旧目录已删除"

#==============================================================
# 步骤 3:重新克隆 deploy 分支
#==============================================================
step "步骤 3/4 克隆 deploy 分支"

info "克隆 $BRANCH 分支..."
git clone --depth 1 --single-branch --branch "$BRANCH" "$REPO_URL" "$PROJECT_DIR"
cd "$PROJECT_DIR"

# 设置脚本执行权限
chmod +x canteen.sh deploy.sh scripts/*.sh 2>/dev/null || true

info "克隆完成"
git log --oneline -1

#==============================================================
# 步骤 4:运行 deploy.sh
#==============================================================
step "步骤 4/4 运行部署向导"

info "启动 deploy.sh..."
echo ""
echo -e "${GREEN}==========================================${NC}"
echo -e "${GREEN}  清理完成,进入部署向导${NC}"
echo -e "${GREEN}  deploy.sh 会引导你:${NC}"
echo -e "${GREEN}    1. 生成安全的 .env(密码/密钥)${NC}"
echo -e "${GREEN}    2. 构建后端基础镜像${NC}"
echo -e "${GREEN}    3. 启动所有服务${NC}"
echo -e "${GREEN}    4. 安全加固(UFW + fail2ban + 入侵检测)${NC}"
echo -e "${GREEN}==========================================${NC}"
echo ""

bash deploy.sh
