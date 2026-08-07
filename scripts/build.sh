#!/bin/bash
#==============================================================
# 构建脚本 - 在 Docker 容器中构建所有产物,输出到 deploy/ 目录
#==============================================================
# 用法:
#   ./scripts/build.sh              # 构建全部
#   ./scripts/build.sh backend      # 仅构建后端
#   ./scripts/build.sh admin-web    # 仅构建管理后台
#   ./scripts/build.sh h5           # 仅构建 H5
#
# 产物输出:
#   deploy/backend/app.jar
#   deploy/admin-web/html/          # dist 内容
#   deploy/admin-web/nginx.conf
#   deploy/h5/html/
#   deploy/h5/nginx.conf
#
# 说明:
#   - X86 终端不在 Docker 中部署,改为在 Windows 上打包为独立 EXE 安装包
#     (详见 src-python/build_installer.py)
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

    # 复制产物(jar 文件名含版本号,用通配符匹配避免版本耦合)
    # 排除 -sources.jar / -javadoc.jar / plain.jar(原 jar)
    local jar_file
    jar_file=$(ls "$PROJECT_DIR/backend/target/enterprise-canteen-"*.jar 2>/dev/null | grep -v -E 'sources|javadoc|plain' | head -1)
    if [ -z "$jar_file" ]; then
        error "未找到后端构建产物: enterprise-canteen-*.jar"
        error "请检查 backend/target/ 目录"
        exit 1
    fi
    info "构建产物: $(basename "$jar_file")"
    # deploy/ 可能是 sudo 部署时 root 所有,canteen 用户无权覆盖,自动修复权限
    if ! cp "$jar_file" "$DEPLOY_DIR/backend/app.jar" 2>/dev/null; then
        info "deploy/ 目录权限不足,尝试 sudo 修复..."
        sudo chown -R "$(whoami):$(whoami)" "$DEPLOY_DIR" 2>/dev/null || true
        cp "$jar_file" "$DEPLOY_DIR/backend/app.jar"
    fi
    # settings-aliyun.xml 保留为 tracked 文件(下次构建会覆盖),避免 git 误报删除
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

    # 验证构建产物:dist/index.html 必须存在
    if [ ! -f "$PROJECT_DIR/$name/dist/index.html" ]; then
        error "$name 构建失败:dist/index.html 不存在"
        error "保留旧产物不动,请检查构建日志排查原因"
        exit 1
    fi

    # 原子替换:先复制到临时目录,验证成功后再替换旧产物
    # 避免复制失败导致 html 目录为空 → nginx 403
    # 临时目录创建在 deploy 同级(同文件系统),mv 才能原子操作
    local tmp_html="$DEPLOY_DIR/$name/.html.tmp.$$"
    rm -rf "$tmp_html"
    mkdir -p "$tmp_html"
    cp -r "$PROJECT_DIR/$name/dist/"* "$tmp_html/"

    # 再次验证临时目录中有 index.html
    if [ ! -f "$tmp_html/index.html" ]; then
        rm -rf "$tmp_html"
        error "$name 产物复制失败:临时目录中未找到 index.html"
        error "保留旧产物不动"
        exit 1
    fi

    # 原子替换:删除旧 html,移动新 html
    rm -rf "$DEPLOY_DIR/$name/html"
    mv "$tmp_html" "$DEPLOY_DIR/$name/html"

    # 复制 nginx.conf(单独处理,nginx.conf 不需要原子性)
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
        ;;
    backend)
        build_backend
        ;;
    admin-web|h5)
        build_frontend "$TARGET"
        ;;
    *)
        error "未知目标: $TARGET"
        echo "用法: $0 [all|backend|admin-web|h5]"
        echo "提示:X86 终端打包请在 Windows 上运行 src-python/build_installer.py"
        exit 1
        ;;
esac

echo ""
info "全部构建完成!产物位于 deploy/ 目录"
echo ""
echo "下一步:"
echo "  首次部署:  ./deploy.sh"
echo "  更新服务:  docker compose restart $TARGET"
