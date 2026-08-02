#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 一键部署 CLI(生产环境)
#==============================================================
# 用法:
#   ./deploy.sh                # 交互式部署向导(首次部署推荐)
#   ./deploy.sh deploy         # 同上
#   ./deploy.sh --skip-env     # 跳过 Docker 安装(已装好时)
#   ./deploy.sh status         # 查看服务状态
#   ./deploy.sh logs [服务]    # 查看日志(服务: backend/admin-web/h5/mysql/redis)
#   ./deploy.sh stop           # 停止所有服务
#   ./deploy.sh restart [服务] # 重启服务(默认全部)
#   ./deploy.sh reset-admin    # 重置超管账号密码(交互式)
#
# 适用系统: CentOS 7+/8/9, Ubuntu 18.04+/20.04/22.04/24.04, Debian 10+
#==============================================================

set -e

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
step()  { echo -e "\n${BLUE}========== $1 ==========${NC}"; }
ask()   { echo -e "${CYAN}[?]${NC} $1"; }

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

#==============================================================
# 修正项目目录所有权
#==============================================================
# 问题场景:用户用 sudo git clone 或 sudo ./deploy.sh 部署后,
# 项目目录归 root 所有,普通用户(canteen)执行 git pull 会报
# "detected dubious ownership" 错误。
# 解决:sudo 运行时自动把项目目录所有权交给实际调用者(SUDO_USER),
# root 仍有权限读写普通用户文件,不影响后续 sudo 部署。
fix_ownership() {
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        local current_owner
        current_owner=$(stat -c '%U' "$PROJECT_DIR" 2>/dev/null || echo "")
        if [[ -n "$current_owner" ]] && [[ "$current_owner" != "$SUDO_USER" ]]; then
            info "修正项目目录所有权: ${current_owner} -> ${SUDO_USER}"
            chown -R "$SUDO_USER:$SUDO_USER" "$PROJECT_DIR" 2>/dev/null || true
        fi
    fi
}

#==============================================================
# 修正脚本可执行权限
#==============================================================
# 问题场景:git checkout / git pull 从 Windows 仓库拉取后,
# 脚本文件可能丢失可执行位(Linux 需要 +x 才能直接 ./xxx.sh 运行)。
# 解决:启动时自动给所有 .sh 脚本补上 +x 权限。
fix_permissions() {
    local need_fix=false
    for f in "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh; do
        [[ -f "$f" ]] || continue
        if [[ ! -x "$f" ]]; then
            need_fix=true
            break
        fi
    done
    if [[ "$need_fix" == "true" ]]; then
        info "修正脚本可执行权限..."
        chmod +x "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh 2>/dev/null || true
    fi
}

#==============================================================
# 工具函数
#==============================================================

# 读取用户输入(带默认值)
# 用法: read_input "提示信息" "默认值" -> 输出到 stdout
read_input() {
    local prompt="$1"
    local default="$2"
    local input
    if [[ -n "$default" ]]; then
        read -p "$(echo -e "${CYAN}[?]${NC} ${prompt} [${default}]: ")" input
        echo "${input:-$default}"
    else
        read -p "$(echo -e "${CYAN}[?]${NC} ${prompt}: ")" input
        echo "$input"
    fi
}

