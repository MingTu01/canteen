#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 一键安装脚本(install.sh)
#==============================================================
# 用法(在任意全新服务器上执行一行命令即可完成部署):
#
#   curl -fsSL https://raw.githubusercontent.com/MingTu01/canteen/deploy/install.sh -o /tmp/canteen-install.sh && sudo bash /tmp/canteen-install.sh
#
# 或指定安装目录:
#
#   sudo bash /tmp/canteen-install.sh /opt/my-canteen
#
# 本脚本完成:
#   1. 检查 root 权限和基础依赖
#   2. 安装 git(如缺失)
#   3. 克隆仓库(deploy 分支,含预构建产物,无需编译)
#   4. 全面修正文件权限和所有权
#   5. 调用 deploy.sh 完成部署(Docker安装/环境配置/服务启动/超管创建)
#
# 适用系统: CentOS 7+/8/9, Ubuntu 18.04+/20.04/22.04/24.04, Debian 10+
#==============================================================

set -eo pipefail

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[安装]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }
step()  { echo -e "\n${BLUE}========== $1 ==========${NC}"; }

#==============================================================
# 如果 stdin 不是终端(curl | bash 管道模式),重定向到 /dev/tty
# 确保 deploy.sh 的交互式输入(密码等)能正常工作
#==============================================================
if [[ ! -t 0 ]] && [[ -t 1 ]]; then
    exec 0</dev/tty 2>/dev/null || true
fi

#==============================================================
# 配置
#==============================================================
REPO_URL="https://github.com/MingTu01/canteen.git"
BRANCH="deploy"
DEFAULT_INSTALL_DIR="/opt/canteen"
INSTALL_DIR="${1:-$DEFAULT_INSTALL_DIR}"

#==============================================================
# 1. root 权限检查
#==============================================================
if [[ $EUID -ne 0 ]]; then
    error "请使用 root 或 sudo 运行本脚本"
    echo ""
    echo "  sudo bash install.sh"
    echo "  sudo bash install.sh /opt/my-canteen   # 自定义安装目录"
    exit 1
fi

# 获取实际调用用户(sudo 运行时为 SUDO_USER,否则为当前用户)
REAL_USER="${SUDO_USER:-$(whoami)}"
if [[ "$REAL_USER" == "root" ]]; then
    # root 直接运行(非 sudo):创建专用用户 canteen
    if ! id "canteen" &>/dev/null; then
        info "创建专用运行用户 canteen..."
        useradd -r -m -d /home/canteen -s /bin/bash canteen 2>/dev/null || true
    fi
    REAL_USER="canteen"
fi
REAL_UID=$(id -u "$REAL_USER" 2>/dev/null || echo 1000)
REAL_GID=$(id -g "$REAL_USER" 2>/dev/null || echo 1000)

info "实际运行用户: ${REAL_USER} (UID=${REAL_UID}, GID=${REAL_GID})"
info "安装目录: ${INSTALL_DIR}"

#==============================================================
# 2. 基础依赖检查
#==============================================================
step "1/4 检查基础依赖"

for cmd in curl tar gzip; do
    if ! command -v "$cmd" &>/dev/null; then
        error "缺少必要命令: $cmd"
        echo "  安装: apt-get install -y $cmd  或  yum install -y $cmd"
        exit 1
    fi
done
info "基础依赖检查通过"

# 磁盘空间检查(至少 2GB)
avail_kb=$(df -P "$(dirname "$INSTALL_DIR")" | awk 'NR==2{print $4}')
avail_gb=$((avail_kb / 1024 / 1024))
if [ "$avail_gb" -lt 2 ]; then
    error "磁盘空间不足: 剩余 ${avail_gb}GB, 需要至少 2GB"
    exit 1
fi
info "磁盘空间: 剩余 ${avail_gb}GB"

#==============================================================
# 3. 安装 git(如缺失)
#==============================================================
step "2/4 检查 git"

if ! command -v git &>/dev/null; then
    info "git 未安装,开始安装..."
    if command -v apt-get &>/dev/null; then
        apt-get update -y && apt-get install -y git
    elif command -v yum &>/dev/null; then
        yum install -y git
    else
        error "无法自动安装 git,请手动安装后重试"
        exit 1
    fi
fi
info "git 已就绪: $(git --version)"

#==============================================================
# 4. 克隆/更新仓库
#==============================================================
step "3/4 克隆仓库(deploy 分支,含预构建产物)"

if [[ -d "$INSTALL_DIR/.git" ]]; then
    info "目录已存在,更新代码..."
    cd "$INSTALL_DIR"
    # 修正 git safe.directory(避免 dubious ownership 错误)
    sudo -u "$REAL_USER" git config --global --add safe.directory "$INSTALL_DIR" 2>/dev/null || true
    sudo -u "$REAL_USER" git fetch origin "$BRANCH" 2>/dev/null || git fetch origin 2>/dev/null || true
    sudo -u "$REAL_USER" git checkout "$BRANCH" 2>/dev/null || true
    sudo -u "$REAL_USER" git pull origin "$BRANCH" 2>/dev/null || git pull origin 2>/dev/null || true
