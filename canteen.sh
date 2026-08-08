#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 服务器管理面板 V2
#==============================================================
# 在服务器上输入 `canteen` 即可弹出此菜单,所有操作一键完成。
#
# 安装为系统命令:
#   sudo ./canteen.sh install      # 创建软链接到 /usr/local/bin/canteen
#   sudo canteen uninstall         # 移除软链接
#
# 也可直接运行:
#   ./canteen.sh                   # 弹出菜单
#   ./canteen.sh status            # 直接执行子命令(非交互)
#==============================================================

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
cd "$PROJECT_DIR" || { echo "无法进入项目目录 $PROJECT_DIR"; exit 1; }

#==============================================================
# 颜色
#==============================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${GREEN}[信息]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }

#==============================================================
# 权限自愈 —— 检测并自动修复常见权限问题
#==============================================================
# 比原版 ensure_runtime_dirs 更强:不仅检测,还尝试自动修复
self_heal_permissions() {
    local fixed=false

    # 1. 运行时目录检测和创建
    for d in backup uploads logs; do
        if [[ ! -d "$PROJECT_DIR/$d" ]]; then
            mkdir -p "$PROJECT_DIR/$d" 2>/dev/null || {
                # 目录创建失败,尝试 sudo
                sudo mkdir -p "$PROJECT_DIR/$d" 2>/dev/null && \
                    sudo chown "$(whoami):$(whoami)" "$PROJECT_DIR/$d" 2>/dev/null
            }
        fi
        # 检查可写性
        if [[ -d "$PROJECT_DIR/$d" ]] && [[ ! -w "$PROJECT_DIR/$d" ]]; then
            # 尝试自动修复
            if sudo chown -R "$(whoami):$(whoami)" "$PROJECT_DIR/$d" 2>/dev/null; then
                warn "已自动修复 $d/ 目录权限"
                fixed=true
            else
                echo -e "${RED}[错误]${NC} $PROJECT_DIR/$d 不可写且无法自动修复"
                echo "  请执行: sudo chown -R \$(whoami):\$(whoami) $PROJECT_DIR"
                return 1
            fi
        fi
    done

    # 2. .env 文件权限检测
    if [[ -f "$PROJECT_DIR/.env" ]] && [[ ! -w "$PROJECT_DIR/.env" ]]; then
        if sudo chown "$(whoami):$(whoami)" "$PROJECT_DIR/.env" 2>/dev/null; then
            warn "已自动修复 .env 文件权限"
            chmod 600 "$PROJECT_DIR/.env" 2>/dev/null
            fixed=true
        else
            echo -e "${YELLOW}[警告]${NC} .env 不可写,部分操作(重置密码等)可能失败"
            echo "  修复: sudo chown \$(whoami):\$(whoami) $PROJECT_DIR/.env"
        fi
    fi

    # 3. 脚本可执行权限
    local need_chmod=false
    for f in "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh; do
        [[ -f "$f" ]] || continue
        [[ -x "$f" ]] || { need_chmod=true; break; }
    done
    if [[ "$need_chmod" == "true" ]]; then
        chmod +x "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh 2>/dev/null || true
        fixed=true
    fi

    # 4. git safe.directory
    if [[ -d "$PROJECT_DIR/.git" ]]; then
        git config --global --add safe.directory "$PROJECT_DIR" 2>/dev/null || true
    fi

    # 5. Docker 组检查
    if command -v docker &>/dev/null; then
        if [[ "$(whoami)" != "root" ]] && ! id -nG "$(whoami)" 2>/dev/null | grep -qw "docker"; then
            echo -e "${YELLOW}[提示]${NC} 当前用户不在 docker 组中"
            echo "  修复: sudo usermod -aG docker \$(whoami) && newgrp docker"
            echo "  或使用 sudo 运行: sudo canteen"
        fi
    fi

    [[ "$fixed" == "true" ]] && info "权限自愈完成"
    return 0
}
self_heal_permissions

#==============================================================
# 共享工具函数
#==============================================================

# 转义 .env 值(双引号包裹,Docker Compose 兼容)
escape_env_value() {
    local v="$1"
    v="${v//\\/\\\\}"
    v="${v//\"/\\\"}"
    v="${v//\$/\\\$}"
    v="${v//\`/\\\`}"
    printf '%s' "$v"
}

# 安全读取 .env 变量
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

# 安全写入 .env 变量
set_env_var() {
    local key="$1" value="$2"
    local envfile="$PROJECT_DIR/.env"
    touch "$envfile" 2>/dev/null || return 1

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
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        chown "$SUDO_USER:$SUDO_USER" "$envfile" 2>/dev/null || true
    fi
}

# 获取系统版本号
get_version() {
    if [ -f "$PROJECT_DIR/VERSIONS.json" ]; then
        python3 -c "import json; print(json.load(open('$PROJECT_DIR/VERSIONS.json'))['system']['version'])" 2>/dev/null || \
        grep -A1 '"system"' "$PROJECT_DIR/VERSIONS.json" 2>/dev/null | grep '"version"' \
            | sed 's/.*"\([0-9.]*\)".*/\1/' || echo "unknown"
    else
        echo "unknown"
    fi
}

