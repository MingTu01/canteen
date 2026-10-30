#!/bin/bash
# canteen-install-version: 2   # 一键命令以此校验 CDN 返回的是最新脚本,改版时递增
#==============================================================
# 企业智慧食堂系统 - 一键安装脚本(install.sh)
#==============================================================
# 用法(在任意全新服务器上执行,多通道自动切换):
#
#   for u in "https://fastly.jsdelivr.net/gh/MingTu01/canteen@main/install.sh" "https://cdn.jsdelivr.net/gh/MingTu01/canteen@main/install.sh" "https://testingcf.jsdelivr.net/gh/MingTu01/canteen@main/install.sh" "https://gh-proxy.com/https://raw.githubusercontent.com/MingTu01/canteen/main/install.sh" "https://raw.githubusercontent.com/MingTu01/canteen/main/install.sh"; do curl -fsSL --connect-timeout 8 --max-time 60 "$u" -o /tmp/canteen-install.sh && grep -q "canteen-install-version: 2" /tmp/canteen-install.sh && break; done && sudo bash /tmp/canteen-install.sh || echo "下载失败或 CDN 缓存了旧版脚本,请重试;仍失败可手动执行: sudo bash /tmp/canteen-install.sh"
#
# 说明:jsdelivr CDN 为主通道(真 CDN 不挑客户端 IP,国内云服务器稳定可达);
# GitHub 代理节点(gh-proxy.com 等)对数据中心 IP(腾讯云/阿里云等)常返回
# 403/404 防滥用拦截,仅作兜底;直连最后兜底。
# 注意:jsdelivr 对 @main 有 12 小时左右的缓存,刚推送的脚本可能仍返回旧版,
# 所以下载后必须校验版本标记,旧版会继续尝试下一个通道。
#
# 或指定安装目录 / 覆盖已有目录:
#
#   sudo bash /tmp/canteen-install.sh /opt/my-canteen
#   sudo bash /tmp/canteen-install.sh /opt/my-canteen --force   # 已有非 git 目录先移到 .bak 再重新克隆
#
# 本脚本完成:
#   1. 检查 root 权限、交互式终端、基础依赖、磁盘空间
#   2. 安装 git(如缺失)
#   3. 克隆/更新 deploy 分支(含预构建产物,无需编译)
#   4. 修正文件权限和所有权(在 git 操作之前,避免 root 属主导致 pull 失败)
#   5. 调用 deploy.sh 完成部署(Docker安装/环境配置/服务启动/超管创建)
#
# 适用系统: CentOS 7+/8/9, Ubuntu 18.04+/20.04/22.04/24.04, Debian 10+
#==============================================================

set -euo pipefail

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { printf '%b\n' "${GREEN}[安装]${NC} $*"; }
warn()  { printf '%b\n' "${YELLOW}[警告]${NC} $*"; }
error() { printf '%b\n' "${RED}[错误]${NC} $*"; }
step()  { printf '\n%b\n' "${BLUE}========== $* ==========${NC}"; }

#==============================================================
# 参数解析
#==============================================================
DEFAULT_INSTALL_DIR="/opt/canteen"
INSTALL_DIR="$DEFAULT_INSTALL_DIR"
FORCE=0

usage() {
    echo "用法: sudo bash install.sh [安装目录] [--force]"
    echo ""
    echo "  安装目录   绝对路径,默认 ${DEFAULT_INSTALL_DIR}"
    echo "  --force    安装目录已存在且不是 git 仓库时,先移动到 .bak.<时间戳> 再重新克隆"
}