else
    info "克隆仓库到 ${INSTALL_DIR} ..."
    # 先以 root 创建安装目录(父目录如 /opt 通常归 root 所有,普通用户无权创建)
    mkdir -p "$INSTALL_DIR"
    # 将安装目录所有权交给实际用户,再以其身份克隆(避免文件归 root 所有)
    if [[ "$REAL_USER" != "root" ]]; then
        chown -R "$REAL_USER:$REAL_USER" "$INSTALL_DIR"
        sudo -u "$REAL_USER" git clone --branch "$BRANCH" --single-branch "$REPO_URL" "$INSTALL_DIR"
    else
        git clone --branch "$BRANCH" --single-branch "$REPO_URL" "$INSTALL_DIR"
    fi
    cd "$INSTALL_DIR"
fi

# 确认产物存在
if [[ ! -f "deploy/backend/app.jar" ]]; then
    warn "deploy 分支未检测到预构建产物"
    warn "可能原因: CI 尚未构建,或网络问题导致 clone 不完整"
    echo ""
    ask_use_main() {
        echo "  选项:"
        echo "    1) 等待 CI 构建后重新运行(推荐)"
        echo "    2) 切换到 main 分支(需要在服务器上构建,耗时较长)"
        echo "    3) 退出"
        read -p "$(echo -e "${CYAN}[?]${NC} 请选择 [1-3]: ")" choice
        case "$choice" in
            2)
                info "切换到 main 分支..."
                sudo -u "$REAL_USER" git fetch origin main 2>/dev/null || true
                sudo -u "$REAL_USER" git checkout main 2>/dev/null || true
                sudo -u "$REAL_USER" git pull origin main 2>/dev/null || true
                ;;
            3)
                exit 1
                ;;
            *)
                info "请等待 CI 构建完成后重新运行本脚本"
                exit 0
                ;;
        esac
    }
    ask_use_main
fi

info "代码就绪"

#==============================================================
# 5. 全面修正权限
#==============================================================
step "4/4 修正文件权限"

# 5.1 项目目录所有权交给实际用户
info "设置项目目录所有权: ${REAL_USER}:${REAL_USER}"
chown -R "$REAL_USER:$REAL_USER" "$INSTALL_DIR"

# 5.2 脚本可执行权限
info "设置脚本可执行权限..."
chmod +x "$INSTALL_DIR"/*.sh 2>/dev/null || true
chmod +x "$INSTALL_DIR"/scripts/*.sh 2>/dev/null || true

# 5.3 git safe.directory(避免 dubious ownership)
info "配置 git safe.directory..."
sudo -u "$REAL_USER" git config --global --add safe.directory "$INSTALL_DIR" 2>/dev/null || true

# 5.4 创建运行时目录并设置权限
for d in backup uploads logs; do
    mkdir -p "$INSTALL_DIR/$d"
    chown "$REAL_USER:$REAL_USER" "$INSTALL_DIR/$d"
done

# 5.5 .env 权限(如已存在)
if [[ -f "$INSTALL_DIR/.env" ]]; then
    chown "$REAL_USER:$REAL_USER" "$INSTALL_DIR/.env"
    chmod 600 "$INSTALL_DIR/.env"
fi

info "权限修正完成"

#==============================================================
# 6. 启动部署向导
#==============================================================
echo ""
echo "=========================================="
echo "  仓库已就绪,即将启动部署向导"
echo "=========================================="
echo ""
echo "  部署向导将引导你完成:"
echo "    - Docker 安装(如未安装)"
echo "    - 环境变量配置(自动生成随机密码)"
echo "    - 超级管理员账号密码设置"
echo "    - 服务启动和验证"
echo ""

# 以实际用户身份运行 deploy.sh(通过 sudo -u 切换)
# 保留环境变量(PUID/PGID 等),保留 stdin/stdout/stderr
if [[ "$REAL_USER" != "root" ]] && [[ "$REAL_USER" != "$(whoami)" ]]; then
    # 检查用户是否在 docker 组(如果 docker 已安装)
    if command -v docker &>/dev/null; then
        if ! id -nG "$REAL_USER" 2>/dev/null | grep -qw "docker"; then
            info "将用户 ${REAL_USER} 加入 docker 组..."
            usermod -aG docker "$REAL_USER" 2>/dev/null && \
                warn "已加入 docker 组,需重新登录后生效" || true
        fi
    fi

    info "以用户 ${REAL_USER} 身份运行 deploy.sh..."
    # 用 sudo -u 保留 HOME 和 PATH,同时传入必要的环境变量
    exec sudo -E -u "$REAL_USER" \
        PUID="$REAL_UID" PGID="$REAL_GID" \
        SUDO_USER="$SUDO_USER" \
        bash "$INSTALL_DIR/deploy.sh" --from-install
else
    exec bash "$INSTALL_DIR/deploy.sh" --from-install
fi