# 读取密码(隐藏输入,带确认)
# 用法: read_password "提示信息" -> 输出到 stdout
read_password() {
    local prompt="$1"
    local pwd1 pwd2
    while true; do
        read -s -p "$(echo -e "${CYAN}[?]${NC} ${prompt}: ")" pwd1
        echo ""
        read -s -p "$(echo -e "${CYAN}[?]${NC} 确认密码: ")" pwd2
        echo ""
        if [[ "$pwd1" != "$pwd2" ]]; then
            warn "两次输入不一致,请重新输入"
            continue
        fi
        if [[ ${#pwd1} -lt 8 ]]; then
            warn "密码至少 8 位,请重新输入"
            continue
        fi
        echo "$pwd1"
        return
    done
}

# 生成随机十六进制字符串
rand_hex() {
    openssl rand -hex "$1" 2>/dev/null || echo "fallback-$(date +%s)-$1"
}

# 获取服务器 IP
get_server_ip() {
    hostname -I 2>/dev/null | awk '{print $1}' || echo "服务器IP"
}

#==============================================================
# 子命令: status
#==============================================================
cmd_status() {
    step "服务状态"
    docker compose ps
    echo ""
    info "健康检查..."
    if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
        info "后端 API: 正常"
    else
        warn "后端 API: 未就绪"
    fi
    for svc in "admin-web:18080" "h5:18081"; do
        name="${svc%%:*}"
        port="${svc##*:}"
        code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/" 2>/dev/null || echo "000")
        if [[ "$code" == "200" ]]; then
            info "${name} (port ${port}): 正常"
        else
            warn "${name} (port ${port}): ${code}"
        fi
    done
}

#==============================================================
# 子命令: logs
#==============================================================
cmd_logs() {
    local service="${1:-}"
    if [[ -z "$service" ]]; then
        docker compose logs -f --tail=100
    else
        docker compose logs -f --tail=100 "$service"
    fi
}

#==============================================================
# 子命令: stop
#==============================================================
cmd_stop() {
    step "停止服务"
    docker compose down
    info "所有服务已停止"
}

#==============================================================
# 子命令: restart
#==============================================================
cmd_restart() {
    local service="${1:-}"
    step "重启服务"
    if [[ -z "$service" ]]; then
        docker compose restart
        info "所有服务已重启"
    else
        docker compose restart "$service"
        info "${service} 已重启"
    fi
}

#==============================================================
# 子命令: reset-admin
#==============================================================
cmd_reset_admin() {
    step "重置超级管理员账号密码"
    echo "此操作将设置超管账号密码,需要重启后端服务生效。"
    echo "  - 若账号已存在且为超管:更新密码"
    echo "  - 若账号不存在:创建新超管(仅在 admin 表为空或只有默认 admin 时)"
    echo ""

    local username password
    username=$(read_input "超管账号名" "admin")
    password=$(read_password "输入新密码(至少 8 位)")

    # 写入 .env 并设置 force 标志(AdminInitializer 读取这些变量)
    set_env_var "INIT_ADMIN_USERNAME" "$username"
    set_env_var "INIT_ADMIN_PASSWORD" "$password"
    set_env_var "INIT_ADMIN_FORCE" "true"

    info "正在重启后端服务以应用新账号..."
    docker compose restart backend

    info "等待后端启动..."
    for i in $(seq 1 30); do
        if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
            info "后端已启动"
            echo ""
            echo "  超管账号: ${username}"
            echo "  请使用新密码登录管理后台"
            echo ""
            warn "登录成功后请删除 .env 中的 INIT_ADMIN_FORCE 和 INIT_ADMIN_PASSWORD(避免明文存储)"
            return
        fi
        sleep 2
        printf "."
    done
    echo ""
    error "后端启动超时,请查看日志: docker compose logs backend"
}

#==============================================================
# 设置 .env 变量(若不存在则追加,存在则更新)
# 用 awk + ENVIRON 传递值,彻底避免 sed 对 | & / \ 等特殊字符的转义问题
#==============================================================
set_env_var() {
    local key="$1"
    local value="$2"
    local envfile="$PROJECT_DIR/.env"

    # 确保 .env 存在
    touch "$envfile"

    if grep -q "^${key}=" "$envfile" 2>/dev/null; then
        # 更新已有行:匹配以 key= 开头的行,整行替换为 key=value
        local tmp
        tmp=$(mktemp)
        KEY="$key" VALUE="$value" awk '
            BEGIN { k = ENVIRON["KEY"]; v = ENVIRON["VALUE"] }
            index($0, k "=") == 1 { print k "=" v; next }
            { print }
        ' "$envfile" > "$tmp" && mv "$tmp" "$envfile"
    else
        # 追加新行
        echo "${key}=${value}" >> "$envfile"
    fi
}

#==============================================================
# 部署步骤函数
#==============================================================

install_docker() {
    step "1/9 检测 Docker 环境"

    if command -v docker &> /dev/null && docker info &> /dev/null; then
        info "Docker 已安装且运行中,跳过安装"
        return 0
    fi

    info "Docker 未安装,开始安装(使用国内源)..."

    if command -v apt-get &> /dev/null; then
        info "检测到 Debian/Ubuntu,使用阿里云镜像源安装..."
        apt-get update -y
        apt-get install -y ca-certificates curl gnupg lsb-release
        install -m 0755 -d /etc/apt/keyrings
        curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        chmod a+r /etc/apt/keyrings/docker.gpg
        echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list
        apt-get update -y
        apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    elif command -v yum &> /dev/null; then
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

configure_docker_mirror() {
    step "2/9 配置 Docker 镜像加速器"

    local daemon_json="/etc/docker/daemon.json"

    # 已存在配置的情况
    if [[ -f "$daemon_json" ]] && grep -q "registry-mirrors" "$daemon_json"; then
        info "检测到已有镜像加速器配置:"
        grep -A5 "registry-mirrors" "$daemon_json" | sed 's/^/    /'
        echo ""
        ask "是否替换为推荐配置? [y/N]"
        read -r ans
        if [[ "$ans" != "y" && "$ans" != "Y" ]]; then
            info "保留现有配置,跳过"
            return
        fi
    fi

    info "写入国内 Docker 镜像加速器..."
    mkdir -p /etc/docker
    # 备份现有配置(若有)
    if [[ -f "$daemon_json" ]] && [[ ! -f "${daemon_json}.bak" ]]; then
        cp "$daemon_json" "${daemon_json}.bak"
        info "已备份原配置到 ${daemon_json}.bak"
    fi

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
}

configure_env() {
    step "3/9 配置环境变量"

    if [[ -f .env ]] && ! confirm_overwrite_env; then
        info "保留现有 .env 文件"
        return
    fi

    info "即将生成 .env 配置文件,请按提示输入:"
    echo ""

    # MySQL 密码
    local mysql_pwd
    ask "MySQL 数据库密码(留空=自动生成随机密码)"
    read -r mysql_pwd
    if [[ -z "$mysql_pwd" ]]; then
        mysql_pwd=$(rand_hex 16)
        info "已生成随机 MySQL 密码"
    elif [[ ${#mysql_pwd} -lt 8 ]]; then
        warn "密码不足 8 位,改为自动生成"
        mysql_pwd=$(rand_hex 16)
    fi

    # JWT 密钥(自动生成)
    local jwt_secret
    jwt_secret=$(rand_hex 32)

    # 超管账号
    echo ""
    info "配置超级管理员账号"
    local admin_user admin_pwd
    admin_user=$(read_input "超管登录账号" "admin")
    admin_pwd=$(read_password "超管登录密码(至少 8 位)")

    # 写入 .env
    cat > .env <<EOF
# MySQL 数据库密码
MYSQL_ROOT_PASSWORD=${mysql_pwd}

# JWT 密钥(自动生成)
JWT_SECRET=${jwt_secret}

# Token 过期时间(毫秒)
JWT_EXPIRATION=86400000
JWT_EMPLOYEE_EXPIRATION=2592000000
JWT_TERMINAL_EXPIRATION=31536000000

# 初始超管账号(后端首次启动时读取,初始化后可删除)
INIT_ADMIN_USERNAME=${admin_user}
INIT_ADMIN_PASSWORD=${admin_pwd}
EOF

    echo ""
    info ".env 已生成"
    warn "请妥善保管 .env 文件,包含敏感信息!"
}

# 确认是否覆盖已有 .env
confirm_overwrite_env() {
    warn "已存在 .env 文件"
    ask "是否重新配置?(y/n)"
    read -r ans
    [[ "$ans" == "y" || "$ans" == "Y" ]]
}

build_artifacts() {
    step "4/9 构建业务产物(在 Docker 容器中,无需宿主机 JDK/Node)"

    info "拉取构建镜像(首次较慢,使用国内源加速)..."
    docker pull maven:3.9-eclipse-temurin-25
    docker pull node:20-alpine

    info "开始构建..."
    chmod +x scripts/build.sh
    ./scripts/build.sh all
}

build_runtime_image() {
    step "5/9 构建后端运行时基础镜像(仅首次需要)"

    if docker images | grep -q "canteen-backend-runtime"; then
        info "运行时镜像已存在,跳过(更新后端代码无需重建)"
    else
        info "构建运行时镜像..."
        docker compose build backend
        info "运行时镜像构建完成"
    fi
}

start_services() {
    step "6/9 启动服务"

    # 端口占用预检测(避免启动到一半才报错)
    local required_ports=("18080" "18081" "18082")
    local port_busy=false
    for p in "${required_ports[@]}"; do
        if ss -tlnp 2>/dev/null | grep -q ":${p} " || netstat -tlnp 2>/dev/null | grep -q ":${p} "; then
            local proc_info
            proc_info=$(ss -tlnp 2>/dev/null | grep ":${p} " | head -1 || netstat -tlnp 2>/dev/null | grep ":${p} " | head -1)
            error "端口 ${p} 已被占用: ${proc_info}"
            port_busy=true
        fi
    done
    if [[ "$port_busy" == "true" ]]; then
        echo ""
        error "端口被占用,无法启动服务。请释放上述端口后重试。"
        echo ""
        info "排查建议:"
        echo "  1) 查看占用进程: sudo ss -tlnp | grep -E '18080|18081|18082'"
        echo "  2) 停止旧服务:   sudo ./deploy.sh stop"
        echo "  3) 如是系统 nginx/apache: sudo systemctl stop nginx && sudo systemctl disable nginx"
        exit 1
    fi
    info "端口 18080/18081/18082 可用"

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
        printf "."
    done
    echo ""

    if [[ $waited -ge $max_wait ]]; then
        warn "后端启动较慢,请稍后用 ./deploy.sh status 检查状态"
    fi
}

#==============================================================
# 配置开机启动(systemd service)
#==============================================================
setup_autostart() {
    step "7/9 配置开机启动"

    # Docker 服务开机启动
    info "启用 Docker 服务开机启动..."
    systemctl enable docker 2>/dev/null && info "Docker 开机启动已启用" || warn "Docker 开机启动设置失败(可能已启用)"

    # 创建应用 systemd service
    local service_file="/etc/systemd/system/canteen.service"
    local docker_bin
    docker_bin=$(which docker 2>/dev/null || echo "/usr/bin/docker")

    info "创建系统服务 canteen.service..."
    cat > "$service_file" <<EOF
[Unit]
Description=Enterprise Canteen System (Docker Compose)
Documentation=https://github.com/MingTu01/canteen
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=${PROJECT_DIR}
ExecStart=${docker_bin} compose up -d
ExecStop=${docker_bin} compose down
TimeoutStartSec=300
# 健康检查失败时不影响系统启动
IgnoreSIGPIPE=no

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable canteen 2>/dev/null && info "canteen 服务开机启动已启用" || warn "canteen 开机启动设置失败"

    echo ""
    info "开机启动管理:"
    echo "    启用:  sudo systemctl enable canteen"
    echo "    禁用:  sudo systemctl disable canteen"
    echo "    状态:  systemctl status canteen"
}

#==============================================================
# 安装 canteen 管理命令到系统 PATH
#==============================================================
install_canteen_command() {
    step "8/9 安装 canteen 管理命令"

    local target="/usr/local/bin/canteen"

    # 确保脚本可执行
    chmod +x "$PROJECT_DIR/canteen.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/scripts/"*.sh 2>/dev/null || true

    if [[ -L "$target" ]] || [[ -f "$target" ]]; then
        info "canteen 命令已存在,更新软链接..."
        rm -f "$target"
    fi

    ln -sf "$PROJECT_DIR/canteen.sh" "$target"
    info "已安装 canteen 命令 -> $target"
    echo ""
    info "现在可以在任意目录输入 canteen 打开管理面板"
}

verify_and_summary() {
    step "9/9 部署验证"

    echo ""
    docker compose ps
    echo ""

    info "健康检查..."
    if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
        info "后端 API: 正常"
    else
        warn "后端 API: 未就绪(可能仍在启动)"
    fi

    for svc in "admin-web:18080" "h5:18081"; do
        name="${svc%%:*}"
        port="${svc##*:}"
        if curl -sf -o /dev/null "http://localhost:${port}" >/dev/null 2>&1; then
            info "${name} (port ${port}): 正常"
        else
            warn "${name} (port ${port}): 未就绪"
        fi
    done

    local ip
    ip=$(get_server_ip)

    echo ""
    echo "=========================================="
    echo "  部署完成!"
    echo "=========================================="
    echo ""
    echo "  服务访问地址:"
    echo "    管理后台:   http://${ip}:18080"
    echo "    H5 订餐端:  http://${ip}:18081"
    echo "    后端 API:   http://${ip}:18082"
    echo ""
    echo "  X86 终端:请使用安装包安装后,在终端设置中填入后端地址"
    echo "            安装包位于 src-python/output/ 目录"
    echo ""
    if [[ -f .env ]]; then
        local admin_user
        admin_user=$(grep "^INIT_ADMIN_USERNAME=" .env 2>/dev/null | cut -d= -f2)
        if [[ -n "$admin_user" ]]; then
            echo "  超管账号: ${admin_user} (密码为你刚才设置的)"
        else
            echo "  默认账号: admin / 123456 (请尽快修改!)"
        fi
    else
        echo "  默认账号: admin / 123456 (请尽快修改!)"
    fi
    echo ""
    echo "  常用命令(任意目录直接输入):"
    echo "    canteen              # 打开管理面板(菜单式操作)"
    echo "    canteen status       # 查看服务状态"
    echo "    canteen upgrade      # 安全升级(含备份+自动回退)"
    echo "    canteen backup       # 手动备份"
    echo "    canteen logs backend # 查看后端日志"
    echo ""
    echo "  开机启动: 已配置(systemd: canteen.service + docker.service)"
    echo "    状态: systemctl status canteen"
    echo "    禁用: sudo systemctl disable canteen"
    echo ""
    warn "安全提醒:请登录管理后台确认超管账号已正确初始化"
}

#==============================================================
# 子命令: deploy
#==============================================================
cmd_deploy() {
    echo ""
    echo "=========================================="
    echo "  企业智慧食堂系统 - 部署向导"
    echo "=========================================="
    echo ""

    # root 权限检查
    if [[ $EUID -ne 0 ]] && [[ "$SKIP_ENV" == "false" ]]; then
        if ! command -v docker &> /dev/null; then
            error "安装 Docker 需要 root 权限,请使用 sudo 运行:"
            echo "  sudo ./deploy.sh"
            exit 1
        fi
    fi

    # 部署开始前:修正项目目录所有权(避免 root 所有导致后续 git pull 失败)
    fix_ownership
    # 修正脚本可执行权限(避免 git pull 后 .sh 丢失 +x)
    fix_permissions

    install_docker
    configure_docker_mirror
    configure_env
    build_artifacts
    build_runtime_image
    start_services
    setup_autostart
    install_canteen_command

    # 部署结束前:再次修正所有权和权限(确保新建目录和脚本可用)
    fix_ownership
    fix_permissions

    verify_and_summary
}

#==============================================================
# 主入口
#==============================================================
SKIP_ENV=false
if [[ "$1" == "--skip-env" ]]; then
    SKIP_ENV=true
    shift
fi

COMMAND="${1:-deploy}"
case "$COMMAND" in
    deploy)
        cmd_deploy
        ;;
    status|ps)
        cmd_status
        ;;
    logs)
        shift
        cmd_logs "$@"
        ;;
    stop|down)
        cmd_stop
        ;;
    restart)
        shift
        cmd_restart "$@"
        ;;
    reset-admin)
        cmd_reset_admin
        ;;
    help|-h|--help)
        echo "用法: ./deploy.sh [命令]"
        echo ""
        echo "命令:"
        echo "  deploy          交互式部署向导(默认)"
        echo "  status          查看服务状态"
        echo "  logs [服务]     查看日志(backend/admin-web/h5/mysql/redis)"
        echo "  stop            停止所有服务"
        echo "  restart [服务]  重启服务(默认全部)"
        echo "  reset-admin     重置超管账号密码"
        echo "  help            显示帮助"
        echo ""
        echo "选项:"
        echo "  --skip-env      跳过 Docker 安装(已装好时)"
        ;;
    *)
        error "未知命令: $COMMAND"
        echo "运行 ./deploy.sh help 查看可用命令"
        exit 1
        ;;
esac