# 获取指定模块版本号
get_module_version() {
    local module="$1"
    local versions_file="$PROJECT_DIR/VERSIONS.json"
    if [ ! -f "$versions_file" ]; then
        echo "unknown"
        return
    fi
    python3 -c "import json; print(json.load(open('$versions_file')).get('$module',{}).get('version','unknown'))" 2>/dev/null || \
    echo "unknown"
}

# 获取服务状态摘要(一行)
get_status_line() {
    if ! command -v docker &>/dev/null; then
        echo -e "${RED}Docker 未安装${NC}"
        return
    fi
    local running total=5
    running=$(docker ps --filter "name=canteen-" --format '{{.Names}}' 2>/dev/null | wc -l)
    if [ "$running" -ge 5 ] 2>/dev/null; then
        echo -e "${GREEN}全部运行中 (${running}/${total})${NC}"
    elif [ "$running" -gt 0 ] 2>/dev/null; then
        echo -e "${YELLOW}部分运行 (${running}/${total})${NC}"
    else
        echo -e "${RED}未运行${NC}"
    fi
}

# 获取当前 git 分支名
get_current_branch() {
    if [ ! -d "$PROJECT_DIR/.git" ]; then
        echo "非Git"
        return
    fi
    local branch
    branch=$(git -C "$PROJECT_DIR" branch --show-current 2>/dev/null || echo "")
    if [ -z "$branch" ]; then
        local commit
        commit=$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "?")
        echo "detached(${commit})"
    else
        echo "$branch"
    fi
}

# 等待用户按回车继续
pause() {
    echo ""
    read -p "$(echo -e "${CYAN}按回车返回菜单...${NC}")"
}

# 确认操作
confirm() {
    local msg="$1"
    read -p "$(echo -e "${CYAN}[?]${NC} ${msg} [y/N]: ")" ans
    [ "$ans" = "y" ] || [ "$ans" = "Y" ]
}

# 获取容器运行时间(格式化)
get_container_uptime() {
    local container="$1"
    local started
    started=$(docker inspect -f '{{.State.StartedAt}}' "$container" 2>/dev/null || echo "")
    [[ -z "$started" ]] && echo "-" && return

    # 计算运行时间
    local now_epoch started_epoch
    now_epoch=$(date +%s)
    started_epoch=$(date -d "$started" +%s 2>/dev/null || echo "$now_epoch")
    local diff=$((now_epoch - started_epoch))

    if [ "$diff" -lt 60 ]; then
        echo "${diff}秒"
    elif [ "$diff" -lt 3600 ]; then
        echo "$((diff / 60))分钟"
    elif [ "$diff" -lt 86400 ]; then
        echo "$((diff / 3600))小时$(((diff % 3600) / 60))分"
    else
        echo "$((diff / 86400))天$(((diff % 86400) / 3600))小时"
    fi
}

#==============================================================
# 菜单功能函数
#==============================================================

# 1. 查看服务状态(增强:含容器资源使用和运行时间)
menu_status() {
    echo ""
    echo -e "${BLUE}========== 服务状态 ==========${NC}"
    echo ""
    docker compose ps 2>/dev/null || {
        error "Docker Compose 未运行"
        pause
        return
    }
    echo ""
    info "健康检查..."
    # 后端
    if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
        info "后端 API (18082): 正常"
    else
        warn "后端 API (18082): 未就绪"
    fi
    for svc in "管理后台:18080" "H5订餐:18081"; do
        name="${svc%%:*}"
        port="${svc##*:}"
        code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/" 2>/dev/null || echo "000")
        if [ "$code" = "200" ]; then
            info "${name} (${port}): 正常"
        else
            warn "${name} (${port}): ${code}"
        fi
    done

    # 容器运行时间和资源使用
    echo ""
    echo -e "${BLUE}---------- 容器详情 ----------${NC}"
    local containers=("canteen-backend" "canteen-admin" "canteen-h5" "canteen-mysql" "canteen-redis")
    printf "  %-20s %-12s %-10s %-10s\n" "容器" "运行时间" "CPU" "内存"
    printf "  %-20s %-12s %-10s %-10s\n" "----" "--------" "---" "----"
    for c in "${containers[@]}"; do
        local status uptime cpu mem
        status=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null || echo "missing")
        if [ "$status" = "running" ]; then
            uptime=$(get_container_uptime "$c")
            # docker stats --no-stream 获取资源使用
            local stats
            stats=$(docker stats --no-stream --format "{{.CPUPerc}}|{{.MemUsage}}" "$c" 2>/dev/null || echo "-|-")
            cpu=$(echo "$stats" | cut -d'|' -f1)
            mem=$(echo "$stats" | cut -d'|' -f2 | cut -d'/' -f1 | xargs)
            printf "  %-20s %-12s %-10s %-10s\n" "$c" "$uptime" "$cpu" "$mem"
        else
            printf "  %-20s %-12s %-10s %-10s\n" "$c" "未运行" "-" "-"
        fi
    done

    # 磁盘
    echo ""
    local disk_usage disk_total disk_avail
    disk_usage=$(df -h "$PROJECT_DIR" | awk 'NR==2{print $5}')
    disk_total=$(df -h "$PROJECT_DIR" | awk 'NR==2{print $2}')
    disk_avail=$(df -h "$PROJECT_DIR" | awk 'NR==2{print $4}')
    info "磁盘: 总计 ${disk_total}, 已用 ${disk_usage}, 可用 ${disk_avail}"

    # Docker 数据大小
    if command -v docker &>/dev/null; then
        local docker_size
        docker_size=$(docker system df --format "{{.Size}}" 2>/dev/null | head -1 || echo "?")
        info "Docker 镜像占用: ${docker_size}"
    fi

    pause
}

