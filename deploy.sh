#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 一键部署脚本(生产环境)
#==============================================================
# 功能:
#   1. 检测并安装 Docker + Docker Compose(国内源)
#   2. 配置 Docker 镜像加速器
#   3. 配置环境变量(.env)
#   4. 在 Docker 容器中构建所有产物(无需宿主机 JDK/Node)
#   5. 构建后端运行时基础镜像(仅首次)
#   6. 启动全部服务
#
# 用法:
#   chmod +x deploy.sh
#   ./deploy.sh              # 完整部署
#   ./deploy.sh --skip-env   # 跳过环境安装(已装好 Docker 时)
#
# 适用系统: CentOS 7+/8/9, Ubuntu 18.04+/20.04/22.04/24.04, Debian 10+
#==============================================================

set -e

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
step()  { echo -e "\n${BLUE}========== $1 ==========${NC}"; }

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

SKIP_ENV=false
if [[ "$1" == "--skip-env" ]]; then
    SKIP_ENV=true
fi

#==============================================================
# Step 1: 检测并安装 Docker
#==============================================================
install_docker() {
    step "1/6 检测 Docker 环境"

    if command -v docker &> /dev/null && docker info &> /dev/null; then
        info "Docker 已安装且运行中"
        return 0
    fi

    if [[ "$SKIP_ENV" == "true" ]]; then
        warn "跳过环境安装(--skip-env),但 Docker 不可用,退出"
        exit 1
    fi

    info "Docker 未安装,开始安装(使用国内源)..."

    # 检测包管理器
    if command -v apt-get &> /dev/null; then
        # Ubuntu/Debian
        info "检测到 Debian/Ubuntu,使用阿里云镜像源安装..."
        apt-get update -y
        apt-get install -y ca-certificates curl gnupg lsb-release
        install -m 0755 -d /etc/apt/keyrings
        # 使用阿里云 Docker GPG 源
        curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        chmod a+r /etc/apt/keyrings/docker.gpg
        echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list
        apt-get update -y
        apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    elif command -v yum &> /dev/null; then
        # CentOS/RHEL
        info "检测到 CentOS/RHEL,使用阿里云镜像源安装..."
        yum install -y yum-utils
        yum-config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
        yum install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    else
        error "不支持的操作系统,请手动安装 Docker"
        exit 1
    fi

    systemctl enable docker
    systemctl start docker
    info "Docker 安装完成"
}

#==============================================================
# Step 2: 配置 Docker 镜像加速器
#==============================================================
configure_docker_mirror() {
    step "2/6 配置 Docker 镜像加速器"

    local daemon_json="/etc/docker/daemon.json"
    local need_write=false

    if [[ ! -f "$daemon_json" ]] || ! grep -q "registry-mirrors" "$daemon_json"; then
        need_write=true
    fi

    if [[ "$need_write" == "true" ]]; then
        info "写入国内 Docker 镜像加速器..."
        mkdir -p /etc/docker
        cat > "$daemon_json" <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1panel.live",
    "https://docker.m.daocloud.io",
    "https://dockerhub.icu",
    "https://hub-mirror.c.163.com",
    "https://mirror.baidubce.com"
  ],
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  }
}
EOF
        systemctl daemon-reload
        systemctl restart docker
        info "镜像加速器配置完成"
    else
        info "镜像加速器已配置,跳过"
    fi
}

#==============================================================
# Step 3: 配置环境变量
#==============================================================
configure_env() {
    step "3/6 配置环境变量"

    if [[ ! -f .env ]]; then
        info "生成 .env 文件(请修改默认密码!)..."
        cat > .env <<EOF
# MySQL 数据库密码(生产环境必须修改!)
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 16 2>/dev/null || echo "canteen-$(date +%s)-change-me")

# JWT 密钥(生产环境必须修改!)
JWT_SECRET=$(openssl rand -hex 32 2>/dev/null || echo "jwt-$(date +%s)-change-me")

# Token 过期时间(毫秒)
JWT_EXPIRATION=86400000
JWT_EMPLOYEE_EXPIRATION=2592000000
JWT_TERMINAL_EXPIRATION=31536000000
EOF
        warn ".env 已生成,密码为随机值,请记录!"
        cat .env
    else
        info ".env 已存在,跳过"
    fi
}

