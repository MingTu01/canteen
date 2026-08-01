#!/bin/bash
#==============================================================
# 构建脚本 - 在 Docker 容器中构建所有产物,输出到 deploy/ 目录
#==============================================================
# 用法:
#   ./scripts/build.sh              # 构建全部
#   ./scripts/build.sh backend      # 仅构建后端
#   ./scripts/build.sh admin-web    # 仅构建管理后台
#   ./scripts/build.sh h5           # 仅构建 H5
#   ./scripts/build.sh terminal     # 仅构建终端
#
# 产物输出:
#   deploy/backend/app.jar
#   deploy/admin-web/html/          # dist 内容
#   deploy/admin-web/nginx.conf
#   deploy/h5/html/
#   deploy/h5/nginx.conf
#   deploy/terminal/html/
#   deploy/terminal/nginx.conf
#
# 设计要点:
#   - 使用 Docker 容器构建,宿主机无需安装 JDK/Node.js
#   - 使用国内镜像源加速(阿里云 Maven / npmmirror)
#   - 构建完成后产物复制到 deploy/,容器自动销毁
#==============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

DEPLOY_DIR="$PROJECT_DIR/deploy"
MAVEN_IMAGE="maven:3.9-eclipse-temurin-25"
NODE_IMAGE="node:20-alpine"

# 阿里云 Maven settings.xml(内联)
MAVEN_SETTINGS='<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <name>Aliyun Maven</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>'

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info()  { echo -e "${GREEN}[构建]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }

# 创建输出目录
init_dirs() {
    mkdir -p "$DEPLOY_DIR/backend"
    mkdir -p "$DEPLOY_DIR/admin-web/html"
    mkdir -p "$DEPLOY_DIR/h5/html"
    mkdir -p "$DEPLOY_DIR/terminal/html"
}

# ---------- 构建后端 ----------
build_backend() {
    info "构建后端 jar..."
    # 写入 Maven settings 到临时文件
    local settings_file="$PROJECT_DIR/backend/settings-aliyun.xml"
    echo "$MAVEN_SETTINGS" > "$settings_file"

    # 使用 Docker 容器构建,挂载源码和 Maven 本地仓库缓存
    docker run --rm \
        -v "$PROJECT_DIR/backend:/build" \
        -v "$PROJECT_DIR/.m2-cache:/root/.m2" \
        -w /build \
        "$MAVEN_IMAGE" \
        sh -c "mvn clean package -Dmaven.test.skip=true -B -s settings-aliyun.xml"

    # 复制产物
    cp "$PROJECT_DIR/backend/target/enterprise-canteen-0.0.1.jar" "$DEPLOY_DIR/backend/app.jar"
    # 清理临时 settings
    rm -f "$settings_file"
    info "后端构建完成: deploy/backend/app.jar ($(du -h "$DEPLOY_DIR/backend/app.jar" | cut -f1))"
}

# ---------- 构建前端通用 ----------
# $1 = 项目名(admin-web/h5/terminal)
build_frontend() {
    local name=$1
    info "构建 $name..."

    # 使用 Docker 容器构建
    docker run --rm \
        -v "$PROJECT_DIR/$name:/build" \
        -w /build \
        "$NODE_IMAGE" \
        sh -c "npm install --registry=https://registry.npmmirror.com && npm run build"

    # 清空旧产物并复制新产物
    rm -rf "$DEPLOY_DIR/$name/html"
    mkdir -p "$DEPLOY_DIR/$name/html"
    cp -r "$PROJECT_DIR/$name/dist/"* "$DEPLOY_DIR/$name/html/"

    # 复制 nginx.conf
    cp "$PROJECT_DIR/$name/nginx.conf" "$DEPLOY_DIR/$name/nginx.conf"

    info "$name 构建完成: deploy/$name/html/ ($(du -sh "$DEPLOY_DIR/$name/html" | cut -f1))"
}

# ---------- 主逻辑 ----------
TARGET=${1:-all}

init_dirs

case "$TARGET" in
    all)
        build_backend
        build_frontend admin-web
        build_frontend h5
        build_frontend terminal
        ;;
    backend)
        build_backend
        ;;
    admin-web|h5|terminal)
        build_frontend "$TARGET"
        ;;
    *)
        error "未知目标: $TARGET"
        echo "用法: $0 [all|backend|admin-web|h5|terminal]"
        exit 1
        ;;
esac

echo ""
info "全部构建完成!产物位于 deploy/ 目录"
echo ""
echo "下一步:"
echo "  首次部署:  ./deploy.sh"
echo "  更新服务:  docker compose restart $TARGET"
