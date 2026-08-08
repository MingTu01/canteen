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

set -eo pipefail

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

# 解析符号链接(防御性:deploy.sh 通常直接运行,但通过符号链接调用时也能正确解析)
resolve_project_dir() {
    local src="$1"
    if command -v readlink &>/dev/null; then
        local resolved
        resolved=$(readlink -f "$src" 2>/dev/null) && [[ -n "$resolved" ]] && { echo "$(cd "$(dirname "$resolved")" && pwd)"; return; }
    fi
    while [[ -L "$src" ]]; do
        local dir
        dir=$(cd "$(dirname "$src")" && pwd)
        src=$(readlink "$src")
        [[ "$src" != /* ]] && src="$dir/$src"
    done
    echo "$(cd "$(dirname "$src")" && pwd)"
}
PROJECT_DIR="$(resolve_project_dir "$0")"
cd "$PROJECT_DIR"

#==============================================================
# 修正项目目录所有权
#==============================================================
# 问题场景:用户用 sudo git clone 或 sudo ./deploy.sh 部署后,
# 项目目录归 root 所有,普通用户(canteen)执行 git pull 会报
# "detected dubious ownership" 错误。
# 解决:sudo 运行时自动把项目目录所有权交给实际调用者(SUDO_USER),
# root 仍有权限读写普通用户文件,不影响后续 sudo 部署。
# P1 修复:显式覆盖 backup/uploads/logs 子目录
# 原因:start_services() 中用 chown -R ${PUID}:${PGID} 修改这三个目录的属主,
# 但若 PUID/PGID 与 SUDO_USER 的 UID/GID 不一致(如手动改过 .env),
# 会导致后续普通用户运行 canteen upgrade 时 snapshot.sh 写入 backup/ 被拒绝。
# 此处在部署结束时(chown 所有项目目录后)再次显式 chown 这三个运行时目录,
# 确保它们归 SUDO_USER 所有(与 canteen 命令的运行用户一致)。
fix_ownership() {
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        local current_owner
        current_owner=$(stat -c '%U' "$PROJECT_DIR" 2>/dev/null || echo "")
        if [[ -n "$current_owner" ]] && [[ "$current_owner" != "$SUDO_USER" ]]; then
            info "修正项目目录所有权: ${current_owner} -> ${SUDO_USER}"
            chown -R "$SUDO_USER:$SUDO_USER" "$PROJECT_DIR" 2>/dev/null || true
        fi

        # 显式确保运行时子目录属主正确
        # 这些目录可能由 start_services() 创建并 chown 给 PUID/PGID,
        # 若 PUID/PGID 与 SUDO_USER 不一致,会导致后续 canteen upgrade 写入失败
        for d in backup uploads logs; do
            if [[ -d "$PROJECT_DIR/$d" ]]; then
                local dir_owner
                dir_owner=$(stat -c '%U' "$PROJECT_DIR/$d" 2>/dev/null || echo "")
                if [[ -n "$dir_owner" ]] && [[ "$dir_owner" != "$SUDO_USER" ]]; then
                    info "修正 ${d}/ 目录所有权: ${dir_owner} -> ${SUDO_USER}"
                    chown -R "$SUDO_USER:$SUDO_USER" "$PROJECT_DIR/$d" 2>/dev/null || true
                fi
            fi
        done
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

# 读取密码(隐藏输入,带确认,-r 防止反斜杠被转义消耗)
# 用法: read_password "提示信息" -> 输出到 stdout,失败返回非 0 且不输出
# 链路说明:部分环境(sudo/SSH/非TTY)下 read -s 会不等输入直接返回空,
# 若仅静默读取为空回退到可见输入,而可见输入在非交互环境同样会立即返回空,
# 两个密码都为空且相等,原逻辑会走 len<8 分支死循环。故改为有界重试(3 次),
# 完全读不到输入时明确失败,把决定权交回调用方(configure_env 会报错并提示用 canteen 重置),
# 避免把空密码静默写入 .env 导致 AdminInitializer 跳过创建超管。
read_password() {
    local prompt="$1"
    local pwd1 pwd2 attempt
    for attempt in 1 2 3; do
        pwd1=""
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} ${prompt}: ")" pwd1
        if [[ -z "$pwd1" ]]; then
            read -r -p "$(echo -e "${CYAN}[?]${NC} ${prompt}(可见输入,因静默读取未生效): ")" pwd1 || pwd1=""
        fi
        echo ""
        # 完全读不到输入(非交互环境,stdin 为空):继续下一次尝试,最后由调用方统一失败
        if [[ -z "$pwd1" ]]; then
            warn "未捕获到密码输入(第 ${attempt}/3 次)。若在非交互环境运行,请改用 canteen 菜单重置密码。"
            continue
        fi
        pwd2=""
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} 确认密码: ")" pwd2
        if [[ -z "$pwd2" ]]; then
            read -r -p "$(echo -e "${CYAN}[?]${NC} 确认密码(可见输入): ")" pwd2 || pwd2=""
        fi
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
        return 0
    done
    return 1
}

# 生成随机十六进制字符串
# P1 修复:fallback 改用 /dev/urandom(比时间戳不可预测)
rand_hex() {
    if command -v openssl &>/dev/null; then
        openssl rand -hex "$1"
    else
        # 用 /dev/urandom 作为 fallback(比时间戳安全,不可预测)
        head -c "$1" /dev/urandom | od -A n -t x1 | tr -d ' \n'
    fi
}

# 获取服务器 IP
get_server_ip() {
    hostname -I 2>/dev/null | awk '{print $1}' || echo "服务器IP"
}

#==============================================================
# P1 修复:部署前依赖检查
#==============================================================
check_dependencies() {
    local missing=()
    # 注意:Docker 由 install_docker() 自动安装,此处不校验 docker,
    # 否则全新服务器会因缺少 docker 而在此处直接退出,无法触发后续一键安装。
    for cmd in curl tar gzip; do
        command -v "$cmd" &>/dev/null || missing+=("$cmd")
    done
    if ! command -v openssl &>/dev/null; then
        warn "openssl 未安装,随机密钥生成将使用 /dev/urandom fallback"
    fi
    if [ ${#missing[@]} -gt 0 ]; then
        error "缺少必要命令: ${missing[*]}"
        echo "安装: sudo apt-get install -y ${missing[*]}"
        exit 1
    fi
}

#==============================================================
# P1 修复:部署前磁盘空间检查
#==============================================================
check_disk_space() {
    local min_gb="${1:-2}"
    local avail_kb
    avail_kb=$(df -P "$PROJECT_DIR" | awk 'NR==2{print $4}')
    local avail_gb=$((avail_kb / 1024 / 1024))
    if [ "$avail_gb" -lt "$min_gb" ]; then
        error "磁盘空间不足:剩余 ${avail_gb}GB,需要至少 ${min_gb}GB"
        exit 1
    fi
    info "磁盘空间: 剩余 ${avail_gb}GB"
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
    echo "此操作将重置超管账号密码并重启后端(同时清除登录锁定)。"
    echo "  - 若账号已存在且为超管:更新密码"
    echo "  - 若账号不存在:强制创建新超管"
    echo ""

    local username password
    username=$(read_input "超管账号名" "admin")
    password=$(read_password "输入新密码(至少 8 位)")
    if [[ -z "$password" || ${#password} -lt 8 ]]; then
        error "未能获取有效密码(至少 8 位),重置已取消"
        return 1
    fi

    # 检查 .env 可写
    local envfile="$PROJECT_DIR/.env"
    if ! touch "$envfile" 2>/dev/null; then
        error "无法写入 .env 文件: $envfile(权限不足?)"
        return 1
    fi

    # 写入 .env 并设置 force 标志(AdminInitializer 读取这些变量)
    set_env_var "INIT_ADMIN_USERNAME" "$username"
    set_env_var "INIT_ADMIN_PASSWORD" "$password"
    set_env_var "INIT_ADMIN_FORCE" "true"

    info "正在重建后端服务(读取新配置 + 清除登录锁定)..."
    docker compose up -d --no-deps backend

    info "等待后端启动..."
    local ok=false
    for i in $(seq 1 30); do
        if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
            ok=true
            break
        fi
        sleep 2
        printf "."
    done
    echo ""

    if [[ "$ok" == "true" ]]; then
        # 自动清理 .env 中的敏感变量(密码已在数据库中,无需保留)
        # 安全清理:先校验过滤结果非空,避免 grep 失败导致空文件覆盖 .env
        info "清理临时配置..."
        local tmp
        tmp=$(mktemp)
        if grep -v "^INIT_ADMIN_FORCE=" "$envfile" 2>/dev/null | grep -v "^INIT_ADMIN_PASSWORD=" > "$tmp" && [[ -s "$tmp" ]]; then
            cp "$envfile" "${envfile}.bak" 2>/dev/null
            mv "$tmp" "$envfile"
            # P2-7 安全修复:清理后重新设置权限 600
            chmod 600 "$envfile"
        else
            rm -f "$tmp"
            warn "清理 .env 失败,原文件未修改"
        fi

        info "密码重置成功!"
        echo "  超管账号: ${username}"
        echo "  请使用新密码登录管理后台"
    else
        error "后端启动超时,请查看日志: docker compose logs backend"
    fi
}

#==============================================================
# 设置 .env 变量(若不存在则追加,存在则更新)
# 用双引号包裹值(escape_env_value 已做 Compose 兼容转义),避免 $/空格/#/反引号等被 shell 展开,
# 也保证含单引号的密码能被 Docker Compose 的 dotenv 解析器正确读取。
# 用 awk + ENVIRON 传递值,彻底避免 sed 对 | & / \ 等特殊字符的转义问题
#==============================================================
# 转义值,使其在 .env 的双引号包裹下能被 Docker Compose 正确解析。
# Docker Compose 的 dotenv 解析规则:
#   - 单引号值:全部字面量,无任何转义,值内不能出现单引号 → 不能用单引号包裹含单引号的密码。
#   - 双引号值:支持转义,`\` 可转义 `\`、`"`、`$`、反引号;且 `$` 会触发变量插值。
# 因此统一用双引号包裹,并转义 `\`、`"`、`$`、反引号,确保含单引号/特殊字符的密码可被解析。
# 注意:必须先转义 `\`,再转义 `"`/`$`/反引号,顺序不能反。
escape_env_value() {
    local v="$1"
    v="${v//\\/\\\\}"   # \ -> \\
    v="${v//\"/\\\"}"   # " -> \"
    v="${v//\$/\\\$}"   # $ -> \$(防止 Compose 插值)
    v="${v//\`/\\\`}"   # ` -> \`
    printf '%s' "$v"
}

# 安全写入 .env 一行:KEY="value"(双引号包裹,值已由 escape_env_value 做 Compose 兼容转义)
# 关键修复:用 printf %s 写入,绝不做 shell 展开。
# 原因:①不能用未加引号的 heredoc(cat <<EOF)写用户输入的密码——heredoc 会对值做参数/命令展开,
# 密码若含 $xxx 会被展开成空,导致 INIT_ADMIN_PASSWORD 被静默写成空值,AdminInitializer 跳过创建超管、无法登录。
# 这正是 canteen 重置(用 set_env_var 的 awk 方式、不展开)能成功、而 deploy 失败的原因。
# ②不能用单引号包裹 + shell 的 '\'' 转义——那是 shell 的转义规则,Docker Compose dotenv 不认,
# 密码含单引号时(如 qweasd2864..')会报 "unexpected character "'"",导致整个 .env 无法被 Compose 读取。
write_env_line() {
    local key="$1" value="$2"
    if [[ -n "$value" ]]; then
        printf "%s=\"%s\"\n" "$key" "$value"
    else
        printf "%s=\n" "$key"
    fi
}

set_env_var() {
    local key="$1"
    local value="$2"
    local envfile="$PROJECT_DIR/.env"

    # 确保 .env 存在
    touch "$envfile"

    # 用双引号包裹值,避免特殊字符在 source/读取时被 shell 展开,且兼容 Compose dotenv 解析
    local escaped_value
    escaped_value=$(escape_env_value "$value")
    local new_line="${key}=\"${escaped_value}\""

    if grep -q "^${key}=" "$envfile" 2>/dev/null; then
        # 更新已有行:匹配以 key= 开头的行,整行替换为 key='value'
        local tmp
        tmp=$(mktemp)
        KEY="$key" LINE="$new_line" awk '
            BEGIN { k = ENVIRON["KEY"]; line = ENVIRON["LINE"] }
            index($0, k "=") == 1 { print line; next }
            { print }
        ' "$envfile" > "$tmp" && mv "$tmp" "$envfile"
    else
        # 追加新行
        echo "$new_line" >> "$envfile"
    fi

    # P2-7 安全修复:每次写入 .env 后强制权限 600 并交给运维用户
    chown_env_to_operator "$envfile"
}

# 安全读取 .env 变量(不执行 source,避免特殊字符导致 shell 展开/命令执行)
# 用法: read_env_var "KEY" [envfile] -> 输出值(去除外层引号),失败返回非 0
read_env_var() {
    local key="$1" envfile="${2:-$PROJECT_DIR/.env}"
    [[ -f "$envfile" ]] || return 1
    local line value
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line// }" ]] && continue
        if [[ "$line" =~ ^${key}= ]]; then
            value="${line#*=}"
            local quoted=""
            # 去除外层引号(单引号或双引号),双引号需再反转义
            if [[ "$value" =~ ^\'.*\'$ ]]; then
                value="${value:1:-1}"
            elif [[ "$value" =~ ^\".*\"$ ]]; then
                value="${value:1:-1}"
                quoted="double"
            fi
            # 双引号包裹的值:反转义 \\  \"  \$  \`(与 escape_env_value 对应)
            if [[ "$quoted" == "double" ]]; then
                value="${value//\\\\/\\}"   # \\  -> \
                value="${value//\\\"/\"}"   # \"  -> "
                value="${value//\\\$/\$}"   # \$  -> $(去掉转义反斜杠,保留字面 $)
                value="${value//\\\`/\`}"   # \`  -> `
            fi
            # 去除行尾 Windows 换行符残留(\r)
            # 原因:.env 若在 Windows 上创建/编辑,行尾会带 \r,read 不会剥离,
            # 导致值尾部多一个回车符(如 canteen2026\r),与 docker-compose 解析结果不一致。
            value="${value//$'\r'/}"
            printf '%s' "$value"
            return 0
        fi
    done < "$envfile"
    return 1
}

# 将 .env 属主改给实际运维用户(SUDO_USER 优先,否则回退到 canteen 系统用户)
# 原因:deploy.sh 常以 sudo 运行,.env 默认归 root;而后续 canteen 升级/重置
# 以普通用户运行,写 .env 会报 "permission denied"。这里统一把属主交给运维用户。
# 用法: chown_env_to_operator <envfile>
chown_env_to_operator() {
    local envfile="$1"
    local target=""
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        target="$SUDO_USER"
    elif id "canteen" >/dev/null 2>&1; then
        target="canteen"
    fi
    if [[ -n "$target" ]]; then
        chown "$target:$target" "$envfile" 2>/dev/null || true
    fi
    # 敏感文件强制 600
    chmod 600 "$envfile" 2>/dev/null || true
}

#==============================================================
# 部署步骤函数
#==============================================================

install_docker() {
    step "1/9 检测 Docker 环境"

    if command -v docker &> /dev/null && docker info &> /dev/null; then
        info "Docker 已安装且运行中,跳过安装"
        add_user_to_docker_group
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
    add_user_to_docker_group
}

#==============================================================
# 把当前用户加入 docker 组(避免后续 canteen 命令需要 sudo)
#==============================================================
# 问题场景:sudo ./deploy.sh 部署后,普通用户运行 canteen(内含 docker compose)
# 会报 "permission denied while trying to connect to the Docker daemon socket"。
# 解决:把实际调用者(SUDO_USER)加入 docker 组,后续无需 sudo 即可操作 Docker。
# 注意:加入 docker 组后需重新登录或 newgrp docker 才能生效。
add_user_to_docker_group() {
    # 只在 sudo 运行时有意义(root 直接运行无需此步骤)
    if [[ -z "$SUDO_USER" ]] || [[ "$SUDO_USER" == "root" ]]; then
        return 0
    fi

    # 检查用户是否已在 docker 组
    if id -nG "$SUDO_USER" 2>/dev/null | grep -qw "docker"; then
        return 0
    fi

    info "将用户 ${SUDO_USER} 加入 docker 组(避免后续 canteen 命令需要 sudo)..."
    if usermod -aG docker "$SUDO_USER" 2>/dev/null; then
        warn "已加入 docker 组,需重新登录后生效(或执行: newgrp docker)"
        warn "否则普通用户运行 canteen 仍需 sudo"
    else
        warn "加入 docker 组失败,后续普通用户运行 canteen 可能需要 sudo"
    fi
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

    # 密码策略(重配 = 复用旧凭证,不破坏已有数据):
    # 重跑 deploy.sh 对已部署系统重装/重配时,若 .env 已存在,敏感凭证
    # (MYSQL_ROOT_PASSWORD / REDIS_PASSWORD / JWT_SECRET / DB_APP_PASSWORD /
    #  BACKUP_ENCRYPTION_KEY)一律复用旧值,不重新随机生成。
    # 原因:MySQL root 密码与数据卷绑定——首次初始化后,若重配时换成新随机密码,
    # 与数据卷里的旧密码不一致,导致 init-db-user.sh 无法登录、后端无法连库。
    # 只有全新安装(无 .env)或用户主动删除 .env 时才生成新随机密码。
    # 这样"重跑 deploy.sh 重装/重配"不再破坏已有数据库。

    if [[ -f .env ]] && ! confirm_overwrite_env; then
        info "保留现有 .env 文件"
        return
    fi

    info "即将生成 .env 配置文件,请按提示输入:"
    echo ""

    # 复用旧值策略(P1-6 修复):若 .env 已存在,敏感凭证默认复用旧值,不重新随机生成。
    # 原因:MySQL root 密码与数据卷绑定——首次初始化后,若重配时换成新随机密码,
    # 与数据卷里的旧密码不一致,导致 init-db-user.sh 无法登录、后端无法连库。
    # 只有全新安装(无 .env)或用户主动删除 .env 时才生成新随机密码。
    # 这样"重跑 deploy.sh 重装/重配"不再破坏已有数据库。
    local old_mysql_pwd="" old_redis_pwd="" old_jwt_secret="" old_db_app_user="" old_db_app_pwd="" old_backup_key=""
    if [[ -f .env ]]; then
        old_mysql_pwd=$(read_env_var "MYSQL_ROOT_PASSWORD" ".env" 2>/dev/null) || old_mysql_pwd=""
        old_redis_pwd=$(read_env_var "REDIS_PASSWORD" ".env" 2>/dev/null) || old_redis_pwd=""
        old_jwt_secret=$(read_env_var "JWT_SECRET" ".env" 2>/dev/null) || old_jwt_secret=""
        old_db_app_user=$(read_env_var "DB_APP_USERNAME" ".env" 2>/dev/null) || old_db_app_user=""
        old_db_app_pwd=$(read_env_var "DB_APP_PASSWORD" ".env" 2>/dev/null) || old_db_app_pwd=""
        old_backup_key=$(read_env_var "BACKUP_ENCRYPTION_KEY" ".env" 2>/dev/null) || old_backup_key=""
    fi

    # MySQL 密码:已有则复用(与数据卷绑定),否则询问/随机生成
    local mysql_pwd="$old_mysql_pwd"
    if [[ -n "$mysql_pwd" ]]; then
        info "已复用现有 MySQL root 密码(与数据卷保持一致)"
    else
        ask "MySQL 数据库密码(留空=自动生成随机密码)"
        read -r mysql_pwd
        if [[ -z "$mysql_pwd" ]]; then
            mysql_pwd=$(rand_hex 16)
            info "已生成随机 MySQL 密码"
        elif [[ ${#mysql_pwd} -lt 8 ]]; then
            warn "密码不足 8 位,改为自动生成"
            mysql_pwd=$(rand_hex 16)
        fi
    fi

    # Redis 密码(P1-4 安全修复:Redis 强制密码,随机生成;已有则复用)
    local redis_pwd="$old_redis_pwd"
    if [[ -n "$redis_pwd" ]]; then
        info "已复用现有 Redis 密码"
    else
        redis_pwd=$(rand_hex 16)
        info "已生成随机 Redis 密码"
    fi

    # JWT 密钥(已有则复用,否则随机生成)
    local jwt_secret="$old_jwt_secret"
    if [[ -z "$jwt_secret" ]]; then
        jwt_secret=$(rand_hex 32)
    fi

    # MySQL 应用专用用户(P1-3:最小权限,仅 DML;已有则复用)
    local db_app_user="${old_db_app_user:-canteen_app}"
    local db_app_pwd="$old_db_app_pwd"
    if [[ -n "$db_app_pwd" ]]; then
        info "已复用 MySQL 应用用户 ${db_app_user}(仅 DML 权限)"
    else
        db_app_pwd=$(rand_hex 16)
        info "已创建 MySQL 应用专用用户: ${db_app_user}(仅 DML 权限)"
    fi

    # 备份加密密钥(P1-1:备份 AES-256 加密,随机生成;已有则复用)
    local backup_key="$old_backup_key"
    if [[ -z "$backup_key" ]]; then
        backup_key=$(rand_hex 32)
    fi

    # P1-5 容器降权:检测宿主机运行用户 UID/GID,容器以此 UID 运行(非 root)
    # 默认 1000(Ubuntu/Debian 第一个普通用户),sudo 运行时取 SUDO_USER 的实际 UID/GID
    local puid=1000 pgid=1000
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        puid=$(id -u "$SUDO_USER" 2>/dev/null || echo 1000)
        pgid=$(id -g "$SUDO_USER" 2>/dev/null || echo 1000)
    fi
    info "容器将以 UID=${puid} GID=${pgid} 运行(非 root 降权,与宿主机用户对齐)"

    # 超管账号
    echo ""
    info "配置超级管理员账号"
    local admin_user admin_pwd
    admin_user=$(read_input "超管登录账号" "admin")
    # 校验超管密码非空且>=8位:read_password 已做静默读取空值回退,这里再兜底,
    # 避免因任何原因捕获到空/过短密码而静默写入,导致 AdminInitializer 跳过、超管无法登录。
    admin_pwd=""
    for _ in 1 2 3; do
        admin_pwd=$(read_password "超管登录密码(至少 8 位)")
        if [[ -n "$admin_pwd" && ${#admin_pwd} -ge 8 ]]; then
            break
        fi
        warn "超管密码为空或不足 8 位,请重新输入"
    done
    if [[ -z "$admin_pwd" || ${#admin_pwd} -lt 8 ]]; then
        error "超管密码多次输入无效,无法配置超管账号。"
        error "请重新运行 sudo ./deploy.sh;或部署完成后运行 canteen 菜单【重置管理员密码】设置。"
        return 1
    fi

    # 转义所有用户输入值(用于 .env 双引号包裹,兼容 Docker Compose dotenv 解析)
    # 双引号包裹的值中,`\` 转义 `\`、`"`、`$`、反引号,可保留含单引号/特殊字符的密码
    local e_mysql_pwd e_db_app_user e_db_app_pwd e_redis_pwd e_jwt_secret e_backup_key e_admin_user e_admin_pwd
    e_mysql_pwd=$(escape_env_value "$mysql_pwd")
    e_db_app_user=$(escape_env_value "$db_app_user")
    e_db_app_pwd=$(escape_env_value "$db_app_pwd")
    e_redis_pwd=$(escape_env_value "$redis_pwd")
    e_jwt_secret=$(escape_env_value "$jwt_secret")
    e_backup_key=$(escape_env_value "$backup_key")
    e_admin_user=$(escape_env_value "$admin_user")
    e_admin_pwd=$(escape_env_value "$admin_pwd")

    # 写入 .env(所有用户输入值用双引号包裹并做 Compose 兼容转义,固定数字/布尔值无需引号)
    # 链路修复:改用 write_env_line/printf 逐行写入,不再用未加引号的 heredoc。
    # 原因:①heredoc 会对值做 shell 展开,用户密码若含 $xxx/反引号/反斜杠会被展开成空或截断,
    # 导致 INIT_ADMIN_PASSWORD 静默变空、AdminInitializer 跳过建超管、无法登录。
    # ②必须用双引号而非单引号包裹——单引号包裹 + shell '\'' 转义不被 Docker Compose dotenv 解析,
    # 密码含单引号时(如 qweasd2864..')会报 unexpected character,整个 .env 无法被 Compose 读取。
    # 与 cmd_reset_admin 的 set_env_var(awk 不展开)行为保持一致。
    {
        echo "# MySQL 数据库密码"
        write_env_line "MYSQL_ROOT_PASSWORD" "$e_mysql_pwd"
        echo ""
        echo "# MySQL 应用专用用户(P1-3:仅 DML 权限,无 DDL/GRANT)"
        write_env_line "DB_APP_USERNAME" "$e_db_app_user"
        write_env_line "DB_APP_PASSWORD" "$e_db_app_pwd"
        echo ""
        echo "# Redis 密码(P1-4 安全修复:Redis 强制密码 + 禁用危险命令)"
        write_env_line "REDIS_PASSWORD" "$e_redis_pwd"
        echo ""
        echo "# JWT 密钥(自动生成)"
        write_env_line "JWT_SECRET" "$e_jwt_secret"
        echo ""
        echo "# Token 过期时间(毫秒)"
        echo "JWT_EXPIRATION=86400000"
        echo "JWT_EMPLOYEE_EXPIRATION=2592000000"
        echo "JWT_TERMINAL_EXPIRATION=31536000000"
        echo ""
        echo "# 备份加密密钥(P1-1:备份文件 AES-256-CBC 加密)"
        write_env_line "BACKUP_ENCRYPTION_KEY" "$e_backup_key"
        echo ""
        echo "# P1-5 容器降权:容器以非 root 运行,UID/GID 与宿主机用户对齐"
        echo "# 由 deploy.sh 自动检测,无需手动修改"
        echo "PUID=${puid}"
        echo "PGID=${pgid}"
        echo ""
        echo "# 初始超管账号(后端首次启动时读取,初始化后可删除)"
        echo "# 注意:FORCE=true 确保重新部署时也能更新已存在超管的密码"
        write_env_line "INIT_ADMIN_USERNAME" "$e_admin_user"
        write_env_line "INIT_ADMIN_PASSWORD" "$e_admin_pwd"
        echo "INIT_ADMIN_FORCE=true"
    } > .env

    # P2-7 安全修复:.env 包含敏感信息,强制权限 600(仅属主可读写)
    chmod 600 .env

    echo ""
    info ".env 已生成"
    warn "请妥善保管 .env 文件,包含敏感信息!"

    # sudo 部署时,.env 会被创建为 root 所有,后续 canteen 用户无法写入
    # 立即 chown 给实际运维用户(SUDO_USER 或 canteen),避免 canteen.sh 重置密码时权限失败
    chown_env_to_operator ".env"
}

# 确认是否覆盖已有 .env
confirm_overwrite_env() {
    warn "已存在 .env 文件"
    ask "是否重新配置?(y/n)"
    read -r ans
    [[ "$ans" == "y" || "$ans" == "Y" ]]
}

build_artifacts() {
    # 检测当前分支(deploy 分支已含产物,无需构建)
    local current_branch=""
    if [ -d ".git" ]; then
        current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
    fi

    # 判断产物是否已存在(deploy 分支或手动复制过产物)
    local has_artifacts=false
    if [ -f "deploy/backend/app.jar" ] && [ -f "deploy/admin-web/html/index.html" ] && [ -f "deploy/h5/html/index.html" ]; then
        has_artifacts=true
    fi

    if [ "$current_branch" = "deploy" ] || [ "$has_artifacts" = true ]; then
        step "4/9 跳过构建(deploy 分支已含产物)"
        if [ "$current_branch" = "deploy" ]; then
            info "当前为 deploy 分支,产物已预构建,无需宿主机 JDK/Node.js"
        else
            info "检测到 deploy/ 目录已有产物,跳过构建"
        fi
        info "  后端:    $(du -h deploy/backend/app.jar 2>/dev/null | cut -f1 || echo '?')"
        info "  管理后台: $(du -sh deploy/admin-web/html 2>/dev/null | cut -f1 || echo '?')"
        info "  H5:      $(du -sh deploy/h5/html 2>/dev/null | cut -f1 || echo '?')"
        echo ""
        info "如需强制重新构建,请切换到 main 分支运行: ./scripts/build.sh all"
        return 0
    fi

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
    # P1 修复:补全 5 个端口(含 MySQL 13306 / Redis 16379)
    local required_ports=("18080" "18081" "18082" "13306" "16379")
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
        echo "  1) 查看占用进程: sudo ss -tlnp | grep -E '18080|18081|18082|13306|16379'"
        echo "  2) 停止旧服务:   sudo ./deploy.sh stop"
        echo "  3) 如是系统 nginx/apache: sudo systemctl stop nginx && sudo systemctl disable nginx"
        exit 1
    fi
    info "端口 18080/18081/18082/13306/16379 可用"

    mkdir -p backup uploads logs

    # P1-5 容器降权:backup/ 和 uploads/ 目录属主必须与容器运行 UID 对齐
    # 从 .env 读取 PUID/PGID(由 configure_env 自动检测写入),默认 1000
    # 用 read_env_var 读取(兼容单引号包裹的值),不依赖 grep|cut 管道(避免 pipefail 陷阱)
    local puid=${PUID:-1000}
    local pgid=${PGID:-1000}
    if [[ -f .env ]]; then
        local env_puid env_pgid
        env_puid=$(read_env_var "PUID" ".env" 2>/dev/null) || env_puid=""
        env_pgid=$(read_env_var "PGID" ".env" 2>/dev/null) || env_pgid=""
        puid="${env_puid:-1000}"
        pgid="${env_pgid:-1000}"
    fi
    info "设置 backup/ uploads/ logs/ 目录权限(UID=${puid},GID=${pgid},与容器运行用户对齐)..."
    # P1 修复:补全 logs 目录(后端日志写入需要)
    chown -R "${puid}:${pgid}" backup uploads logs 2>/dev/null || warn "chown backup/uploads/logs 失败(不影响部署,但容器内可能无法写入)"

    # P0 修复:分阶段启动,消除 backend 与 init-db-user.sh 竞争
    # 之前 docker compose up -d 一次性启动所有服务,backend 在 MySQL healthy 后
    # 立即启动,与 init-db-user.sh 同时执行,可能导致 backend 用未创建的用户连接失败

    # 阶段 1:仅启动 MySQL 和 Redis(不启动 backend/admin-web/h5)
    info "阶段 1/3:启动 MySQL 和 Redis..."
    docker compose up -d mysql redis

    # 等待 MySQL healthy
    info "等待 MySQL 健康检查通过..."
    local mysql_wait=0
    while [[ $mysql_wait -lt 60 ]]; do
        if docker compose ps mysql 2>/dev/null | grep -q "healthy"; then
            break
        fi
        sleep 3
        mysql_wait=$((mysql_wait + 3))
        printf "."
    done
    echo ""
    if [[ $mysql_wait -ge 60 ]]; then
        error "MySQL 启动超时,无法创建应用用户"
        exit 1
    fi

    # 阶段 2:创建应用专用用户(此时 backend 尚未启动,无竞争)
    # init-db-user.sh 设计为宿主机执行(通过 docker exec 操作 MySQL),
    # 不能挂载到 /docker-entrypoint-initdb.d/(容器内无 docker 命令会静默失败)
    if [[ -f scripts/init-db-user.sh ]]; then
        info "阶段 2/3:创建 MySQL 应用专用用户(canteen_app,最小权限)..."
        chmod +x scripts/init-db-user.sh 2>/dev/null || true
        if ! bash scripts/init-db-user.sh; then
            error "init-db-user.sh 执行失败,canteen_app 用户未创建,后端将无法连接数据库"
            exit 1
        fi
    else
        warn "scripts/init-db-user.sh 不存在,跳过应用用户创建(后端可能无法连接数据库)"
    fi

    # 阶段 3:启动 backend 和前端服务(Flyway 将创建 flyway_schema_history 表)
    info "阶段 3/3:启动后端和前端服务..."
    docker compose up -d

    info "等待后端健康检查通过..."
    local max_wait=180
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
        # P0 修复:健康检查超时必须阻断部署,不能只 warn 否则会误报部署成功
        error "后端健康检查超时(${max_wait}s),部署失败!"
        echo ""
        info "排查建议:"
        echo "  1. 查看后端日志: docker compose logs --tail=100 backend"
        echo "  2. 检查服务状态: docker compose ps"
        echo "  3. 手动检查健康: curl -v http://localhost:18082/api/system/health"
        echo ""
        warn "服务可能已部分启动,可用 ./deploy.sh stop 停止后排查问题"
        exit 1
    fi

    # 阶段 4:backend healthy 后,Flyway 已建表,重新执行 init-db-user.sh 回收元数据表权限
    # 首次部署时 flyway_schema_history 表不存在,权限回收被跳过;此时表已存在,需补回收
    if [[ -f scripts/init-db-user.sh ]]; then
        info "回收 Flyway 元数据表写权限(首次部署后补执行)..."
        bash scripts/init-db-user.sh 2>/dev/null || warn "元数据表权限回收失败(不影响运行,但建议手动执行 init-db-user.sh)"
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
        admin_user=$(read_env_var "INIT_ADMIN_USERNAME" ".env" 2>/dev/null) || admin_user=""
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
# 注:安全加固(UFW/fail2ban/auditd/AIDE)已从 deploy.sh 移除
# 原因:UFW 自动配置会封锁服务器端口,导致无法访问
# 如需安全加固,请参考 docs/SERVER_HARDENING.md 手动执行
#==============================================================

#==============================================================
# 校验超管是否已成功初始化(backend healthy 后查询 admin 表)
# 返回 0 表示已确认存在指定超管(role=1),1 表示未确认。
# 链路意义:只有确认超管已落库,才能安全清理 .env 中的 INIT_ADMIN_*。
# 若未创建成功就清理,后续 docker compose restart(不重读 env)不会再次初始化,
# 超管将永远无法用部署时设置的密码登录,只能依赖 canteen 重置。
# 因此 deploy 末尾必须"先校验、后清理"。
#==============================================================
verify_admin_initialized() {
    local username="$1" envfile="$PROJECT_DIR/.env"
    [[ -n "$username" ]] || return 1

    local root_pwd db_name
    root_pwd=$(read_env_var "MYSQL_ROOT_PASSWORD" "$envfile" 2>/dev/null) || root_pwd=""
    db_name=$(read_env_var "MYSQL_DATABASE" "$envfile" 2>/dev/null) || db_name=""
    [[ -n "$root_pwd" ]] || return 1
    db_name="${db_name:-canteen}"

    # 等待 backend 容器 healthy(AdminInitializer 在 ApplicationReadyEvent 执行)
    local i=0 state=""
    while [[ $i -lt 60 ]]; do
        state=$(docker inspect -f '{{.State.Health.Status}}' canteen-backend 2>/dev/null || echo "missing")
        if [[ "$state" == "healthy" ]]; then
            break
        fi
        sleep 3
        i=$((i + 3))
    done
    if [[ "$state" != "healthy" ]]; then
        warn "backend 未进入 healthy(${state}),无法确认超管初始化"
        return 1
    fi

    # 查询 admin 表是否存在该超管(转义用户名中的单引号,防 SQL 注入)
    local esc_user count
    esc_user="${username//\'/\'\'}"
    count=$(docker exec -i canteen-mysql mysql -uroot -p"${root_pwd}" -s -N -e \
        "SELECT COUNT(*) FROM ${db_name}.admin WHERE username='${esc_user}' AND role=1;" 2>/dev/null || echo "0")
    [[ "$count" -ge 1 ]] 2>/dev/null
}

#==============================================================
# P2-8 部署后清理 .env 中的临时敏感变量
#==============================================================
cleanup_sensitive_env() {
    local envfile="$PROJECT_DIR/.env"
    if [[ ! -f "$envfile" ]]; then
        return 0
    fi

    info "清理 .env 中的临时敏感变量(INIT_ADMIN_PASSWORD / INIT_ADMIN_FORCE)..."

    local tmp
    tmp=$(mktemp)
    # 删除 INIT_ADMIN_FORCE 和 INIT_ADMIN_PASSWORD,保留 INIT_ADMIN_USERNAME 供参考
    if grep -v "^INIT_ADMIN_FORCE=" "$envfile" 2>/dev/null \
        | grep -v "^INIT_ADMIN_PASSWORD=" > "$tmp" && [[ -s "$tmp" ]]; then
        cp "$envfile" "${envfile}.bak" 2>/dev/null
        mv "$tmp" "$envfile"
        chmod 600 "$envfile"
        info "已清理 INIT_ADMIN_PASSWORD 和 INIT_ADMIN_FORCE(密码已在数据库中)"
    else
        rm -f "$tmp"
        warn "清理 .env 失败,请手动删除 INIT_ADMIN_PASSWORD"
    fi
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

    # P1 修复:部署前检查依赖和磁盘空间(快速失败,避免部署到一半才报错)
    check_dependencies
    check_disk_space

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

    # .env 由 configure_env 在 sudo 权限下创建(归 root)。
    # 统一交给运维用户(SUDO_USER 或 canteen),否则普通用户 docker compose /
    # canteen 升级会报 "open .env: permission denied"。
    if [[ -f "$PROJECT_DIR/.env" ]]; then
        chown_env_to_operator "$PROJECT_DIR/.env"
    fi

    # P2-7:确保 .env 权限为 600(仅属主可读写)
    if [[ -f "$PROJECT_DIR/.env" ]]; then
        chmod 600 "$PROJECT_DIR/.env"
    fi

    # P2-8:部署完成后清理 .env 中的临时敏感变量(INIT_ADMIN_PASSWORD / INIT_ADMIN_FORCE)
    # 链路修复:必须先确认超管已成功落库才清理,否则后续 restart(不重读 env)无法再初始化超管。
    # 由 verify_admin_initialized 校验;未确认时保留 INIT_ADMIN_*,避免永久丢失部署密码。
    local admin_user=""
    admin_user=$(read_env_var "INIT_ADMIN_USERNAME" "$PROJECT_DIR/.env" 2>/dev/null) || admin_user=""
    if [[ -n "$admin_user" ]] && verify_admin_initialized "$admin_user"; then
        cleanup_sensitive_env
    else
        warn "未能确认超管 '${admin_user:-?}' 已初始化,保留 .env 中的 INIT_ADMIN_PASSWORD"
        warn "请先排查后端日志: docker compose logs --tail=100 backend"
        warn "后端就绪后执行以下命令重建 backend 以完成超管初始化:"
        warn "  docker compose up -d --no-deps backend"
        warn "或在服务器上运行 canteen → 重置管理员密码"
    fi

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
