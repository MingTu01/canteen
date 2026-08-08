#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 服务器管理面板
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

# 解析符号链接:通过 /usr/local/bin/canteen 软链接调用时,
# $0 是 /usr/local/bin/canteen,dirname 得到 /usr/local/bin(错误)。
# 用 readlink -f 解析真实路径;不支持时回退到遍历 symlink。
resolve_project_dir() {
    local src="$1"
    # readlink -f 能解析多级符号链接(GNU coreutils, CentOS/Ubuntu 自带)
    if command -v readlink &>/dev/null; then
        local resolved
        resolved=$(readlink -f "$src" 2>/dev/null) && [[ -n "$resolved" ]] && { echo "$(cd "$(dirname "$resolved")" && pwd)"; return; }
    fi
    # 回退:手动遍历 symlink(BSD/老旧系统)
    while [[ -L "$src" ]]; do
        local dir
        dir=$(cd "$(dirname "$src")" && pwd)
        src=$(readlink "$src")
        [[ "$src" != /* ]] && src="$dir/$src"
    done
    echo "$(cd "$(dirname "$src")" && pwd)"
}
PROJECT_DIR="$(resolve_project_dir "$0")"
cd "$PROJECT_DIR" || { echo "无法进入项目目录 $PROJECT_DIR"; exit 1; }

# 确保运行时目录存在且可写(普通用户运行 canteen upgrade 时,backup/snapshots 创建不被拒绝)
# 问题场景:sudo ./deploy.sh 部署后,backup/ uploads/ logs/ 属主是 root,
# 后续普通用户运行 canteen upgrade 时,snapshot.sh 在 backup/ 下创建子目录会被 Permission denied。
# 解决:启动时检查并创建(若已存在属主不对,提示用户 sudo chown)。
ensure_runtime_dirs() {
    for d in backup uploads logs; do
        if [[ ! -d "$PROJECT_DIR/$d" ]]; then
            mkdir -p "$PROJECT_DIR/$d" 2>/dev/null || {
                echo -e "${RED}[ERROR]${NC} 无法创建 $PROJECT_DIR/$d (权限不足)"
                echo "  请执行: sudo chown -R \$(whoami):\$(whoami) $PROJECT_DIR"
                exit 1
            }
        fi
        # 检查可写性
        if [[ ! -w "$PROJECT_DIR/$d" ]]; then
            echo -e "${YELLOW}[WARN]${NC} $PROJECT_DIR/$d 不可写(属主可能是 root)"
            echo "  修复: sudo chown -R \$(whoami):\$(whoami) $PROJECT_DIR/$d"
        fi
    done
}
ensure_runtime_dirs

# 颜色
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
# 工具函数
#==============================================================

# 获取当前系统版本号(从 VERSIONS.json 读取)
get_version() {
    if [ -f "$PROJECT_DIR/VERSIONS.json" ]; then
        # 读取 system.version 字段
        python3 -c "import json; print(json.load(open('$PROJECT_DIR/VERSIONS.json'))['system']['version'])" 2>/dev/null || \
        grep -A1 '"system"' "$PROJECT_DIR/VERSIONS.json" 2>/dev/null | grep '"version"' \
            | sed 's/.*"\([0-9.]*\)".*/\1/' || echo "unknown"
    else
        echo "unknown"
    fi
}

# 获取指定模块版本号(参数: 模块名 backend/admin-web/h5/terminal)
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

# 显示所有模块版本号(多行)
show_all_versions() {
    local versions_file="$PROJECT_DIR/VERSIONS.json"
    if [ ! -f "$versions_file" ]; then
        echo "  (VERSIONS.json 不存在,版本未知)"
        return
    fi
    echo "  后端服务:    v$(get_module_version backend)"
    echo "  管理后台:    v$(get_module_version admin-web)"
    echo "  H5订餐端:    v$(get_module_version h5)"
    echo "  X86终端:     v$(get_module_version terminal)"
    echo "  系统版本:    v$(get_module_version system)"
}

# 获取服务状态摘要(一行)
get_status_line() {
    if ! command -v docker &>/dev/null; then
        echo -e "${RED}● Docker 未安装${NC}"
        return
    fi
    # 用 docker ps 直接查 canteen- 开头的容器,不依赖 docker-compose.yml 和 .env
    # 避免 .env 权限不足时 docker compose 命令失败导致误报"未运行"
    local running total=5  # backend, admin-web, h5, mysql, redis
    running=$(docker ps --filter "name=canteen-" --format '{{.Names}}' 2>/dev/null | wc -l)
    if [ "$running" -ge 5 ] 2>/dev/null; then
        echo -e "${GREEN}● 全部运行中 (${running}/${total})${NC}"
    elif [ "$running" -gt 0 ] 2>/dev/null; then
        echo -e "${YELLOW}● 部分运行 (${running}/${total})${NC}"
    else
        echo -e "${RED}● 未运行${NC}"
    fi
}

# 获取当前 git 分支名(用于菜单显示)
get_current_branch() {
    if [ ! -d "$PROJECT_DIR/.git" ]; then
        echo "非Git"
        return
    fi
    local branch
    branch=$(git -C "$PROJECT_DIR" branch --show-current 2>/dev/null || echo "")
    if [ -z "$branch" ]; then
        # detached HEAD 状态
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

#==============================================================
# 菜单功能函数
#==============================================================

# 1. 查看服务状态
menu_status() {
    echo ""
    echo -e "${BLUE}========== 服务状态 ==========${NC}"
    echo ""
    docker compose ps 2>/dev/null || {
        error "Docker Compose 未运行"
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
    # 磁盘
    echo ""
    local disk_usage
    disk_usage=$(df -h "$PROJECT_DIR" | awk 'NR==2{print $5}')
    info "磁盘使用: ${disk_usage}"
    pause
}

# 显示升级步骤说明(根据当前分支自动适配)
# 参数: $1 = 升级范围描述(如 "全部" / "后端" / "前端")
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

# 2. 升级全部(后端+前端)
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

# 5. 手动备份(创建快照)
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

# 6. 恢复备份(从快照恢复)
menu_restore() {
    echo ""
    echo -e "${BLUE}========== 恢复备份 ==========${NC}"
    echo ""
    chmod +x "$PROJECT_DIR/scripts/snapshot.sh"

    # 先列出快照
    "$PROJECT_DIR/scripts/snapshot.sh" list

    # 检查是否有快照
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

    # 读取密码(隐藏输入,带确认,-r 防止反斜杠被转义消耗)
    local pwd1 pwd2
    while true; do
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} 新密码(至少 8 位): ")" pwd1
        echo ""
        read -r -s -p "$(echo -e "${CYAN}[?]${NC} 确认密码: ")" pwd2
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

    # 检查 .env 可写(常见失败:符号链接解析错误或权限不足)
    if ! touch "$envfile" 2>/dev/null; then
        # .env 不可写,尝试自动修复权限
        # 常见原因:deploy.sh 用 sudo 运行,.env 被 root 所有
        if [[ -f "$envfile" ]] && [[ "$(id -u)" != "0" ]]; then
            local env_owner
            env_owner=$(stat -c '%U' "$envfile" 2>/dev/null || echo "")
            if [[ "$env_owner" == "root" ]]; then
                info "检测到 .env 属于 root,尝试修复权限..."
                if sudo chown "$(whoami):$(whoami)" "$envfile" 2>/dev/null; then
                    info "权限已修复"
                else
                    error "无法修改 .env 权限(sudo 失败)"
                    warn "请手动执行: sudo chown $(whoami):$(whoami) $envfile"
                    pause
                    return
                fi
            else
                error "无法写入 .env 文件: $envfile"
                warn "所有者: ${env_owner:-unknown}, 当前用户: $(whoami)"
                warn "请手动执行: sudo chown $(whoami):$(whoami) $envfile"
                pause
                return
            fi
        else
            error "无法写入 .env 文件: $envfile"
            warn "可能原因:权限不足或项目目录不正确"
            warn "请尝试: sudo canteen  或  cd $(dirname "$PROJECT_DIR") && sudo ./canteen.sh"
            pause
            return
        fi
    fi

    # 写入 INIT_ADMIN_* 环境变量(用双引号包裹值,兼容 Docker Compose dotenv 解析)
    info "写入配置..."
    # 转义值,使其在 .env 的双引号包裹下能被 Docker Compose 正确解析。
    # 不能用单引号包裹 + shell 的 '\'' 转义——那是 shell 规则,Docker Compose dotenv 不认,
    # 密码含单引号(如 qweasd2864..')会报 "unexpected character",导致整个 .env 无法被 Compose 读取。
    # 双引号值支持转义,`\` 转义 `\`、`"`、`$`、反引号;单引号在双引号内字面保留。
    _escape_val() {
        local v="$1"
        v="${v//\\/\\\\}"
        v="${v//\"/\\\"}"
        v="${v//\$/\\\$}"
        v="${v//\`/\\\`}"
        printf '%s' "$v"
    }
    for kv in "INIT_ADMIN_USERNAME=$username" "INIT_ADMIN_PASSWORD=$pwd1" "INIT_ADMIN_FORCE=true"; do
        local key="${kv%%=*}"
        local val="${kv#*=}"
        local escaped_val new_line
        escaped_val=$(_escape_val "$val")
        new_line="${key}=\"${escaped_val}\""
        if grep -q "^${key}=" "$envfile" 2>/dev/null; then
            local tmp
            tmp=$(mktemp)
            KEY="$key" LINE="$new_line" awk '
                BEGIN { k = ENVIRON["KEY"]; line = ENVIRON["LINE"] }
                index($0, k "=") == 1 { print line; next }
                { print }
            ' "$envfile" > "$tmp" && mv "$tmp" "$envfile"
        else
            echo "$new_line" >> "$envfile"
        fi
    done
    # 写入后强制权限 600 + chown(避免 sudo 运行时 .env 变 root 所有)
    chmod 600 "$envfile" 2>/dev/null || true
    if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
        chown "$SUDO_USER:$SUDO_USER" "$envfile" 2>/dev/null || true
    fi

    # 用 up -d 而非 restart:restart 不重读 .env,只有 up -d 才会用新环境变量重建容器
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

    # 自动清理 .env 中的敏感变量(密码已在数据库中,无需保留)
    # 删除 INIT_ADMIN_FORCE 和 INIT_ADMIN_PASSWORD,保留 INIT_ADMIN_USERNAME 供参考
    # 安全清理:先校验过滤结果非空,避免 grep 失败导致空文件覆盖 .env
    info "清理临时配置..."
    local tmp
    tmp=$(mktemp)
    if grep -v "^INIT_ADMIN_FORCE=" "$envfile" 2>/dev/null | grep -v "^INIT_ADMIN_PASSWORD=" > "$tmp" && [ -s "$tmp" ]; then
        cp "$envfile" "${envfile}.bak" 2>/dev/null
        chmod 600 "${envfile}.bak" 2>/dev/null || true
        mv "$tmp" "$envfile"
        # 清理后重新设置权限 600 + chown
        chmod 600 "$envfile" 2>/dev/null || true
        if [[ -n "$SUDO_USER" ]] && [[ "$SUDO_USER" != "root" ]]; then
            chown "$SUDO_USER:$SUDO_USER" "$envfile" 2>/dev/null || true
        fi
    else
        rm -f "$tmp"
        warn "清理 .env 失败,原文件未修改(敏感变量仍保留,建议手动删除 INIT_ADMIN_FORCE/INIT_ADMIN_PASSWORD)"
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
    info "查看 ${svc} 日志(最近 200 行,Ctrl+C 退出跟踪)..."
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
    read -p "$(echo -e "${CYAN}[?]${NC} 选择 [0-3]: ")"

    local svc=""
    case "$REPLY" in
        1) svc="all" ;;
        2) svc="backend" ;;
        3) svc="frontend" ;;
        0) return ;;
        *) warn "无效选择"; return ;;
    esac

    echo ""
    if confirm "确认重启?"; then
        # 用 up -d 而非 restart:restart 在容器被 down 删除后会失败,up -d 会重建
        # 不吞错误,失败时显示真实原因
        local ok=true
        local err_out
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