#==============================================================
# Step 4: 构建产物
#==============================================================
build_artifacts() {
    step "4/6 构建业务产物(在 Docker 容器中,无需宿主机 JDK/Node)"

    info "拉取构建镜像(首次较慢,使用国内源加速)..."
    docker pull maven:3.9-eclipse-temurin-25
    docker pull node:20-alpine

    info "开始构建..."
    chmod +x scripts/build.sh
    ./scripts/build.sh all
}

#==============================================================
# Step 5: 构建后端运行时镜像(仅首次)
#==============================================================
build_runtime_image() {
    step "5/6 构建后端运行时基础镜像(仅首次部署需要)"

    if docker images | grep -q "canteen-backend-runtime"; then
        info "运行时镜像已存在,跳过(更新后端代码无需重建)"
    else
        info "构建运行时镜像..."
        docker compose build backend
        info "运行时镜像构建完成(后续更新后端只需替换 jar + 重启)"
    fi
}

#==============================================================
# Step 6: 启动服务
#==============================================================
start_services() {
    step "6/6 启动服务"

    # 创建持久化目录
    mkdir -p backup uploads logs

    info "启动 Docker Compose..."
    docker compose up -d

    info "等待后端健康检查通过..."
    local max_wait=120
    local waited=0
    while [[ $waited -lt $max_wait ]]; do
        if docker compose ps backend | grep -q "healthy"; then
            info "后端已健康!"
            break
        fi
        sleep 5
        waited=$((waited + 5))
        echo -n "."
    done
    echo ""

    if [[ $waited -ge $max_wait ]]; then
        warn "后端启动较慢,请稍后用 docker compose ps 检查状态"
    fi
}

#==============================================================
# 验证部署
#==============================================================
verify_deployment() {
    step "部署验证"

    echo ""
    docker compose ps
    echo ""

    info "健康检查..."
    if curl -sf http://localhost:8080/api/system/health &> /dev/null; then
        info "后端 API: 正常"
    else
        warn "后端 API: 未就绪(可能仍在启动)"
    fi

    for port in 80 81 82; do
        if curl -sf -o /dev/null http://localhost:$port &> /dev/null; then
            info "端口 $port: 正常"
        else
            warn "端口 $port: 未就绪"
        fi
    done
}

#==============================================================
# 部署完成提示
#==============================================================
show_summary() {
    step "部署完成"

    echo ""
    echo "服务访问地址:"
    echo "  管理后台:   http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo '服务器IP')"
    echo "  H5订餐端:   http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo '服务器IP'):81"
    echo "  X86终端:    http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo '服务器IP'):82"
    echo "  后端API:    http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo '服务器IP'):8080"
    echo ""
    echo "默认管理员账号: admin / 123456"
    echo ""
    echo "常用命令:"
    echo "  查看状态:   docker compose ps"
    echo "  查看日志:   docker compose logs -f backend"
    echo "  停止服务:   docker compose down"
    echo "  更新后端:   ./scripts/build.sh backend && docker compose restart backend"
    echo "  更新前端:   ./scripts/build.sh admin-web && docker compose restart admin-web"
    echo "  数据备份:   ./scripts/backup.sh"
    echo ""
    warn "安全提醒: 请立即登录管理后台修改默认密码!"
}

#==============================================================
# 主流程
#==============================================================
main() {
    echo ""
    echo "=========================================="
    echo "  企业智慧食堂系统 - 一键部署"
    echo "=========================================="
    echo ""

    # 需要 root 权限安装 Docker(如果未安装)
    if [[ $EUID -ne 0 ]] && [[ "$SKIP_ENV" == "false" ]]; then
        if ! command -v docker &> /dev/null; then
            error "安装 Docker 需要 root 权限,请使用 sudo 运行:"
            echo "  sudo ./deploy.sh"
            exit 1
        fi
    fi

    install_docker
    configure_docker_mirror
    configure_env
    build_artifacts
    build_runtime_image
    start_services
    verify_deployment
    show_summary
}

main "$@"
