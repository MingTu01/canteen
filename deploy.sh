#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 部署脚本 V2
#==============================================================
# 用法:
#   ./deploy.sh                # 交互式部署向导(首次部署推荐)
#   ./deploy.sh deploy         # 同上
#   ./deploy.sh status         # 查看服务状态
#   ./deploy.sh logs [服务]    # 查看日志
#   ./deploy.sh stop           # 停止所有服务
#   ./deploy.sh restart [服务] # 重启服务
#   ./deploy.sh reset-admin    # 重置超管账号密码
#
# 一键安装(在全新服务器上,多加速器自动切换):
#   for p in "https://gh-proxy.com/https/" "https://ghfast.top/https/" "https://mirror.ghproxy.com/https/" "https://"; do
#     curl -fsSL --connect-timeout 8 --max-time 60 "${p}raw.githubusercontent.com/MingTu01/canteen/main/install.sh" -o /tmp/canteen-install.sh && [ -s /tmp/canteen-install.sh ] && break
#   done && sudo bash /tmp/canteen-install.sh
#
# 适用系统: CentOS 7+/8/9, Ubuntu 18.04+, Debian 10+
#==============================================================

set -eo pipefail

#==============================================================
# 颜色与输出
#==============================================================
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

#==============================================================
# 项目目录解析
#==============================================================
resolve_project_dir() {
    local src="$1"
    if command -v readlink &>/dev/null; then
        local resolved
        resolved=$(readlink -f "$src" 2>/dev/null) && [[ -n "$resolved" ]] && { echo "$(cd "$(dirname "$resolved")" && pwd)"; return; }
    fi
    while [[ -L "$src" ]]; do
        local dir; dir=$(cd "$(dirname "$src")" && pwd)
        src=$(readlink "$src")
        [[ "$src" != /* ]] && src="$dir/$src"
    done
    echo "$(cd "$(dirname "$src")" && pwd)"
}
PROJECT_DIR="$(resolve_project_dir "$0")"
cd "$PROJECT_DIR"

#==============================================================
# 运行用户检测
#==============================================================
# 确定实际运维用户:sudo 运行时为 SUDO_USER,否则为当前用户
# root 直接运行时,使用 canteen 系统用户(由 install.sh 创建)
get_operator() {
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        echo "$SUDO_USER"
    elif [[ "$(whoami)" != "root" ]]; then
        echo "$(whoami)"
    elif id "canteen" &>/dev/null; then
        echo "canteen"
    else
        echo "root"
    fi
}
OPERATOR=$(get_operator)
OPERATOR_UID=$(id -u "$OPERATOR" 2>/dev/null || echo 1000)
OPERATOR_GID=$(id -g "$OPERATOR" 2>/dev/null || echo 1000)

#==============================================================
# 核心权限管理 —— 统一处理所有权限问题
#==============================================================
# 调用时机: 部署开始前、部署结束后、每次写入 .env 后
# 解决以下权限问题:
#   1. sudo git clone 导致目录归 root → chown 给实际用户
#   2. git pull 后 .sh 丢失 +x → chmod +x
#   3. git "dubious ownership" → safe.directory
#   4. .env 归 root → chown 给实际用户 + chmod 600
#   5. backup/uploads/logs 目录属主不对 → chown
#   6. Docker 组未加入 → usermod -aG docker
fix_all_permissions() {
    local target_user="$OPERATOR"
    local target_uid="$OPERATOR_UID"
    local target_gid="$OPERATOR_GID"

    # 1. 项目目录所有权(sudo 运行时才需要修正)
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        local owner
        owner=$(stat -c '%U' "$PROJECT_DIR" 2>/dev/null || echo "")
        if [[ -n "$owner" ]] && [[ "$owner" != "$SUDO_USER" ]]; then
            info "修正项目目录所有权: ${owner} -> ${SUDO_USER}"
            chown -R "$SUDO_USER:$SUDO_USER" "$PROJECT_DIR" 2>/dev/null || true
        fi
    fi

    # 2. 脚本可执行权限
    local need_fix=false
    for f in "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh; do
        [[ -f "$f" ]] || continue
        [[ -x "$f" ]] || { need_fix=true; break; }
    done
    if [[ "$need_fix" == "true" ]]; then
        info "修正脚本可执行权限..."
        chmod +x "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh 2>/dev/null || true
    fi

    # 3. git safe.directory
    if [[ -d "$PROJECT_DIR/.git" ]]; then
        git config --global --add safe.directory "$PROJECT_DIR" 2>/dev/null || true
        if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
            sudo -u "$SUDO_USER" git config --global --add safe.directory "$PROJECT_DIR" 2>/dev/null || true
        fi
    fi

    # 4. 运行时目录
    for d in backup uploads logs; do
        mkdir -p "$PROJECT_DIR/$d" 2>/dev/null || true
        if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
            local dir_owner
            dir_owner=$(stat -c '%U' "$PROJECT_DIR/$d" 2>/dev/null || echo "")
            if [[ -n "$dir_owner" ]] && [[ "$dir_owner" != "$SUDO_USER" ]]; then
                chown -R "$SUDO_USER:$SUDO_USER" "$PROJECT_DIR/$d" 2>/dev/null || true
            fi
        fi
    done

    # 5. .env 权限
    if [[ -f "$PROJECT_DIR/.env" ]]; then
        chmod 600 "$PROJECT_DIR/.env" 2>/dev/null || true
        if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
            chown "$SUDO_USER:$SUDO_USER" "$PROJECT_DIR/.env" 2>/dev/null || true
        elif [[ "$OPERATOR" != "root" ]] && [[ "$(whoami)" == "root" ]]; then
            chown "$OPERATOR:$OPERATOR" "$PROJECT_DIR/.env" 2>/dev/null || true
        fi
    fi

    # 6. Docker 组(确保普通用户可操作 Docker)
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        if ! id -nG "$SUDO_USER" 2>/dev/null | grep -qw "docker"; then
            info "将用户 ${SUDO_USER} 加入 docker 组..."
            if usermod -aG docker "$SUDO_USER" 2>/dev/null; then
                warn "已加入 docker 组,需重新登录后生效(或执行: newgrp docker)"
            fi
        fi
    fi
}

#==============================================================
# .env 安全读写(兼容 Docker Compose dotenv)
#==============================================================

# 转义值:双引号包裹下,Docker Compose 需转义 \ " $ `
escape_env_value() {
    local v="$1"
    v="${v//\\/\\\\}"
    v="${v//\"/\\\"}"
    v="${v//\$/\\\$}"
    v="${v//\`/\\\`}"
    printf '%s' "$v"
}

# 安全写入 .env 行:KEY="value"(printf %s,不做 shell 展开)
write_env_line() {
    local key="$1" value="$2"
    if [[ -n "$value" ]]; then
        printf '%s="%s"\n' "$key" "$value"
    else
        printf '%s=\n' "$key"
    fi
}

# 设置 .env 变量(存在则更新,不存在则追加)
set_env_var() {
    local key="$1" value="$2"
    local envfile="$PROJECT_DIR/.env"
    touch "$envfile"

    local escaped_value
    escaped_value=$(escape_env_value "$value")
    local new_line="${key}=\"${escaped_value}\""

    if grep -q "^${key}=" "$envfile" 2>/dev/null; then
        local tmp; tmp=$(mktemp)
        KEY="$key" LINE="$new_line" awk '
            BEGIN { k = ENVIRON["KEY"]; line = ENVIRON["LINE"] }
            index($0, k "=") == 1 { print line; next }
            { print }
        ' "$envfile" > "$tmp" && mv "$tmp" "$envfile"
    else
        echo "$new_line" >> "$envfile"
    fi

    chmod 600 "$envfile" 2>/dev/null || true
    # chown 给运维用户
    local chown_user=""
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        chown_user="$SUDO_USER"
    elif [[ "$OPERATOR" != "root" ]] && [[ "$(whoami)" == "root" ]]; then
        chown_user="$OPERATOR"
    fi
    [[ -n "$chown_user" ]] && chown "$chown_user:$chown_user" "$envfile" 2>/dev/null || true
}

# 安全读取 .env 变量(不 source,防 shell 展开)
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
            if [[ "$value" =~ ^\'.*\'$ ]]; then
                value="${value:1:-1}"
            elif [[ "$value" =~ ^\".*\"$ ]]; then
                value="${value:1:-1}"
                quoted="double"
            fi
            if [[ "$quoted" == "double" ]]; then
                value="${value//\\\\/\\}"
                value="${value//\\\"/\"}"
                value="${value//\\\$/\$}"
                value="${value//\\\`/\`}"
            fi
            value="${value//$'\r'/}"
            printf '%s' "$value"
            return 0
        fi
    done < "$envfile"
    return 1
}

#==============================================================
# 工具函数
#==============================================================

# 生成随机十六进制
rand_hex() {
    if command -v openssl &>/dev/null; then
        openssl rand -hex "$1"
    else
        head -c "$1" /dev/urandom | od -A n -t x1 | tr -d ' \n'
    fi
}

# 读取用户输入(带默认值)
read_input() {
    local prompt="$1" default="$2" input
    if [[ -n "$default" ]]; then
        read -p "$(echo -e "${CYAN}[?]${NC} ${prompt} [${default}]: ")" input
        echo "${input:-$default}"
    else
        read -p "$(echo -e "${CYAN}[?]${NC} ${prompt}: ")" input
        echo "$input"
    fi
}

# 读取密码(隐藏输入,带确认,3 次重试)
read_password() {
    local prompt="$1" pwd1 pwd2 attempt
    # 关键: 本函数通过 $(read_password ...) 调用,stdout 被 $() 捕获。
    # 所有交互输出(echo/warn/read -p)必须重定向到 stderr,只有最终的
    # echo "$pwd1" 输出到 stdout。否则 echo "" 的换行符会污染返回值,
    # 导致密码变成 "\n\nqweasd2864..",write_env_line 输出跨多行,
    # cleanup_sensitive_env 的 grep -v 只删第一行,残留脏数据破坏 .env。
    for attempt in 1 2 3; do
        pwd1=""
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} ${prompt}: ")" pwd1
        if [[ -z "$pwd1" ]]; then
            read -r -p "$(echo -e "${CYAN}[?]${NC} ${prompt}(可见输入): ")" pwd1 || pwd1=""
        fi
        echo "" >&2
        if [[ -z "$pwd1" ]]; then
            warn "未捕获到密码输入(第 ${attempt}/3 次)" >&2
            continue
        fi
        # 禁止双引号和反斜杠:Docker Compose dotenv 解析器不认 \" 转义,
        # 密码含 " 会导致整个 .env 解析失败,所有 docker compose 命令全挂
        if [[ "$pwd1" == *'"'* ]] || [[ "$pwd1" == *'\\'* ]]; then
            warn "密码不能包含双引号(\")或反斜杠(\\),请更换密码" >&2
            continue
        fi
        pwd2=""
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} 确认密码: ")" pwd2
        if [[ -z "$pwd2" ]]; then
            read -r -p "$(echo -e "${CYAN}[?]${NC} 确认密码(可见输入): ")" pwd2 || pwd2=""
        fi
        echo "" >&2
        if [[ "$pwd1" != "$pwd2" ]]; then
            warn "两次输入不一致,请重新输入" >&2
            continue
        fi
        if [[ ${#pwd1} -lt 8 ]]; then
            warn "密码至少 8 位,请重新输入" >&2
            continue
        fi
        # 唯一输出到 stdout 的行:密码值(被 $() 捕获)
        printf '%s' "$pwd1"
        return 0
    done
    return 1
}

# 获取服务器 IP
get_server_ip() {
    hostname -I 2>/dev/null | awk '{print $1}' || echo "服务器IP"
}

#==============================================================
# 部署步骤
#==============================================================

# 前置检查
check_prerequisites() {
    local missing=()
    for cmd in curl tar gzip; do
        command -v "$cmd" &>/dev/null || missing+=("$cmd")
    done
    if [ ${#missing[@]} -gt 0 ]; then
        error "缺少必要命令: ${missing[*]}"
        echo "安装: sudo apt-get install -y ${missing[*]}"
        exit 1
    fi

    local min_gb=2 avail_kb avail_gb
    avail_kb=$(df -P "$PROJECT_DIR" | awk 'NR==2{print $4}')
    avail_gb=$((avail_kb / 1024 / 1024))
    if [ "$avail_gb" -lt "$min_gb" ]; then
        error "磁盘空间不足: 剩余 ${avail_gb}GB, 需要至少 ${min_gb}GB"
        exit 1
    fi
    info "磁盘空间: 剩余 ${avail_gb}GB"
}

# 安装 Docker
install_docker() {
    step "1/8 检测 Docker 环境"

    if command -v docker &>/dev/null && docker info &>/dev/null; then
        info "Docker 已安装且运行中,跳过安装"
        return 0
    fi

    info "Docker 未安装,开始安装(使用国内源)..."

    if command -v apt-get &>/dev/null; then
        info "检测到 Debian/Ubuntu..."
        apt-get update -y
        apt-get install -y ca-certificates curl gnupg lsb-release
        install -m 0755 -d /etc/apt/keyrings
        curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
        chmod a+r /etc/apt/keyrings/docker.gpg
        echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list
        apt-get update -y
        apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    elif command -v yum &>/dev/null; then
        info "检测到 CentOS/RHEL..."
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

# 配置 Docker 镜像加速
configure_docker_mirror() {
    step "2/8 配置 Docker 镜像加速器"

    local daemon_json="/etc/docker/daemon.json"

    # 检测是否已有镜像加速器配置(用 sudo cat 兼容非 root 运行场景)
    local has_mirror=false
    if [[ -f "$daemon_json" ]]; then
        if sudo cat "$daemon_json" 2>/dev/null | grep -q "registry-mirrors"; then
            has_mirror=true
        fi
    fi

    if [[ "$has_mirror" == "true" ]]; then
        info "检测到已有镜像加速器配置"
        ask "是否替换为推荐配置? [y/N]"
        read -r ans
        [[ "$ans" != "y" && "$ans" != "Y" ]] && { info "保留现有配置"; return; }
    else
        info "未检测到镜像加速器配置,将写入推荐配置"
    fi

    info "写入国内 Docker 镜像加速器..."
    sudo mkdir -p /etc/docker
    [[ -f "$daemon_json" ]] && [[ ! -f "${daemon_json}.bak" ]] && sudo cp "$daemon_json" "${daemon_json}.bak"

    sudo tee "$daemon_json" > /dev/null <<'EOF'
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
    sudo systemctl daemon-reload
    sudo systemctl restart docker
    info "镜像加速器配置完成"
}

# 配置环境变量
configure_env() {
    step "3/8 配置环境变量"

    # 重配策略:已有 .env 则复用敏感凭证,不破坏已有数据库
    if [[ -f .env ]]; then
        warn "已存在 .env 文件"
        ask "是否重新配置?(y/n)"
        read -r ans
        if [[ "$ans" != "y" && "$ans" != "Y" ]]; then
            info "保留现有 .env 文件"
            return
        fi
    fi

    info "即将生成 .env 配置文件"
    echo ""

    # 复用旧值(若 .env 已存在)
    local old_mysql_pwd="" old_redis_pwd="" old_jwt_secret="" old_db_app_user="" old_db_app_pwd="" old_backup_key=""
    if [[ -f .env ]]; then
        old_mysql_pwd=$(read_env_var "MYSQL_ROOT_PASSWORD" ".env" 2>/dev/null) || old_mysql_pwd=""
        old_redis_pwd=$(read_env_var "REDIS_PASSWORD" ".env" 2>/dev/null) || old_redis_pwd=""
        old_jwt_secret=$(read_env_var "JWT_SECRET" ".env" 2>/dev/null) || old_jwt_secret=""
        old_db_app_user=$(read_env_var "DB_APP_USERNAME" ".env" 2>/dev/null) || old_db_app_user=""
        old_db_app_pwd=$(read_env_var "DB_APP_PASSWORD" ".env" 2>/dev/null) || old_db_app_pwd=""
        old_backup_key=$(read_env_var "BACKUP_ENCRYPTION_KEY" ".env" 2>/dev/null) || old_backup_key=""
    fi

    # MySQL 密码
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

    # Redis 密码
    local redis_pwd="$old_redis_pwd"
    if [[ -n "$redis_pwd" ]]; then
        info "已复用现有 Redis 密码"
    else
        redis_pwd=$(rand_hex 16)
        info "已生成随机 Redis 密码"
    fi

    # JWT 密钥
    local jwt_secret="$old_jwt_secret"
    [[ -z "$jwt_secret" ]] && jwt_secret=$(rand_hex 32)

    # MySQL 应用专用用户
    local db_app_user="${old_db_app_user:-canteen_app}"
    local db_app_pwd="$old_db_app_pwd"
    if [[ -n "$db_app_pwd" ]]; then
        info "已复用 MySQL 应用用户 ${db_app_user}(仅 DML 权限)"
    else
        db_app_pwd=$(rand_hex 16)
        info "已创建 MySQL 应用专用用户: ${db_app_user}(仅 DML 权限)"
    fi

    # 备份加密密钥
    local backup_key="$old_backup_key"
    [[ -z "$backup_key" ]] && backup_key=$(rand_hex 32)

    # 容器降权 UID/GID
    local puid="${PUID:-$OPERATOR_UID}" pgid="${PGID:-$OPERATOR_GID}"
    # install.sh 传入的 PUID/PGID 优先
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        puid=$(id -u "$SUDO_USER" 2>/dev/null || echo 1000)
        pgid=$(id -g "$SUDO_USER" 2>/dev/null || echo 1000)
    fi
    info "容器将以 UID=${puid} GID=${pgid} 运行(非 root 降权)"

    # 超管账号
    echo ""
    info "配置超级管理员账号"
    local admin_user admin_pwd
    admin_user=$(read_input "超管登录账号" "admin")
    admin_pwd=""
    for _ in 1 2 3; do
        admin_pwd=$(read_password "超管登录密码(至少 8 位)")
        if [[ -n "$admin_pwd" && ${#admin_pwd} -ge 8 ]]; then
            break
        fi
        warn "超管密码为空或不足 8 位,请重新输入"
    done
    if [[ -z "$admin_pwd" || ${#admin_pwd} -lt 8 ]]; then
        error "超管密码多次输入无效"
        error "请重新运行部署;或部署完成后运行 canteen 菜单【重置管理员密码】"
        return 1
    fi

    # 转义所有值(双引号包裹,兼容 Docker Compose)
    local e_mysql_pwd e_db_app_user e_db_app_pwd e_redis_pwd e_jwt_secret e_backup_key e_admin_user e_admin_pwd
    e_mysql_pwd=$(escape_env_value "$mysql_pwd")
    e_db_app_user=$(escape_env_value "$db_app_user")
    e_db_app_pwd=$(escape_env_value "$db_app_pwd")
    e_redis_pwd=$(escape_env_value "$redis_pwd")
    e_jwt_secret=$(escape_env_value "$jwt_secret")
    e_backup_key=$(escape_env_value "$backup_key")
    e_admin_user=$(escape_env_value "$admin_user")
    e_admin_pwd=$(escape_env_value "$admin_pwd")

    # 写入 .env(用 write_env_line/printf,不做 shell 展开)
    {
        echo "# MySQL 数据库"
        write_env_line "MYSQL_ROOT_PASSWORD" "$e_mysql_pwd"
        echo "MYSQL_DATABASE=canteen"
        echo ""
        echo "# MySQL 应用专用用户(仅 DML 权限)"
        write_env_line "DB_APP_USERNAME" "$e_db_app_user"
        write_env_line "DB_APP_PASSWORD" "$e_db_app_pwd"
        echo ""
        echo "# Redis 密码"
        write_env_line "REDIS_PASSWORD" "$e_redis_pwd"
        echo ""
        echo "# JWT 密钥"
        write_env_line "JWT_SECRET" "$e_jwt_secret"
        echo "JWT_EXPIRATION=86400000"
        echo "JWT_EMPLOYEE_EXPIRATION=2592000000"
        echo "JWT_TERMINAL_EXPIRATION=31536000000"
        echo ""
        echo "# 备份加密密钥"
        write_env_line "BACKUP_ENCRYPTION_KEY" "$e_backup_key"
        echo ""
        echo "# 容器降权 UID/GID"
        echo "PUID=${puid}"
        echo "PGID=${pgid}"
        echo ""
        echo "# 初始超管账号(首次启动时读取,初始化后自动清理密码)"
        write_env_line "INIT_ADMIN_USERNAME" "$e_admin_user"
        write_env_line "INIT_ADMIN_PASSWORD" "$e_admin_pwd"
        echo "INIT_ADMIN_FORCE=true"
    } > .env

    chmod 600 .env
    fix_all_permissions

    echo ""
    info ".env 已生成"
    warn "请妥善保管 .env 文件,包含敏感信息!"
}

# 构建产物检测
build_artifacts() {
    local current_branch=""
    if [ -d ".git" ]; then
        current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
    fi

    local has_artifacts=false
    if [ -f "deploy/backend/app.jar" ] && [ -f "deploy/admin-web/html/index.html" ] && [ -f "deploy/h5/html/index.html" ]; then
        has_artifacts=true
    fi

    if [ "$current_branch" = "deploy" ] || [ "$has_artifacts" = true ]; then
        step "4/8 跳过构建(deploy 分支已含产物)"
        if [ "$current_branch" = "deploy" ]; then
            info "当前为 deploy 分支,产物已预构建"
        else
            info "检测到 deploy/ 目录已有产物,跳过构建"
        fi
        info "  后端:    $(du -h deploy/backend/app.jar 2>/dev/null | cut -f1 || echo '?')"
        info "  管理后台: $(du -sh deploy/admin-web/html 2>/dev/null | cut -f1 || echo '?')"
        info "  H5:      $(du -sh deploy/h5/html 2>/dev/null | cut -f1 || echo '?')"
        return 0
    fi

    step "4/8 构建业务产物(在 Docker 容器中)"
    info "拉取构建镜像..."
    docker pull maven:3.9-eclipse-temurin-25
    docker pull node:20-alpine

    info "开始构建..."
    chmod +x scripts/build.sh
    ./scripts/build.sh all
}

# 构建运行时镜像
build_runtime_image() {
    step "5/8 构建后端运行时基础镜像(仅首次)"

    if docker images | grep -q "canteen-backend-runtime"; then
        info "运行时镜像已存在,跳过"
    else
        info "构建运行时镜像..."
        docker compose build backend
        info "运行时镜像构建完成"
    fi
}

# 启动服务(分阶段)
start_services() {
    step "6/8 启动服务"

    # 端口占用检查
    local required_ports=("18080" "18081" "18082" "13306" "16379")
    local port_busy=false
    for p in "${required_ports[@]}"; do
        if ss -tlnp 2>/dev/null | grep -q ":${p} " || netstat -tlnp 2>/dev/null | grep -q ":${p} "; then
            error "端口 ${p} 已被占用"
            port_busy=true
        fi
    done
    if [[ "$port_busy" == "true" ]]; then
        echo ""
        echo "排查建议:"
        echo "  1) 查看占用: sudo ss -tlnp | grep -E '18080|18081|18082|13306|16379'"
        echo "  2) 停止旧服务: sudo ./deploy.sh stop"
        exit 1
    fi
    info "端口 18080/18081/18082/13306/16379 可用"

    mkdir -p backup uploads logs

    # 目录权限对齐容器运行 UID
    local puid=${PUID:-$OPERATOR_UID} pgid=${PGID:-$OPERATOR_GID}
    if [[ -f .env ]]; then
        local env_puid env_pgid
        env_puid=$(read_env_var "PUID" ".env" 2>/dev/null) || env_puid=""
        env_pgid=$(read_env_var "PGID" ".env" 2>/dev/null) || env_pgid=""
        puid="${env_puid:-$puid}"
        pgid="${env_pgid:-$pgid}"
    fi
    info "设置目录权限(UID=${puid}, GID=${pgid})..."
    chown -R "${puid}:${pgid}" backup uploads logs 2>/dev/null || warn "chown 失败(不影响部署,但容器内可能无法写入)"

    # 阶段 1: MySQL + Redis
    info "阶段 1/3: 启动 MySQL 和 Redis..."
    docker compose up -d mysql redis

    info "等待 MySQL 健康检查通过..."
    local mysql_wait=0
    while [[ $mysql_wait -lt 60 ]]; do
        docker compose ps mysql 2>/dev/null | grep -q "healthy" && break
        sleep 3; mysql_wait=$((mysql_wait + 3)); printf "."
    done
    echo ""
    if [[ $mysql_wait -ge 60 ]]; then
        error "MySQL 启动超时"
        exit 1
    fi

    # 阶段 2: 创建应用用户
    if [[ -f scripts/init-db-user.sh ]]; then
        info "阶段 2/3: 创建 MySQL 应用专用用户..."
        chmod +x scripts/init-db-user.sh 2>/dev/null || true
        if ! bash scripts/init-db-user.sh; then
            error "init-db-user.sh 执行失败,后端将无法连接数据库"
            exit 1
        fi
    else
        warn "scripts/init-db-user.sh 不存在,跳过应用用户创建"
    fi

    # 阶段 3: 后端 + 前端
    info "阶段 3/3: 启动后端和前端服务..."
    docker compose up -d

    info "等待后端健康检查通过..."
    local max_wait=180 waited=0
    while [[ $waited -lt $max_wait ]]; do
        docker compose ps backend | grep -q "healthy" && { info "后端已健康!"; break; }
        sleep 5; waited=$((waited + 5)); printf "."
    done
    echo ""

    if [[ $waited -ge $max_wait ]]; then
        error "后端健康检查超时(${max_wait}s),部署失败!"
        echo ""
        echo "排查建议:"
        echo "  1. 查看日志: docker compose logs --tail=100 backend"
        echo "  2. 检查状态: docker compose ps"
        echo "  3. 手动检查: curl -v http://localhost:18082/api/system/health"
        exit 1
    fi

    # 回收 Flyway 元数据表权限
    if [[ -f scripts/init-db-user.sh ]]; then
        info "回收 Flyway 元数据表写权限..."
        bash scripts/init-db-user.sh 2>/dev/null || true
    fi
}

# 开机启动
setup_autostart() {
    step "7/8 配置开机启动"

    systemctl enable docker 2>/dev/null && info "Docker 开机启动已启用" || warn "Docker 开机启动设置失败"

    local service_file="/etc/systemd/system/canteen.service"
    local docker_bin; docker_bin=$(which docker 2>/dev/null || echo "/usr/bin/docker")

    info "创建系统服务 canteen.service..."
    cat > "$service_file" <<EOF
[Unit]
Description=Enterprise Canteen System (Docker Compose)
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
IgnoreSIGPIPE=no

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable canteen 2>/dev/null && info "canteen 开机启动已启用" || warn "canteen 开机启动设置失败"
}

# 安装 canteen 命令
install_canteen_command() {
    step "8/8 安装 canteen 管理命令"

    local target="/usr/local/bin/canteen"
    chmod +x "$PROJECT_DIR/canteen.sh" 2>/dev/null || true
    chmod +x "$PROJECT_DIR/scripts/"*.sh 2>/dev/null || true

    [[ -L "$target" || -f "$target" ]] && rm -f "$target"
    ln -sf "$PROJECT_DIR/canteen.sh" "$target"
    info "已安装 canteen 命令 -> $target"
    info "现在可以在任意目录输入 canteen 打开管理面板"
}

# 校验超管已初始化
verify_admin_initialized() {
    local username="$1" envfile="$PROJECT_DIR/.env"
    [[ -n "$username" ]] || return 1

    local root_pwd db_name
    root_pwd=$(read_env_var "MYSQL_ROOT_PASSWORD" "$envfile" 2>/dev/null) || root_pwd=""
    db_name=$(read_env_var "MYSQL_DATABASE" "$envfile" 2>/dev/null) || db_name=""
    [[ -n "$root_pwd" ]] || return 1
    db_name="${db_name:-canteen}"

    local i=0 state=""
    while [[ $i -lt 60 ]]; do
        state=$(docker inspect -f '{{.State.Health.Status}}' canteen-backend 2>/dev/null || echo "missing")
        [[ "$state" == "healthy" ]] && break
        sleep 3; i=$((i + 3))
    done
    [[ "$state" == "healthy" ]] || { warn "backend 未 healthy(${state})"; return 1; }

    local esc_user count
    esc_user="${username//\'/\'\'}"
    count=$(docker exec -i canteen-mysql mysql -uroot -p"${root_pwd}" -s -N -e \
        "SELECT COUNT(*) FROM ${db_name}.admin WHERE username='${esc_user}' AND role=1;" 2>/dev/null || echo "0")
    [[ "$count" -ge 1 ]] 2>/dev/null
}

# 清理 .env 临时敏感变量
cleanup_sensitive_env() {
    local envfile="$PROJECT_DIR/.env"
    [[ -f "$envfile" ]] || return 0

    info "清理 .env 中的临时敏感变量..."
    local tmp; tmp=$(mktemp)
    # 删除 INIT_ADMIN_PASSWORD 和 INIT_ADMIN_FORCE 相关行
    # 关键: 密码值可能因 read_password 的 echo "" 污染而跨多行,
    # grep -v "^INIT_ADMIN_PASSWORD=" 只能删第一行,残留的密码碎片行需要额外清理。
    # 用 awk 状态机:遇到 INIT_ADMIN_PASSWORD= 开头的行开始跳过,直到遇到下一个合法 KEY= 行
    awk '
        BEGIN { skip = 0 }
        # 合法 KEY= 行(字母/下划线开头,含=):结束跳过
        /^[A-Za-z_][A-Za-z0-9_]*=/ { skip = 0 }
        # INIT_ADMIN_PASSWORD 行:开始跳过(密码值可能跨行)
        /^INIT_ADMIN_PASSWORD=/ { skip = 1; next }
        # INIT_ADMIN_FORCE 行:直接跳过
        /^INIT_ADMIN_FORCE=/ { next }
        # 跳过模式中:如果是空行或不像合法KEY=,可能是密码残留,跳过
        skip == 1 { next }
        { print }
    ' "$envfile" > "$tmp" 2>/dev/null

    if [[ -s "$tmp" ]]; then
        cp "$envfile" "${envfile}.bak" 2>/dev/null
        mv "$tmp" "$envfile"
        chmod 600 "$envfile"
        # chown 给运维用户
        local chown_user=""
        if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
            chown_user="$SUDO_USER"
        elif [[ "$OPERATOR" != "root" ]] && [[ "$(whoami)" == "root" ]]; then
            chown_user="$OPERATOR"
        fi
        [[ -n "$chown_user" ]] && chown "$chown_user:$chown_user" "$envfile" 2>/dev/null || true
        info "已清理 INIT_ADMIN_PASSWORD 和 INIT_ADMIN_FORCE"
    else
        rm -f "$tmp"
        warn "清理 .env 失败,请手动删除 INIT_ADMIN_PASSWORD 行"
    fi
}

# 部署验证与摘要
verify_and_summary() {
    echo ""
    docker compose ps
    echo ""

    info "健康检查..."
    if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
        info "后端 API: 正常"
    else
        warn "后端 API: 未就绪"
    fi
    for svc in "admin-web:18080" "h5:18081"; do
        name="${svc%%:*}"; port="${svc##*:}"
        code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/" 2>/dev/null || echo "000")
        [[ "$code" == "200" ]] && info "${name} (${port}): 正常" || warn "${name} (${port}): ${code}"
    done

    local ip; ip=$(get_server_ip)

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

    if [[ -f .env ]]; then
        local admin_user
        admin_user=$(read_env_var "INIT_ADMIN_USERNAME" ".env" 2>/dev/null) || admin_user=""
        if [[ -n "$admin_user" ]]; then
            echo "  超管账号: ${admin_user} (密码为你刚才设置的)"
        else
            echo "  超管账号: 请使用 canteen 命令重置密码"
        fi
    fi

    echo ""
    echo "  常用命令(任意目录直接输入):"
    echo "    canteen              # 打开管理面板"
    echo "    canteen status       # 查看服务状态"
    echo "    canteen upgrade      # 安全升级"
    echo "    canteen backup       # 手动备份"
    echo "    canteen logs backend # 查看后端日志"
    echo ""
    warn "请登录管理后台确认超管账号已正确初始化"
}

#==============================================================
# 子命令: deploy
#==============================================================
cmd_deploy() {
    echo ""
    echo "=========================================="
    echo "  企业智慧食堂系统 - 部署向导 V2"
    echo "=========================================="
    echo ""

    check_prerequisites

    # root 权限检查(安装 Docker 需要)
    if [[ $EUID -ne 0 ]] && [[ "$FROM_INSTALL" != "true" ]]; then
        if ! command -v docker &>/dev/null; then
            error "安装 Docker 需要 root 权限,请使用 sudo 运行"
            echo "  sudo ./deploy.sh"
            exit 1
        fi
    fi

    # 部署前:权限修正
    fix_all_permissions

    install_docker
    configure_docker_mirror
    configure_env
    build_artifacts
    build_runtime_image
    start_services
    setup_autostart
    install_canteen_command

    # 部署后:权限修正
    fix_all_permissions

    # 清理 .env 临时变量(先校验超管已落库)
    local admin_user=""
    admin_user=$(read_env_var "INIT_ADMIN_USERNAME" "$PROJECT_DIR/.env" 2>/dev/null) || admin_user=""
    if [[ -n "$admin_user" ]] && verify_admin_initialized "$admin_user"; then
        cleanup_sensitive_env
    else
        warn "未能确认超管已初始化,但仍清理 .env 中的临时密码(避免明文残留)"
        warn "请排查后端日志: docker compose logs --tail=100 backend"
        warn "后端就绪后执行: docker compose up -d --no-deps backend"
        warn "或运行 canteen -> 重置管理员密码"
        cleanup_sensitive_env
    fi

    verify_and_summary
}

#==============================================================
# 子命令: status
#==============================================================
cmd_status() {
    step "服务状态"
    docker compose ps 2>/dev/null || { error "Docker Compose 未运行"; return; }
    echo ""
    info "健康检查..."
    if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
        info "后端 API: 正常"
    else
        warn "后端 API: 未就绪"
    fi
    for svc in "admin-web:18080" "h5:18081"; do
        name="${svc%%:*}"; port="${svc##*:}"
        code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/" 2>/dev/null || echo "000")
        [[ "$code" == "200" ]] && info "${name} (${port}): 正常" || warn "${name} (${port}): ${code}"
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
    echo ""

    local username password
    username=$(read_input "超管账号名" "admin")
    password=$(read_password "输入新密码(至少 8 位)")
    if [[ -z "$password" || ${#password} -lt 8 ]]; then
        error "未能获取有效密码,重置已取消"
        return 1
    fi

    # 写入 .env
    set_env_var "INIT_ADMIN_USERNAME" "$username"
    set_env_var "INIT_ADMIN_PASSWORD" "$password"
    set_env_var "INIT_ADMIN_FORCE" "true"

    info "正在重建后端服务..."
    docker compose up -d --no-deps backend

    info "等待后端启动..."
    local ok=false
    for i in $(seq 1 30); do
        curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1 && { ok=true; break; }
        sleep 2; printf "."
    done
    echo ""

    if [[ "$ok" == "true" ]]; then
        info "清理临时配置..."
        local envfile="$PROJECT_DIR/.env" tmp
        tmp=$(mktemp)
        if grep -v "^INIT_ADMIN_FORCE=" "$envfile" 2>/dev/null | grep -v "^INIT_ADMIN_PASSWORD=" > "$tmp" && [[ -s "$tmp" ]]; then
            cp "$envfile" "${envfile}.bak" 2>/dev/null
            mv "$tmp" "$envfile"
            chmod 600 "$envfile"
            fix_all_permissions
        else
            rm -f "$tmp"
            warn "清理 .env 失败"
        fi
        info "密码重置成功!"
        echo "  超管账号: ${username}"
        echo "  请使用新密码登录管理后台"
    else
        error "后端启动超时,请查看日志: docker compose logs backend"
    fi
}

#==============================================================
# 主入口
#==============================================================
FROM_INSTALL="false"
if [[ "$1" == "--from-install" ]]; then
    FROM_INSTALL="true"
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
        shift; cmd_logs "$@"
        ;;
    stop|down)
        cmd_stop
        ;;
    restart)
        shift; cmd_restart "$@"
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
        echo "一键安装(多加速器自动切换):"
        echo "  for p in \"https://gh-proxy.com/https/\" \"https://ghfast.top/https/\" \"https://mirror.ghproxy.com/https/\" \"https://\"; do"
        echo "    curl -fsSL --connect-timeout 8 --max-time 60 \"\${p}raw.githubusercontent.com/MingTu01/canteen/main/install.sh\" -o /tmp/canteen-install.sh && [ -s /tmp/canteen-install.sh ] && break"
        echo "  done && sudo bash /tmp/canteen-install.sh"
        ;;
    *)
        error "未知命令: $COMMAND"
        echo "运行 ./deploy.sh help 查看可用命令"
        exit 1
        ;;
esac