# 显示升级步骤说明
show_upgrade_steps() {
    local scope_desc="$1"
    local cur_branch
    cur_branch=$(get_current_branch)
    echo "  此操作将:"
    echo "    1. 创建升级前快照(数据库 + 产物 + 代码版本)"
    if [ "$cur_branch" = "deploy" ]; then
        echo "    2. 拉取最新产物(git pull origin deploy,无需构建)"
        echo "    3. 重启${scope_desc}服务"
        echo "    4. 健康检查"
        echo "    5. 如失败将自动回退到升级前状态"
        echo ""
        echo -e "  ${YELLOW}当前分支: deploy(免构建模式,秒级更新)${NC}"
    else
        echo "    2. 拉取最新代码(git pull)"
        echo "    3. 重新构建${scope_desc}产物"
        echo "    4. 重启${scope_desc}服务"
        echo "    5. 健康检查"
        echo "    6. 如失败将自动回退到升级前状态"
        echo ""
        echo -e "  ${YELLOW}当前分支: ${cur_branch}(需本地构建)${NC}"
    fi
}

# 2. 升级全部
menu_upgrade_all() {
    echo ""
    echo -e "${BLUE}========== 升级全部 ==========${NC}"
    show_upgrade_steps "全部"
    echo ""
    if confirm "确认升级全部?"; then
        chmod +x "$PROJECT_DIR/scripts/upgrade.sh"
        "$PROJECT_DIR/scripts/upgrade.sh" all
        pause
    else
        info "已取消"
    fi
}

# 3. 仅升级后端
menu_upgrade_backend() {
    echo ""
    echo -e "${BLUE}========== 升级后端 ==========${NC}"
    show_upgrade_steps "后端"
    echo ""
    if confirm "确认升级后端?"; then
        chmod +x "$PROJECT_DIR/scripts/upgrade.sh"
        "$PROJECT_DIR/scripts/upgrade.sh" backend
        pause
    else
        info "已取消"
    fi
}

# 4. 仅升级前端
menu_upgrade_frontend() {
    echo ""
    echo -e "${BLUE}========== 升级前端 ==========${NC}"
    show_upgrade_steps "前端(admin-web + h5)"
    echo ""
    if confirm "确认升级前端?"; then
        chmod +x "$PROJECT_DIR/scripts/upgrade.sh"
        "$PROJECT_DIR/scripts/upgrade.sh" frontend
        pause
    else
        info "已取消"
    fi
}

# 5. 手动备份
menu_backup() {
    echo ""
    echo -e "${BLUE}========== 手动备份 ==========${NC}"
    echo "  将创建完整快照(数据库 + 产物 + 代码版本)"
    echo ""
    read -p "$(echo -e "${CYAN}[?]${NC} 备份说明(可选): ")" desc
    desc="${desc:-手动备份}"
    chmod +x "$PROJECT_DIR/scripts/snapshot.sh"
    "$PROJECT_DIR/scripts/snapshot.sh" create "$desc"
    pause
}

# 6. 恢复备份
menu_restore() {
    echo ""
    echo -e "${BLUE}========== 恢复备份 ==========${NC}"
    echo ""
    chmod +x "$PROJECT_DIR/scripts/snapshot.sh"

    "$PROJECT_DIR/scripts/snapshot.sh" list

    local latest
    latest=$("$PROJECT_DIR/scripts/snapshot.sh" latest 2>/dev/null)
    if [ -z "$latest" ]; then
        info "暂无快照可恢复"
        pause
        return
    fi

    echo ""
    read -p "$(echo -e "${CYAN}[?]${NC} 输入要恢复的快照序号或 ID(留空取消): ")" input
    if [ -z "$input" ]; then
        info "已取消"
        pause
        return
    fi

    "$PROJECT_DIR/scripts/snapshot.sh" restore "$input"
    pause
}

