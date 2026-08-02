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

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

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

# 获取当前版本号
get_version() {
    if [ -f "$PROJECT_DIR/backend/src/main/resources/version.json" ]; then
        grep -m1 '"version"' "$PROJECT_DIR/backend/src/main/resources/version.json" 2>/dev/null \
            | sed 's/.*"\([0-9.]*\)".*/\1/' || echo "unknown"
    else
        echo "unknown"
    fi
}

# 获取服务状态摘要(一行)
get_status_line() {
    if ! command -v docker &>/dev/null; then
        echo -e "${RED}● Docker 未安装${NC}"
        return
    fi
    local running
    running=$(docker compose ps --format json 2>/dev/null | grep -c '"running"' || echo "0")
    local total=5  # backend, admin-web, h5, mysql, redis
    if [ "$running" -ge 5 ] 2>/dev/null; then
        echo -e "${GREEN}● 全部运行中 (${running}/${total})${NC}"
    elif [ "$running" -gt 0 ] 2>/dev/null; then
        echo -e "${YELLOW}● 部分运行 (${running}/${total})${NC}"
    else
        echo -e "${RED}● 未运行${NC}"
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

# 2. 升级全部(后端+前端)
menu_upgrade_all() {
    echo ""
    echo -e "${BLUE}========== 升级全部 ==========${NC}"
    echo "  此操作将:"
    echo "    1. 创建升级前快照(数据库 + 产物 + 代码版本)"
    echo "    2. 拉取最新代码(git pull)"
    echo "    3. 重新构建全部产物"
    echo "    4. 重启所有服务"
    echo "    5. 健康检查"
    echo "    6. 如失败将自动回退到升级前状态"
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
    echo "  此操作将:"
    echo "    1. 创建升级前快照"
    echo "    2. 拉取最新代码"
    echo "    3. 重新构建后端 jar"
    echo "    4. 重启后端服务"
    echo "    5. 健康检查(失败自动回退)"
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
    echo "  此操作将:"
    echo "    1. 创建升级前快照"
    echo "    2. 拉取最新代码"
    echo "    3. 重新构建 admin-web + h5"
    echo "    4. 重启前端服务"
    echo "    5. 健康检查(失败自动回退)"
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
    echo "  此操作将设置超管账号密码,需要重启后端服务生效。"
    echo "  - 若账号已存在且为超管:更新密码"
    echo "  - 若账号不存在:创建新超管(仅在 admin 表为空或只有默认 admin 时)"
    echo ""

    read -p "$(echo -e "${CYAN}[?]${NC} 超管账号名 [admin]: ")" username
    username="${username:-admin}"

    # 读取密码(隐藏输入,带确认)
    local pwd1 pwd2
    while true; do
        read -s -p "$(echo -e "${CYAN}[?]${NC} 新密码(至少 8 位): ")" pwd1
        echo ""
        read -s -p "$(echo -e "${CYAN}[?]${NC} 确认密码: ")" pwd2
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
    if confirm "确认重置超管 '${username}' 的密码?"; then
        # 调用 deploy.sh 的 set_env_var 逻辑(内联实现,避免依赖)
        local envfile="$PROJECT_DIR/.env"
        touch "$envfile"

        # 更新/追加环境变量(用 awk 避免 sed 转义问题)
        for kv in "INIT_ADMIN_USERNAME=$username" "INIT_ADMIN_PASSWORD=$pwd1" "INIT_ADMIN_FORCE=true"; do
            local key="${kv%%=*}"
            local val="${kv#*=}"
            if grep -q "^${key}=" "$envfile" 2>/dev/null; then
                local tmp
                tmp=$(mktemp)
                KEY="$key" VALUE="$val" awk '
                    BEGIN { k = ENVIRON["KEY"]; v = ENVIRON["VALUE"] }
                    index($0, k "=") == 1 { print k "=" v; next }
                    { print }
                ' "$envfile" > "$tmp" && mv "$tmp" "$envfile"
            else
                echo "${key}=${val}" >> "$envfile"
            fi
        done

        info "正在重启后端服务..."
        docker compose restart backend 2>/dev/null

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

        if [ "$ok" = true ]; then
            echo ""
            info "密码重置成功!"
            echo "  超管账号: ${username}"
            echo "  请使用新密码登录管理后台"
            echo ""
            warn "登录成功后请删除 .env 中的 INIT_ADMIN_FORCE 和 INIT_ADMIN_PASSWORD"
        else
            error "后端启动超时,请查看日志: docker compose logs backend"
        fi
    else
        info "已取消"
    fi
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
    docker compose logs --tail=200 "$svc" 2>/dev/null
    echo ""
    info "是否持续跟踪日志?(Ctrl+C 退出)"
    if confirm "跟踪日志?"; then
        docker compose logs -f "$svc" 2>/dev/null
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
        case "$svc" in
            all)       docker compose restart ;;
            backend)   docker compose restart backend ;;
            frontend)  docker compose restart admin-web h5 ;;
        esac
        info "已重启"
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
# 主菜单
#==============================================================
show_menu() {
    # 清屏
    clear 2>/dev/null || true

    local version
    version=$(get_version)
    local status_line
    status_line=$(get_status_line)

    echo -e "${BLUE}╔══════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}   ${BOLD}企业智慧食堂系统 - 管理面板${NC}              ${BLUE}║${NC}"
    echo -e "${BLUE}╠══════════════════════════════════════════════╣${NC}"
    echo -e "${BLUE}║${NC}  版本: v${version}    状态: ${status_line}        ${BLUE}║${NC}"
    echo -e "${BLUE}╠══════════════════════════════════════════════╣${NC}"
    echo -e "${BLUE}║${NC}                                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【升级】${NC}                                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   1) 升级全部 (后端+前端) ${YELLOW}含备份+自动回退${NC}  ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   2) 仅升级后端                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   3) 仅升级前端 (admin-web + h5)            ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【备份与恢复】${NC}                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   4) 手动备份 (创建快照)                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   5) 恢复备份 (从快照恢复)                   ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   6) 查看快照列表                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【管理】${NC}                                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   7) 查看服务状态                             ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   8) 重置管理员密码                           ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   9) 查看日志                                 ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  10) 重启服务                                 ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  11) 停止服务                                 ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${BOLD}【系统】${NC}                                     ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  12) 修复 canteen 系统命令(重新安装)           ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                              ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}   0) 退出                                     ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════╝${NC}"
    echo ""
}

main_loop() {
    while true; do
        show_menu
        read -p "$(echo -e "${CYAN}请选择 [0-12]: ${NC}")" choice

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