# 12. 修复/重装 canteen 系统命令
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

#==============================================================
# 13. 查看版本详情与更新日志
#==============================================================
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

    # 用 python3 解析 JSON,显示各模块版本和更新日志
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
            print(f\"    更新: {cl}\")
        print()
" 2>/dev/null || {
        # python3 不可用时降级显示
        echo "  后端服务:    v$(get_module_version backend)"
        echo "  管理后台:    v$(get_module_version admin-web)"
        echo "  H5订餐端:    v$(get_module_version h5)"
        echo "  X86终端:     v$(get_module_version terminal)"
        echo "  系统版本:    v$(get_module_version system)"
        echo ""
        warn "(python3 不可用,仅显示版本号,不显示更新日志)"
    }

    # 显示最近 git 提交历史(最近 10 条)
    if [ -d "$PROJECT_DIR/.git" ]; then
        echo -e "${BLUE}---------- 最近代码更新 ----------${NC}"
        git -C "$PROJECT_DIR" log --oneline -10 --pretty=format:"  %h %s (%ci)" 2>/dev/null || echo "  (无法读取 git 日志)"
        echo ""
    fi
    echo ""
    pause
}

#==============================================================
# 主菜单
#==============================================================
show_menu() {
    # 清屏
    clear 2>/dev/null || true

    local version
    version=$(get_version)
    local status_line
    status_line=$(get_status_line)
    local be_ver hw_ver h5_ver term_ver
    be_ver=$(get_module_version backend)
    hw_ver=$(get_module_version admin-web)
    h5_ver=$(get_module_version h5)
    term_ver=$(get_module_version terminal)
    local cur_branch
    cur_branch=$(get_current_branch)

    echo -e "${BLUE}╔══════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}   ${BOLD}企业智慧食堂系统 - 管理面板${NC}                      ${BLUE}║${NC}"
    echo -e "${BLUE}╠══════════════════════════════════════════════════════╣${NC}"
    echo -e "${BLUE}║${NC}  系统版本: v${version}    状态: ${status_line}          ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  后端: v${be_ver}  管理后台: v${hw_ver}  H5: v${h5_ver}      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  终端: v${term_ver}  分支: ${cur_branch}                        ${BLUE}║${NC}"
    echo -e "${BLUE}╠══════════════════════════════════════════════════════╣${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【升级】${NC}                                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   1) 升级全部 (后端+前端) ${YELLOW}含备份+自动回退+版本对比${NC}  ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   2) 仅升级后端                                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   3) 仅升级前端 (admin-web + h5)                    ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【备份与恢复】${NC}                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   4) 手动备份 (创建快照)                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   5) 恢复备份 (从快照恢复)                           ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   6) 查看快照列表                                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【管理】${NC}                                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   7) 查看服务状态                                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   8) 重置管理员密码                                   ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   9) 查看日志                                         ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  10) 重启服务                                         ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  11) 停止服务                                         ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【系统】${NC}                                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  12) 修复 canteen 系统命令(重新安装)                   ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  13) 查看版本详情与更新日志                           ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   0) 退出                                             ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════╝${NC}"
    echo ""
}

main_loop() {
    while true; do
        show_menu
        read -p "$(echo -e "${CYAN}请选择 [0-13]: ${NC}")" choice

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
        help|-h|--help)
            echo "企业智慧食堂系统管理面板"
            echo ""
            echo "用法:"
            echo "  canteen                 # 打开交互式菜单"
            echo "  canteen install         # 重新安装/修复 canteen 命令"
            echo "  canteen uninstall       # 卸载 canteen 命令"
            echo "  canteen status          # 查看服务状态"
            echo "  canteen upgrade [all|backend|frontend]  # 升级"
            echo "  canteen backup [说明]   # 创建快照"
            echo "  canteen restore <ID>    # 恢复快照"
            echo "  canteen logs [服务]     # 查看日志"
            echo ""
            exit 0
            ;;
    esac
fi

# 交互式菜单
main_loop