# 7. 查看快照列表
menu_snapshots() {
    echo ""
    chmod +x "$PROJECT_DIR/scripts/snapshot.sh"
    "$PROJECT_DIR/scripts/snapshot.sh" list
    pause
}

# 8. 重置管理员密码
menu_reset_admin() {
    echo ""
    echo -e "${BLUE}========== 重置管理员密码 ==========${NC}"
    echo "  此操作将重置超管账号密码并重启后端(同时清除登录锁定)。"
    echo "  - 若账号已存在且为超管:更新密码"
    echo "  - 若账号不存在:强制创建新超管"
    echo ""

    read -p "$(echo -e "${CYAN}[?]${NC} 超管账号名 [admin]: ")" username
    username="${username:-admin}"

    # 读取密码(隐藏输入,带确认)
    local pwd1 pwd2
    while true; do
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} 新密码(至少 8 位): ")" pwd1
        if [[ -z "$pwd1" ]]; then
            read -r -p "$(echo -e "${CYAN}[?]${NC} 新密码(可见输入): ")" pwd1 || pwd1=""
        fi
        echo ""
        # 禁止双引号和反斜杠:Docker Compose dotenv 不认 \" 转义
        if [[ "$pwd1" == *'"'* ]] || [[ "$pwd1" == *'\\'* ]]; then
            warn "密码不能包含双引号(\")或反斜杠(\\),请更换密码"
            continue
        fi
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} 确认密码: ")" pwd2
        if [[ -z "$pwd2" ]]; then
            read -r -p "$(echo -e "${CYAN}[?]${NC} 确认密码(可见输入): ")" pwd2 || pwd2=""
        fi
        echo ""
        if [ "$pwd1" != "$pwd2" ]; then
            warn "两次输入不一致,请重新输入"
            continue
        fi
        if [ ${#pwd1} -lt 8 ]; then
            warn "密码至少 8 位,请重新输入"
            continue
        fi
        break
    done

    echo ""
    if ! confirm "确认重置超管 '${username}' 的密码?"; then
        info "已取消"
        pause
        return
    fi

    local envfile="$PROJECT_DIR/.env"

    # 检查 .env 可写(自动修复权限)
    if ! touch "$envfile" 2>/dev/null; then
        if [[ -f "$envfile" ]] && [[ "$(id -u)" != "0" ]]; then
            local env_owner
            env_owner=$(stat -c '%U' "$envfile" 2>/dev/null || echo "")
            if [[ "$env_owner" == "root" ]]; then
                info "检测到 .env 属于 root,尝试修复权限..."
                if sudo chown "$(whoami):$(whoami)" "$envfile" 2>/dev/null; then
                    info "权限已修复"
                    chmod 600 "$envfile" 2>/dev/null
                else
                    error "无法修改 .env 权限"
                    warn "请手动执行: sudo chown \$(whoami):\$(whoami) $envfile"
                    pause
                    return
                fi
            else
                error "无法写入 .env: $envfile"
                warn "所有者: ${env_owner:-unknown}, 当前用户: $(whoami)"
                pause
                return
            fi
        else
            error "无法写入 .env: $envfile"
            pause
            return
        fi
    fi

    # 写入 INIT_ADMIN_* 环境变量(使用共享的 set_env_var)
    info "写入配置..."
    set_env_var "INIT_ADMIN_USERNAME" "$username"
    set_env_var "INIT_ADMIN_PASSWORD" "$pwd1"
    set_env_var "INIT_ADMIN_FORCE" "true"

    # 重建后端(up -d 而非 restart:restart 不重读 .env)
    info "正在重建后端服务(读取新配置 + 清除登录锁定)..."
    if ! docker compose up -d --no-deps backend 2>/dev/null; then
        error "后端重建失败,请检查 Docker 服务状态"
        pause
        return
    fi

    # 等待后端健康
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

    if [ "$ok" = false ]; then
        error "后端启动超时,密码可能未生效"
        warn "请查看日志: docker compose logs backend"
        pause
        return
    fi

    # 清理 .env 中的敏感变量
    info "清理临时配置..."
    local tmp
    tmp=$(mktemp)
    # 用 awk 状态机清理:密码值可能跨多行,grep -v 只删第一行会残留脏数据
    awk '
        BEGIN { skip = 0 }
        /^[A-Za-z_][A-Za-z0-9_]*=/ { skip = 0 }
        /^INIT_ADMIN_PASSWORD=/ { skip = 1; next }
        /^INIT_ADMIN_FORCE=/ { next }
        skip == 1 { next }
        { print }
    ' "$envfile" > "$tmp" 2>/dev/null
    if [ -s "$tmp" ]; then
        cp "$envfile" "${envfile}.bak" 2>/dev/null
        chmod 600 "${envfile}.bak" 2>/dev/null || true
        mv "$tmp" "$envfile"
        chmod 600 "$envfile" 2>/dev/null || true
        if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
            chown "$SUDO_USER:$SUDO_USER" "$envfile" 2>/dev/null || true
        fi
    else
        rm -f "$tmp"
        warn "清理 .env 失败,原文件未修改"
    fi

    echo ""
    info "密码重置成功!"
    echo "  超管账号: ${username}"
    echo "  请使用新密码登录管理后台"
    echo ""
    pause
}

# 9. 查看日志
menu_logs() {
    echo ""
    echo -e "${BLUE}========== 查看日志 ==========${NC}"
    echo "  1) 后端 (backend)"
    echo "  2) 管理后台 (admin-web)"
    echo "  3) H5 订餐端 (h5)"
    echo "  4) MySQL"
    echo "  5) Redis"
    echo "  0) 返回"
    echo ""
    read -p "$(echo -e "${CYAN}[?]${NC} 选择 [0-5]: ")" log_choice

    local svc=""
    case "$log_choice" in
        1) svc="backend" ;;
        2) svc="admin-web" ;;
        3) svc="h5" ;;
        4) svc="mysql" ;;
        5) svc="redis" ;;
        0) return ;;
        *) warn "无效选择"; return ;;
    esac

    echo ""
    info "查看 ${svc} 日志(最近 200 行)..."
    echo ""
    docker compose logs --tail=200 "$svc" 2>&1
    echo ""
    info "是否持续跟踪日志?(Ctrl+C 退出)"
    if confirm "跟踪日志?"; then
        docker compose logs -f "$svc" 2>&1
    fi
    pause
}