for arg in "$@"; do
    case "$arg" in
        -f|--force) FORCE=1 ;;
        -h|--help) usage; exit 0 ;;
        /*) INSTALL_DIR="$arg" ;;
        *)
            error "未知参数: $arg"
            usage
            exit 1
            ;;
    esac
done

if [[ -z "$INSTALL_DIR" || "$INSTALL_DIR" == "/" ]]; then
    error "非法安装目录: '${INSTALL_DIR}'"
    exit 1
fi

# git 通道超时(秒)。没有超时保护时,失效的加速节点会让安装卡死很久。
GIT_CLONE_TIMEOUT="${GIT_CLONE_TIMEOUT:-480}"
GIT_FETCH_TIMEOUT="${GIT_FETCH_TIMEOUT:-180}"

#==============================================================
# 交互式终端检查(部署向导需要输入超管密码)
#   curl | bash 管道模式下 stdin 不是终端,重定向到 /dev/tty;
#   非交互环境(CI/无 TTY)提前明确失败,而不是走到一半静默退出。
#==============================================================
if [[ ! -t 0 ]] && [[ -t 1 ]]; then
    exec 0</dev/tty 2>/dev/null || true
fi
if [[ ! -t 0 ]]; then
    error "未检测到交互式终端,首次部署需要输入超管账号密码"
    echo ""
    echo "  请在 SSH 会话内直接执行:"
    echo "    sudo bash $0 ${INSTALL_DIR}"
    exit 1
fi

#==============================================================
# 1. root 权限 + 实际用户
#==============================================================
if [[ $EUID -ne 0 ]]; then
    error "请使用 root 或 sudo 运行本脚本"
    echo ""
    echo "  sudo bash install.sh"
    echo "  sudo bash install.sh /opt/my-canteen   # 自定义安装目录"
    exit 1
fi

REAL_USER="${SUDO_USER:-$(id -un)}"
if [[ "$REAL_USER" == "root" ]]; then
    # root 直接运行(非 sudo):创建专用用户 canteen
    if ! id "canteen" &>/dev/null; then
        info "创建专用运行用户 canteen..."
        useradd -r -m -d /home/canteen -s /bin/bash canteen || {
            error "创建用户 canteen 失败,请手动创建后重试"
            exit 1
        }
    fi
    REAL_USER="canteen"
fi
if ! id "$REAL_USER" &>/dev/null; then
    error "运行用户 ${REAL_USER} 不存在"
    exit 1
fi
REAL_UID=$(id -u "$REAL_USER")
REAL_GID=$(id -g "$REAL_USER")

info "实际运行用户: ${REAL_USER} (UID=${REAL_UID}, GID=${REAL_GID})"
info "安装目录: ${INSTALL_DIR}"

#==============================================================
# 通用函数
#==============================================================
# 以实际用户身份执行命令(优先 sudo -u;精简系统没有 sudo 时退化到 runuser)
as_user() {
    local user="$1"; shift
    if [[ "$user" == "root" ]]; then
        "$@"
    elif command -v sudo &>/dev/null; then
        sudo -u "$user" -H "$@"
    elif command -v runuser &>/dev/null; then
        runuser -u "$user" -- "$@"
    else
        error "缺少 sudo/runuser,无法以 ${user} 身份执行命令"
        exit 1
    fi
}

git_run() {
    as_user "$REAL_USER" git "$@"
}

# 带超时的 git,并限制低速传输:连续 60s 低于 1KB/s 视为失败,避免死节点挂住。
# 注意:timeout 必须作为可执行程序位于 sudo 之后,不能把 shell 函数交给 timeout。
git_timed() {
    local t="$1"; shift
    if command -v timeout &>/dev/null; then
        as_user "$REAL_USER" timeout "$t" git \
            -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 "$@"
    else
        as_user "$REAL_USER" git "$@"
    fi
}

# git 克隆通道(依次尝试,最后一个为直连兜底;国内服务器 GitHub 直连经常超时/重置)
GIT_CHANNELS=(
    "https://gh-proxy.com/https://github.com/"
    "https://ghp.keleyaa.com/https://github.com/"
    "https://g.blfrp.cn/https://github.com/"
    "https://gh.llkk.cc/https://github.com/"
    "https://ghpxy.hwinzniej.top/https://github.com/"
    "https://github.com/"
)
REPO_URL="https://github.com/MingTu01/canteen.git"
BRANCH="deploy"

channel_label() {
    if [[ "$1" == "https://github.com/" ]]; then
        echo "直连"
    else
        echo "$1" | sed 's|^https://||;s|/https://github.com/$||'
    fi
}

# 修正目录所有权(关键:必须在任何 git 操作之前执行,
# 否则 root 属主的 .git 会让 git pull 报 "cannot open '.git/FETCH_HEAD': Permission denied")
ensure_owner() {
    local path="$1" cur
    cur=$(stat -c '%U' "$path" 2>/dev/null || echo "")
    if [[ "$cur" != "$REAL_USER" ]]; then
        info "修正所有权: ${path} (${cur:-未知} -> ${REAL_USER})"
        chown -R "$REAL_USER:$REAL_USER" "$path"
    fi
}

# 注册 git safe.directory(避免 dubious ownership),运维用户与 root 各注册一份
safe_directory() {
    local dir="$1"
    git_run config --global --add safe.directory "$dir" 2>/dev/null || true
    if [[ "$REAL_USER" != "root" ]]; then
        git config --global --add safe.directory "$dir" 2>/dev/null || true
    fi
}

# 找到一个已存在的祖先目录(自定义安装目录的父级可能还不存在,df 会报错)
existing_parent() {
    local p="$1"
    while [[ ! -d "$p" ]]; do
        p=$(dirname "$p")
    done
    printf '%s' "$p"
}

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

# 磁盘空间检查(硬性 3GB,推荐 10GB;deploy 分支含 jar,clone 后约 200MB)
parent_dir=$(existing_parent "$INSTALL_DIR")
avail_kb=$(df -P "$parent_dir" | awk 'NR==2{print $4}')
avail_kb="${avail_kb:-0}"
avail_gb=$((avail_kb / 1024 / 1024))
if (( avail_gb < 3 )); then
    error "磁盘空间不足: 剩余 ${avail_gb}GB, 需要至少 3GB"
    exit 1
fi
if (( avail_gb < 10 )); then
    warn "磁盘剩余 ${avail_gb}GB, 推荐 ≥10GB(Docker 镜像 + 数据卷)"
else
    info "磁盘剩余 ${avail_gb}GB"
fi

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
# 4. 准备仓库(克隆 / 更新)
#==============================================================
step "3/4 准备仓库(deploy 分支,含预构建产物)"

UPDATE_MODE=0
if [[ -d "$INSTALL_DIR/.git" ]]; then
    UPDATE_MODE=1
    ensure_owner "$INSTALL_DIR"
elif [[ -d "$INSTALL_DIR" ]] && [[ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null || true)" ]]; then
    # 非空且不是 git 仓库:可能是 ZIP 部署目录或手工文件,绝不能直接删除
    if [[ $FORCE -eq 1 ]]; then
        backup_dir="${INSTALL_DIR}.bak.$(date +%Y%m%d_%H%M%S)"
        warn "安装目录已存在(非 git),移动到 ${backup_dir}"
        mv "$INSTALL_DIR" "$backup_dir"
    else
        error "安装目录已存在且不是 git 仓库: ${INSTALL_DIR}"
        echo ""
        echo "  为避免覆盖已有数据(ZIP 部署 / 手工文件),已停止。可选处理:"
        echo "    1) 换一个目录:      sudo bash $0 /opt/canteen-new"
        echo "    2) 备份后移走:      sudo mv ${INSTALL_DIR} ${INSTALL_DIR}.bak && sudo bash $0"
        echo "    3) 确认可以覆盖:    sudo bash $0 ${INSTALL_DIR} --force  # 先移到 .bak.<时间戳>"
        exit 1
    fi
fi

if [[ $UPDATE_MODE -eq 1 ]]; then
    info "检测到已有 git 仓库,进入更新模式"
    safe_directory "$INSTALL_DIR"
    cd "$INSTALL_DIR"

    cur_branch=$(git_run rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
    if [[ "$cur_branch" != "$BRANCH" ]]; then
        warn "当前分支: ${cur_branch:-detached},切换到 ${BRANCH}..."
        git_run fetch origin "$BRANCH" 2>/dev/null || true
        git_run checkout -B "$BRANCH" "origin/$BRANCH" 2>/dev/null \
            || git_run checkout "$BRANCH" 2>/dev/null \
            || warn "切换分支失败,将尝试直接更新"
    fi

    update_ok=0
    for ch in "${GIT_CHANNELS[@]}"; do
        info "更新通道: $(channel_label "$ch") (超时 ${GIT_FETCH_TIMEOUT}s)"
        if git_timed "$GIT_FETCH_TIMEOUT" -c "url.${ch}.insteadOf=https://github.com/" fetch origin "$BRANCH"; then
            # 服务器目录不应有本地提交:快进失败时以远端为准
            if git_run merge --ff-only "origin/$BRANCH" 2>/dev/null \
                || git_run reset --hard "origin/$BRANCH"; then
                git_run config "url.${ch}.insteadOf" "https://github.com/" 2>/dev/null || true
                update_ok=1
                break
            fi
        fi
        warn "通道 $(channel_label "$ch") 更新失败,尝试下一个..."
    done

    if [[ $update_ok -ne 1 ]]; then
        error "所有通道(含直连)均无法更新代码"
        echo ""
        echo "  排查建议:"
        echo "    1) 确认目录所有权: ls -ld ${INSTALL_DIR}   # 应属于 ${REAL_USER}"
        echo "    2) 确认能访问外网: curl -fsSL --max-time 10 https://github.com -o /dev/null && echo 网络OK"
        echo "    3) 手工修复后重跑: sudo chown -R ${REAL_USER}:${REAL_USER} ${INSTALL_DIR}"
        exit 1
    fi
else
    info "克隆仓库到 ${INSTALL_DIR} ..."
    clone_ok=0
    for ch in "${GIT_CHANNELS[@]}"; do
        info "克隆通道: $(channel_label "$ch") (超时 ${GIT_CLONE_TIMEOUT}s)"
        # 目录此时为空或只有上次失败残留,可安全清理
        rm -rf "${INSTALL_DIR:?}"
        mkdir -p "$INSTALL_DIR"
        chown "$REAL_USER:$REAL_USER" "$INSTALL_DIR"
        if git_timed "$GIT_CLONE_TIMEOUT" -c "url.${ch}.insteadOf=https://github.com/" \
            clone --branch "$BRANCH" --single-branch "$REPO_URL" "$INSTALL_DIR"; then
            clone_ok=1
            # 记住成功通道,后续 deploy.sh / upgrade.sh 的 git pull 自动走同一通道
            git_run -C "$INSTALL_DIR" config "url.${ch}.insteadOf" "https://github.com/" 2>/dev/null || true
            break
        fi
        warn "通道 $(channel_label "$ch") 克隆失败,换下一个通道重试..."
    done

    if [[ $clone_ok -ne 1 ]]; then
        error "所有克隆通道(含直连)均失败"
        echo ""
        echo "  排查建议:"
        echo "    1) 确认服务器能访问外网:"
        echo "       curl -fsSL --max-time 10 https://github.com -o /dev/null && echo 网络OK"
        echo "    2) 或手动克隆后重跑本脚本(目录必须为空或不存在):"
        echo "       sudo git clone -b deploy https://gh-proxy.com/https://github.com/MingTu01/canteen.git ${INSTALL_DIR}"
        exit 1
    fi
    cd "$INSTALL_DIR"
fi

#==============================================================
# 5. 产物校验 + 权限修正
#==============================================================
step "4/4 校验产物与权限"

missing=0
[[ -s "deploy/backend/app.jar" ]] || { error "缺少 deploy/backend/app.jar"; missing=1; }
[[ -s "deploy/admin-web/html/index.html" ]] || { error "缺少 deploy/admin-web/html/index.html"; missing=1; }
[[ -s "deploy/h5/html/index.html" ]] || { error "缺少 deploy/h5/html/index.html"; missing=1; }
if [[ $missing -eq 1 ]]; then
    echo ""
    echo "  deploy 分支缺少预构建产物,通常是 CI 尚未构建完成或网络中断导致 clone 不完整。"
    echo "  处理: 等待 GitHub Actions 完成构建后重跑本脚本;或执行 sudo bash ${INSTALL_DIR}/install.sh 重新更新。"
    exit 1
fi
jar_mb=$(( $(stat -c %s deploy/backend/app.jar) / 1024 / 1024 ))
info "后端产物: ${jar_mb}MB"
if (( jar_mb < 5 )); then
    warn "app.jar 仅 ${jar_mb}MB,可能不完整,请检查后端日志"
fi

ensure_owner "$INSTALL_DIR"
chmod +x "$INSTALL_DIR"/*.sh 2>/dev/null || true
chmod +x "$INSTALL_DIR"/scripts/*.sh 2>/dev/null || true

safe_directory "$INSTALL_DIR"

for d in backup uploads logs; do
    mkdir -p "$INSTALL_DIR/$d"
    chown -R "$REAL_USER:$REAL_USER" "$INSTALL_DIR/$d"
done

if [[ -f "$INSTALL_DIR/.env" ]]; then
    chown "$REAL_USER:$REAL_USER" "$INSTALL_DIR/.env"
    chmod 600 "$INSTALL_DIR/.env"
fi

info "产物校验与权限修正完成"

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

# 检查用户是否在 docker 组(如果 docker 已安装)
if command -v docker &>/dev/null && [[ "$REAL_USER" != "root" ]]; then
    if ! id -nG "$REAL_USER" 2>/dev/null | tr ' ' '\n' | grep -qx "docker"; then
        info "将用户 ${REAL_USER} 加入 docker 组..."
        usermod -aG docker "$REAL_USER" 2>/dev/null || true
        warn "已加入 docker 组,需重新登录后生效"
    fi
fi

# install.sh 以 root 运行 deploy.sh(写 /etc/docker/daemon.json、systemctl、安装 Docker 均需要 root);
# deploy.sh 通过 SUDO_USER 知道实际运维用户,fix_all_permissions 会把项目文件所有权修正给该用户
export SUDO_USER="${SUDO_USER:-$REAL_USER}"
export PUID="$REAL_UID"
export PGID="$REAL_GID"

info "启动部署向导..."
exec bash "$INSTALL_DIR/deploy.sh" --from-install