# 10. 重启服务
menu_restart() {
    echo ""
    echo -e "${BLUE}========== 重启服务 ==========${NC}"
    echo "  1) 重启全部"
    echo "  2) 仅重启后端"
    echo "  3) 仅重启前端(admin-web + h5)"
    echo "  0) 返回"
    echo ""
    read -p "$(echo -e "${CYAN}[?]${NC} 选择 [0-3]: ")" choice

    local svc=""
    case "$choice" in
        1) svc="all" ;;
        2) svc="backend" ;;
        3) svc="frontend" ;;
        0) return ;;
        *) warn "无效选择"; return ;;
    esac

    echo ""
    if confirm "确认重启?"; then
        local ok=true err_out
        case "$svc" in
            all)
                err_out=$(docker compose up -d 2>&1) || { ok=false; echo "$err_out"; } ;;
            backend)
                err_out=$(docker compose up -d --no-deps backend 2>&1) || { ok=false; echo "$err_out"; } ;;
            frontend)
                err_out=$(docker compose up -d --no-deps admin-web h5 2>&1) || { ok=false; echo "$err_out"; } ;;
        esac
        if [ "$ok" = true ]; then
            info "已重启"
        else
            echo ""
            error "重启失败,错误信息如上"
            echo "    完整日志: docker compose logs"
        fi
    else
        info "已取消"
    fi
    pause
}

# 11. 停止服务
menu_stop() {
    echo ""
    echo -e "${BLUE}========== 停止服务 ==========${NC}"
    warn "此操作将停止所有服务,用户将无法访问系统"
    echo ""
    if confirm "确认停止所有服务?"; then
        docker compose down
        info "所有服务已停止"
    else
        info "已取消"
    fi
    pause
}

# 12. 修复 canteen 系统命令
menu_install() {
    echo ""
    echo -e "${BLUE}========== 修复 canteen 系统命令 ==========${NC}"
    echo "  (首次部署已自动安装,此选项用于修复或重新安装)"
    echo ""
    local target="/usr/local/bin/canteen"

    if [ -L "$target" ] || [ -f "$target" ]; then
        info "canteen 命令已安装:"
        ls -la "$target"
        if confirm "是否重新安装?"; then
            rm -f "$target"
        else
            pause
            return
        fi
    fi

    if [ "$EUID" -ne 0 ]; then
        warn "安装系统命令需要 root 权限"
        warn "请运行: sudo canteen install  或  sudo ./canteen.sh install"
        pause
        return
    fi

    ln -sf "$PROJECT_DIR/canteen.sh" "$target"
    chmod +x "$PROJECT_DIR/canteen.sh"
    info "安装完成!"
    echo ""
    echo "  现在可以在任何目录直接输入 canteen 打开管理面板"
    echo "  软链接: $target -> $PROJECT_DIR/canteen.sh"
    echo ""
    echo "  卸载: sudo canteen uninstall"
    pause
}

# 卸载 canteen 系统命令
menu_uninstall() {
    local target="/usr/local/bin/canteen"
    if [ -L "$target" ] || [ -f "$target" ]; then
        rm -f "$target"
        info "已卸载 canteen 命令"
    else
        info "canteen 命令未安装"
    fi
}

# 13. 查看版本详情与更新日志
menu_versions() {
    echo ""
    echo -e "${BLUE}========== 版本详情 ==========${NC}"
    echo ""
    local versions_file="$PROJECT_DIR/VERSIONS.json"
    if [ ! -f "$versions_file" ]; then
        warn "VERSIONS.json 不存在"
        pause
        return
    fi

    python3 -c "
import json
with open('$versions_file', encoding='utf-8') as f:
    data = json.load(f)
order = ['system', 'backend', 'admin-web', 'h5', 'terminal']
names = {'system': '系统整体', 'backend': '后端服务', 'admin-web': '管理后台', 'h5': 'H5订餐端', 'terminal': 'X86终端'}
for k in order:
    if k in data:
        v = data[k]
        print(f\"  【{names.get(k, k)}】 v{v.get('version', 'unknown')}\")
        print(f\"    来源: {v.get('source', '-')}\")
        cl = v.get('changelog', '')
        if cl:
            # 只显示最新一条更新日志(第一个 / 分隔)
            latest = cl.split('/')[0].strip()
            print(f\"    更新: {latest}\")
        print()
" 2>/dev/null || {
        echo "  后端服务:    v$(get_module_version backend)"
        echo "  管理后台:    v$(get_module_version admin-web)"
        echo "  H5订餐端:    v$(get_module_version h5)"
        echo "  X86终端:     v$(get_module_version terminal)"
        echo "  系统版本:    v$(get_module_version system)"
        echo ""
        warn "(python3 不可用,仅显示版本号)"
    }

    if [ -d "$PROJECT_DIR/.git" ]; then
        echo -e "${BLUE}---------- 最近代码更新 ----------${NC}"
        git -C "$PROJECT_DIR" log --oneline -10 --pretty=format:"  %h %s (%ci)" 2>/dev/null || echo "  (无法读取 git 日志)"
        echo ""
    fi
    echo ""
    pause
}

#==============================================================
# 14. 系统诊断(新增)
#==============================================================
menu_diagnostics() {
    echo ""
    echo -e "${BLUE}========== 系统诊断 ==========${NC}"
    echo ""

    # 操作系统信息
    echo -e "${BOLD}【系统信息】${NC}"
    if [ -f /etc/os-release ]; then
        local os_name os_version
        os_name=$(grep '^PRETTY_NAME=' /etc/os-release 2>/dev/null | cut -d'"' -f2 || echo "unknown")
        info "操作系统: ${os_name}"
    fi
    local kernel arch
    kernel=$(uname -r 2>/dev/null || echo "?")
    arch=$(uname -m 2>/dev/null || echo "?")
    info "内核: ${kernel} (${arch})"

    # CPU 和内存
    local cpu_cores mem_total mem_avail
    cpu_cores=$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo 2>/dev/null || echo "?")
    mem_total=$(free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo "?")
    mem_avail=$(free -h 2>/dev/null | awk '/^Mem:/{print $7}' || echo "?")
    info "CPU 核心: ${cpu_cores}"
    info "内存: 总计 ${mem_total}, 可用 ${mem_avail}"

    # 磁盘
    echo ""
    echo -e "${BOLD}【磁盘】${NC}"
    df -h "$PROJECT_DIR" 2>/dev/null | awk 'NR==1{printf "  %-30s %-10s %-10s %-10s %s\n","挂载点","总计","已用","可用","使用率"} NR==2{printf "  %-30s %-10s %-10s %-10s %s\n",$6,$2,$3,$4,$5}'

    # Docker 信息
    echo ""
    echo -e "${BOLD}【Docker】${NC}"
    if command -v docker &>/dev/null; then
        info "Docker 版本: $(docker --version 2>/dev/null)"
        info "Compose 版本: $(docker compose version 2>/dev/null | head -1 || echo '未知')"
        echo ""
        docker system df 2>/dev/null | awk 'NR>0{printf "  %s\n",$0}'
    else
        warn "Docker 未安装"
    fi

    # 服务状态快速检查
    echo ""
    echo -e "${BOLD}【服务快速检查】${NC}"
    if command -v docker &>/dev/null; then
        local containers=("canteen-backend:18082" "canteen-admin:18080" "canteen-h5:18081" "canteen-mysql:13306" "canteen-redis:16379")
        for item in "${containers[@]}"; do
            local name="${item%%:*}" port="${item##*:}"
            local status
            status=$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || echo "missing")
            if [ "$status" = "running" ]; then
                local health
                health=$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo "n/a")
                if [[ "$health" == "healthy" ]] || [[ "$health" == "n/a" ]]; then
                    echo -e "  ${GREEN}OK${NC}  ${name} (${status}/${health})"
                else
                    echo -e "  ${YELLOW}WARN${NC}  ${name} (${status}/${health})"
                fi
            else
                echo -e "  ${RED}FAIL${NC}  ${name} (${status})"
            fi
        done
    fi

    # 网络端口检查
    echo ""
    echo -e "${BOLD}【端口监听】${NC}"
    if command -v ss &>/dev/null; then
        ss -tlnp 2>/dev/null | grep -E '18080|18081|18082|13306|16379' | awk '{
            split($4, a, ":")
            port=a[length(a)]
            printf "  %-6s %s\n", port, $6
        }' || info "无相关端口监听"
    fi

    # 权限检查
    echo ""
    echo -e "${BOLD}【权限检查】${NC}"
    local perm_ok=true
    for d in backup uploads logs; do
        if [[ -d "$PROJECT_DIR/$d" ]] && [[ ! -w "$PROJECT_DIR/$d" ]]; then
            warn "${d}/ 不可写"
            perm_ok=false
        fi
    done
    if [[ -f "$PROJECT_DIR/.env" ]] && [[ ! -w "$PROJECT_DIR/.env" ]]; then
        warn ".env 不可写"
        perm_ok=false
    fi
    if [[ "$(whoami)" != "root" ]] && command -v docker &>/dev/null; then
        if ! id -nG "$(whoami)" 2>/dev/null | grep -qw "docker"; then
            warn "当前用户不在 docker 组"
            perm_ok=false
        fi
    fi
    [[ "$perm_ok" == "true" ]] && info "权限检查通过"

    echo ""
    pause
}

#==============================================================
# 15. 清理 Docker 镜像(新增)
#==============================================================
menu_clean_images() {
    echo ""
    echo -e "${BLUE}========== 清理 Docker 镜像 ==========${NC}"
    echo "  清理未使用的镜像、容器、网络和缓存,释放磁盘空间"
    echo ""

    # 显示当前占用
    info "当前 Docker 磁盘占用:"
    docker system df 2>/dev/null
    echo ""

    if ! confirm "确认清理?"; then
        info "已取消"
        pause
        return
    fi

    info "正在清理..."
    docker system prune -f 2>/dev/null
    echo ""
    info "清理后 Docker 磁盘占用:"
    docker system df 2>/dev/null
    echo ""
    info "清理完成"
    pause
}

#==============================================================
# 16. 查看配置信息(新增,密码脱敏)
#==============================================================
menu_config() {
    echo ""
    echo -e "${BLUE}========== 配置信息 ==========${NC}"
    echo ""

    local envfile="$PROJECT_DIR/.env"
    if [[ ! -f "$envfile" ]]; then
        warn ".env 文件不存在"
        pause
        return
    fi

    # 检查 .env 可读
    if [[ ! -r "$envfile" ]]; then
        warn ".env 文件不可读(权限不足)"
        echo "  修复: sudo chmod 644 $envfile && sudo cat $envfile"
        pause
        return
    fi

    echo -e "${BOLD}【.env 配置(敏感信息已脱敏)】${NC}"
    echo ""

    # 读取并脱敏显示
    local line key value
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && { echo "  $line"; continue; }
        [[ -z "${line// }" ]] && { echo ""; continue; }

        key="${line%%=*}"
        value="${line#*=}"

        # 去除外层引号
        if [[ "$value" =~ ^\".*\"$ ]] || [[ "$value" =~ ^\'.*\'$ ]]; then
            value="${value:1:-1}"
        fi

        # 敏感字段脱敏:只显示前 4 位 + ****
        case "$key" in
            MYSQL_ROOT_PASSWORD|DB_APP_PASSWORD|REDIS_PASSWORD|JWT_SECRET|BACKUP_ENCRYPTION_KEY|INIT_ADMIN_PASSWORD)
                if [[ -n "$value" ]]; then
                    local masked
                    if [[ ${#value} -le 4 ]]; then
                        masked="****"
                    else
                        masked="${value:0:4}****"
                    fi
                    printf "  %-30s %s\n" "$key" "$masked"
                else
                    printf "  %-30s (空)\n" "$key"
                fi
                ;;
            INIT_ADMIN_PASSWORD)
                # INIT_ADMIN_PASSWORD 应该已被清理
                printf "  %-30s (应已清理)\n" "$key"
                ;;
            *)
                printf "  %-30s %s\n" "$key" "$value"
                ;;
        esac
    done < "$envfile"

    echo ""
    echo -e "${BOLD}【访问地址】${NC}"
    local ip
    ip=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "服务器IP")
    echo "  管理后台:   http://${ip}:18080"
    echo "  H5 订餐端:  http://${ip}:18081"
    echo "  后端 API:   http://${ip}:18082"
    echo "  MySQL:      127.0.0.1:13306"
    echo "  Redis:      127.0.0.1:16379"

    echo ""
    echo -e "${BOLD}【超管账号】${NC}"
    local admin_user
    admin_user=$(read_env_var "INIT_ADMIN_USERNAME" "$envfile" 2>/dev/null) || admin_user=""
    if [[ -n "$admin_user" ]]; then
        echo "  超管账号: ${admin_user}"
    else
        echo "  超管账号: (未配置,使用 canteen 重置密码)"
    fi

    echo ""
    pause
}

#==============================================================
# 主菜单
#==============================================================
show_menu() {
    clear 2>/dev/null || true

    local version status_line be_ver hw_ver h5_ver term_ver cur_branch
    version=$(get_version)
    status_line=$(get_status_line)
    be_ver=$(get_module_version backend)
    hw_ver=$(get_module_version admin-web)
    h5_ver=$(get_module_version h5)
    term_ver=$(get_module_version terminal)
    cur_branch=$(get_current_branch)

    echo -e "${BLUE}╔══════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}   ${BOLD}企业智慧食堂系统 - 管理面板 V2${NC}                    ${BLUE}║${NC}"
    echo -e "${BLUE}╠══════════════════════════════════════════════════════╣${NC}"
    echo -e "${BLUE}║${NC}  系统版本: v${version}    状态: ${status_line}          ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  后端: v${be_ver}  管理后台: v${hw_ver}  H5: v${h5_ver}      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  终端: v${term_ver}  分支: ${cur_branch}                        ${BLUE}║${NC}"
    echo -e "${BLUE}╠══════════════════════════════════════════════════════╣${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【升级】${NC}                                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   1) 升级全部 (后端+前端) ${YELLOW}含备份+自动回退${NC}         ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   2) 仅升级后端                                         ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   3) 仅升级前端 (admin-web + h5)                       ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【备份与恢复】${NC}                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   4) 手动备份 (创建快照)                                ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   5) 恢复备份 (从快照恢复)                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   6) 查看快照列表                                        ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【管理】${NC}                                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   7) 查看服务状态 ${YELLOW}(含资源监控)${NC}                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   8) 重置管理员密码                                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   9) 查看日志                                           ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  10) 重启服务                                           ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  11) 停止服务                                           ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【系统】${NC}                                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  12) 修复 canteen 系统命令                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  13) 查看版本详情与更新日志                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  14) 系统诊断 ${YELLOW}(OS/CPU/内存/端口/权限)${NC}              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  15) 清理 Docker 镜像 ${YELLOW}(释放磁盘空间)${NC}               ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  16) 查看配置信息 ${YELLOW}(.env脱敏+访问地址)${NC}              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   0) 退出                                               ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════╝${NC}"
    echo ""
}

main_loop() {
    while true; do
        show_menu
        read -p "$(echo -e "${CYAN}请选择 [0-16]: ${NC}")" choice

        case "$choice" in
            1) menu_upgrade_all ;;
            2) menu_upgrade_backend ;;
            3) menu_upgrade_frontend ;;
            4) menu_backup ;;
            5) menu_restore ;;
            6) menu_snapshots ;;
            7) menu_status ;;
            8) menu_reset_admin ;;
            9) menu_logs ;;
            10) menu_restart ;;
            11) menu_stop ;;
            12) menu_install ;;
            13) menu_versions ;;
            14) menu_diagnostics ;;
            15) menu_clean_images ;;
            16) menu_config ;;
            0|q|quit|exit)
                echo ""
                info "再见!"
                echo ""
                exit 0
                ;;
            *)
                warn "无效选择: $choice"
                sleep 1
                ;;
        esac
    done
}

#==============================================================
# 入口
#==============================================================

# 确保脚本可执行
chmod +x "$PROJECT_DIR/canteen.sh" 2>/dev/null || true
chmod +x "$PROJECT_DIR/scripts/"*.sh 2>/dev/null || true

# 支持直接传子命令(非交互模式)
if [ $# -gt 0 ]; then
    case "$1" in
        install)
            menu_install
            exit $?
            ;;
        uninstall)
            menu_uninstall
            exit $?
            ;;
        status)
            menu_status
            exit $?
            ;;
        upgrade)
            shift
            chmod +x "$PROJECT_DIR/scripts/upgrade.sh"
            "$PROJECT_DIR/scripts/upgrade.sh" "${1:-all}"
            exit $?
            ;;
        backup)
            shift
            chmod +x "$PROJECT_DIR/scripts/snapshot.sh"
            "$PROJECT_DIR/scripts/snapshot.sh" create "${1:-手动备份}"
            exit $?
            ;;
        restore)
            shift
            chmod +x "$PROJECT_DIR/scripts/snapshot.sh"
            "$PROJECT_DIR/scripts/snapshot.sh" restore "$@"
            exit $?
            ;;
        logs)
            shift
            docker compose logs --tail=200 "${1:-}" 2>/dev/null
            exit $?
            ;;
        diagnose|diag)
            menu_diagnostics
            exit $?
            ;;
        help|-h|--help)
            echo "企业智慧食堂系统管理面板 V2"
            echo ""
            echo "用法:"
            echo "  canteen                 # 打开交互式菜单"
            echo "  canteen install         # 重新安装/修复 canteen 命令"
            echo "  canteen uninstall       # 卸载 canteen 命令"
            echo "  canteen status          # 查看服务状态(含资源监控)"
            echo "  canteen upgrade [all|backend|frontend]  # 升级"
            echo "  canteen backup [说明]   # 创建快照"
            echo "  canteen restore <ID>    # 恢复快照"
            echo "  canteen logs [服务]     # 查看日志"
            echo "  canteen diagnose        # 系统诊断"
            echo ""
            exit 0
            ;;
    esac
fi

# 交互式菜单
main_loop
