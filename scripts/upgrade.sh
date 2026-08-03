#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 安全升级脚本(分支感知版)
#==============================================================
# 本脚本自动检测当前所在分支,按分支采用不同升级策略:
#
# 【deploy 分支】(服务器部署用,产物已预构建):
#   1. 升级前快照(数据库 + deploy 产物 + git commit)
#   2. git pull 拉取最新产物(deploy 分支,无需构建)
#   3. docker compose up -d 重启服务
#   4. 健康检查(等待 120s)
#   5. 失败则自动回退到快照状态
#
# 【main 分支】(开发机用,需本地构建):
#   1. 升级前快照
#   2. git pull 拉取源码
#   3. build.sh 重建产物
#   4. docker compose up -d 重启服务
#   5. 健康检查 + 自动回退
#
# 【detached HEAD】(历史遗留问题):
#   自动切换到 deploy 分支并继续升级
#
# 用法:
#   ./scripts/upgrade.sh              # 升级全部(后端 + 前端)
#   ./scripts/upgrade.sh backend      # 仅升级后端
#   ./scripts/upgrade.sh frontend     # 仅升级前端(admin-web/h5)
#
# 注意:X86 终端不在 Docker 部署(独立 EXE 安装包),不参与本脚本升级。
#==============================================================

# 不用 set -e,因为我们要在失败时执行回退而非直接退出
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

SCOPE="${1:-all}"

# 颜色(必须在分支检测前定义,因分支检测中会调用 info/warn/error)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[升级]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }
step()  { echo -e "\n${BLUE}========== $1 ==========${NC}"; }

# 安全读取 .env 变量(不执行 source,避免密码含 $/空格/#/反引号 时 shell 展开导致值篡改)
read_env_var() {
    local key="$1" envfile="${2:-$PROJECT_DIR/.env}"
    [[ -f "$envfile" ]] || return 1
    local line value
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        [[ -z "${line// }" ]] && continue
        if [[ "$line" =~ ^${key}= ]]; then
            value="${line#*=}"
            if [[ "$value" =~ ^\'.*\'$ ]]; then
                value="${value:1:-1}"
            elif [[ "$value" =~ ^\".*\"$ ]]; then
                value="${value:1:-1}"
            fi
            printf '%s' "$value"
            return 0
        fi
    done < "$envfile"
    return 1
}

#==============================================================
# 分支检测:确定升级模式
#==============================================================
# 获取当前分支名(detached HEAD 时为空)
CURRENT_BRANCH=$(git -C "$PROJECT_DIR" branch --show-current 2>/dev/null || echo "")

# 检测是否处于 detached HEAD 状态
IS_DETACHED=false
if [ -z "$CURRENT_BRANCH" ]; then
    IS_DETACHED=true
fi

# 确定升级模式:deploy 分支 = 免构建,其他 = 需构建
NO_BUILD=false
TRACK_BRANCH="main"

if [ "$IS_DETACHED" = "true" ]; then
    # detached HEAD:尝试切换到 deploy 分支(服务器部署场景)
    info "检测到 detached HEAD 状态,尝试切换到 deploy 分支..."
    if git -C "$PROJECT_DIR" checkout deploy 2>/dev/null; then
        CURRENT_BRANCH="deploy"
        NO_BUILD=true
        TRACK_BRANCH="deploy"
        info "已切换到 deploy 分支(免构建模式)"
    elif git -C "$PROJECT_DIR" checkout -b deploy origin/deploy 2>/dev/null; then
        CURRENT_BRANCH="deploy"
        NO_BUILD=true
        TRACK_BRANCH="deploy"
        info "已从远程创建并切换到 deploy 分支(免构建模式)"
    else
        # 无法切换到 deploy,回退到 main 分支
        warn "无法切换到 deploy 分支,尝试 main 分支(需构建模式)..."
        if git -C "$PROJECT_DIR" checkout main 2>/dev/null; then
            CURRENT_BRANCH="main"
            TRACK_BRANCH="main"
        else
            error "无法确定所在分支,且无法切换到 deploy 或 main"
            warn "请手动执行: git checkout deploy  或  git checkout main"
            exit 1
        fi
    fi
elif [ "$CURRENT_BRANCH" = "deploy" ]; then
    NO_BUILD=true
    TRACK_BRANCH="deploy"
fi

# 获取当前系统版本号(从 VERSIONS.json 读取)
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

# 显示升级前版本信息(本地当前版本 + 远程最新版本 + git 提交差异)
show_version_diff() {
    echo ""
    echo -e "${CYAN}---------- 版本对比 ----------${NC}"

    # 本地当前版本
    local be_local hw_local h5_local
    be_local=$(get_module_version backend)
    hw_local=$(get_module_version admin-web)
    h5_local=$(get_module_version h5)

    echo "  [本地当前版本]"
    echo "    后端: v${be_local}    管理后台: v${hw_local}    H5: v${h5_local}"
    echo ""

    # 获取本地最新 commit
    local local_commit=""
    if [ -d "$PROJECT_DIR/.git" ]; then
        local_commit=$(git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null | cut -c1-12)
    fi

    # 获取远程最新 commit 和版本号
    local remote_commit="" remote_be="" remote_hw="" remote_h5=""
    if [ -d "$PROJECT_DIR/.git" ]; then
        info "检查远程仓库最新版本(分支: ${TRACK_BRANCH})..."
        # 获取远程跟踪分支最新 commit(不修改本地代码)
        remote_commit=$(git -C "$PROJECT_DIR" ls-remote origin "$TRACK_BRANCH" 2>/dev/null | awk '{print $1}' | cut -c1-12)

        if [ -n "$remote_commit" ] && [ "$remote_commit" != "$local_commit" ]; then
            echo ""
            echo "  [远程最新版本] commit: ${remote_commit} (分支: ${TRACK_BRANCH})"
            # 显示本地与远程之间的提交差异
            echo ""
            echo "  [待更新提交] (本地 ${local_commit} → 远程 ${remote_commit})"
            # 获取远程 commit 但本地没有的提交列表
            git -C "$PROJECT_DIR" fetch origin "$TRACK_BRANCH" 2>/dev/null
            local new_commits
            new_commits=$(git -C "$PROJECT_DIR" log --oneline "HEAD..origin/${TRACK_BRANCH}" 2>/dev/null)
            if [ -n "$new_commits" ]; then
                echo "$new_commits" | head -20 | while read -r line; do
                    echo "    $line"
                done
                local total
                total=$(echo "$new_commits" | wc -l)
                if [ "$total" -gt 20 ]; then
                    echo "    ...(共 $total 条提交,仅显示前 20 条)"
                fi
            else
                echo "    (无新提交)"
            fi

            # 尝试获取远程 VERSIONS.json 的版本号
            remote_be=$(git -C "$PROJECT_DIR" show "origin/${TRACK_BRANCH}:VERSIONS.json" 2>/dev/null | \
                python3 -c "import json,sys; print(json.load(sys.stdin).get('backend',{}).get('version','unknown'))" 2>/dev/null || echo "?")
            remote_hw=$(git -C "$PROJECT_DIR" show "origin/${TRACK_BRANCH}:VERSIONS.json" 2>/dev/null | \
                python3 -c "import json,sys; print(json.load(sys.stdin).get('admin-web',{}).get('version','unknown'))" 2>/dev/null || echo "?")
            remote_h5=$(git -C "$PROJECT_DIR" show "origin/${TRACK_BRANCH}:VERSIONS.json" 2>/dev/null | \
                python3 -c "import json,sys; print(json.load(sys.stdin).get('h5',{}).get('version','unknown'))" 2>/dev/null || echo "?")
            echo ""
            echo "  [远程版本号]"
            echo "    后端: v${remote_be}    管理后台: v${remote_hw}    H5: v${remote_h5}"

            # 版本变化提示
            echo ""
            echo "  [版本变化]"
            [ "$be_local" != "$remote_be" ] && [ "$remote_be" != "?" ] && \
                echo "    后端: v${be_local} → v${remote_be}" || echo "    后端: 无变化"
            [ "$hw_local" != "$remote_hw" ] && [ "$remote_hw" != "?" ] && \
                echo "    管理后台: v${hw_local} → v${remote_hw}" || echo "    管理后台: 无变化"
            [ "$h5_local" != "$remote_h5" ] && [ "$remote_h5" != "?" ] && \
                echo "    H5: v${h5_local} → v${remote_h5}" || echo "    H5: 无变化"
        elif [ "$remote_commit" = "$local_commit" ]; then
            echo "  [远程] commit: ${remote_commit}"
            echo "  本地已是最新版本,无待更新提交"
        else
            echo "  (无法获取远程版本信息,可能网络不通)"
        fi
    else
        echo "  (非 Git 项目,无法对比版本)"
    fi
    echo -e "${CYAN}------------------------------${NC}"
    echo ""
}

# 健康检查:等待后端就绪
# 参数: $1 = 最大等待秒数
# 返回: 0 = 健康, 1 = 超时
wait_backend_healthy() {
    local max_wait="${1:-120}"
    local waited=0
    info "等待后端启动(最多 ${max_wait}s)..."
    while [ "$waited" -lt "$max_wait" ]; do
        if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
            info "后端已健康! (耗时 ${waited}s)"
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
        printf "."
    done
    echo ""
    return 1
}

#==============================================================
# 自动回退:恢复到升级前快照
# 参数: $1 = 快照 ID
#==============================================================
auto_rollback() {
    local snap_id="$1"
    local snap_path="$PROJECT_DIR/backup/snapshots/$snap_id"

    echo ""
    error "升级失败,启动自动回退..."
    echo ""

    if [ ! -d "$snap_path" ]; then
        error "快照 $snap_id 不存在,无法自动回退!"
        warn "请手动排查问题或使用 canteen 菜单手动恢复"
        return 1
    fi

    # 从 .env 读取数据库配置(用 read_env_var 避免 source 时特殊字符被展开)
    # P0-2 安全修复:移除弱默认密码,未配置则失败退出
    local db_pass="${SPRING_DATASOURCE_PASSWORD:-}"
    if [ -z "$db_pass" ]; then
        db_pass=$(read_env_var "SPRING_DATASOURCE_PASSWORD" 2>/dev/null) || db_pass=""
    fi
    if [ -z "$db_pass" ]; then
        db_pass="${MYSQL_ROOT_PASSWORD:-}"
        if [ -z "$db_pass" ]; then
            db_pass=$(read_env_var "MYSQL_ROOT_PASSWORD" 2>/dev/null) || db_pass=""
        fi
    fi
    if [ -z "$db_pass" ]; then
        error "数据库密码未配置,请在 .env 中设置 MYSQL_ROOT_PASSWORD 或 SPRING_DATASOURCE_PASSWORD"
        return 1
    fi
    local db_name="${MYSQL_DATABASE:-}"
    if [ -z "$db_name" ]; then
        db_name=$(read_env_var "MYSQL_DATABASE" 2>/dev/null) || db_name="canteen"
    fi

    # P0 修复:回退顺序改为 停服务→代码→数据库→产物→重启
    # (原来是 产物→数据库→代码→重启,后端在数据库恢复期间仍会写入脏数据)

    # 0. 停止后端服务,避免恢复过程中后端写入新数据导致状态不一致
    info "回退:停止后端服务(避免恢复过程中写入数据)..."
    docker compose stop backend 2>/dev/null || true

    # 1. 回退代码(用 git reset --hard 保持分支上下文,避免 detached HEAD)
    if [ -f "$snap_path/git_commit.txt" ]; then
        local commit
        commit=$(cat "$snap_path/git_commit.txt")
        if [ "$commit" != "nongit" ] && [ -d "$PROJECT_DIR/.git" ]; then
            info "回退:代码回退到 ${commit:0:12}(git reset --hard,保持分支上下文)..."
            if git -C "$PROJECT_DIR" reset --hard "$commit" 2>/dev/null; then
                info "代码已回退"
            else
                error "代码回退失败,中止回退流程避免状态不一致"
                warn "请手动执行:git -C $PROJECT_DIR reset --hard $commit"
                warn "完成后重启服务:docker compose up -d"
                return 1
            fi
        fi
    fi

    # 2. 恢复数据库(检查 mysql 命令退出码,失败不报告成功)
    if [ -s "$snap_path/database.sql.gz" ]; then
        if command -v docker &>/dev/null && docker ps 2>/dev/null | grep -q canteen-mysql; then
            info "回退:恢复数据库..."
            set -o pipefail
            if gunzip -c "$snap_path/database.sql.gz" | \
                docker exec -i canteen-mysql mysql -uroot -p"${db_pass}" "${db_name}" 2>/dev/null; then
                info "数据库已恢复"
            else
                error "数据库恢复失败(密码错误或 SQL 执行异常)"
                set +o pipefail
                return 1
            fi
            set +o pipefail
        else
            warn "MySQL 容器未运行,跳过数据库恢复"
        fi
    fi

    # 3. 恢复 deploy/ 产物(先解压到临时目录,成功后再替换,避免 rm -rf 后 tar 失败导致产物丢失)
    if [ -s "$snap_path/deploy.tar.gz" ]; then
        info "回退:恢复 deploy/ 产物..."
        local tmp_extract
        tmp_extract=$(mktemp -d)
        if tar -xzf "$snap_path/deploy.tar.gz" -C "$tmp_extract" 2>/dev/null; then
            rm -rf "$PROJECT_DIR/deploy"
            mv "$tmp_extract/deploy" "$PROJECT_DIR/deploy" 2>/dev/null || cp -r "$tmp_extract/deploy" "$PROJECT_DIR/deploy"
            rm -rf "$tmp_extract"
            info "产物已恢复"
        else
            rm -rf "$tmp_extract"
            error "产物恢复失败(deploy.tar.gz 可能损坏),deploy 目录未修改"
            warn "如需手动恢复:tar -xzf $snap_path/deploy.tar.gz -C $PROJECT_DIR"
            return 1
        fi
    fi

    # 4. 重启服务(用 up -d 而非 restart,确保重读 .env 和 compose 配置)
    info "回退:重启服务..."
    docker compose up -d 2>/dev/null || docker compose restart 2>/dev/null || true

    # 5. 回退后健康检查
    info "回退:等待服务恢复..."
    if wait_backend_healthy 60; then
        echo ""
        echo -e "${GREEN}==========================================${NC}"
        echo -e "${GREEN}  已自动回退到升级前状态${NC}"
        echo -e "${GREEN}==========================================${NC}"
        echo "  快照 ID: $snap_id"
        echo "  服务已恢复运行"
        echo ""
        warn "请检查升级失败原因,修复后重新升级"
        echo ""
    else
        echo ""
        error "回退后服务仍不健康!"
        warn "请手动排查:"
        warn "  1. 查看日志: docker compose logs --tail=100 backend"
        warn "  2. 手动恢复: canteen → 恢复备份 → 选择快照 $snap_id"
        echo ""
    fi
}

#==============================================================
# 主升级流程
#==============================================================
main() {
    # 校验参数
    case "$SCOPE" in
        backend|frontend|all) ;;
        *)
            echo "用法: $0 [backend|frontend|all]"
            exit 1
            ;;
    esac

    echo ""
    echo -e "${BLUE}==========================================${NC}"
    echo -e "${BLUE}  企业智慧食堂系统 - 安全升级${NC}"
    echo -e "${BLUE}==========================================${NC}"
    echo "  当前版本: v$(get_version)"
    echo "  当前分支: ${CURRENT_BRANCH}"
    if [ "$NO_BUILD" = "true" ]; then
        echo "  升级模式: 免构建(deploy 分支,产物已预构建)"
    else
        echo "  升级模式: 本地构建(main 分支,需 build.sh)"
    fi
    echo "  升级范围: ${SCOPE}"
    echo "  升级时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo -e "${BLUE}==========================================${NC}"

    # 步骤总数:免构建模式 5 步,构建模式 6 步
    local total_steps=6
    [ "$NO_BUILD" = "true" ] && total_steps=5

    # 显示版本对比(本地 vs 远程)
    show_version_diff

    # 检查 Docker
    if ! command -v docker &>/dev/null; then
        error "未检测到 Docker,请先安装"
        exit 1
    fi

    #==========================================================
    # 步骤 1:创建升级前快照(关键!)
    #==========================================================
    step "步骤 1/${total_steps} 创建升级前快照"
    chmod +x "$PROJECT_DIR/scripts/snapshot.sh"
    local snap_id
    snap_id=$("$PROJECT_DIR/scripts/snapshot.sh" create "升级前快照(scope=$SCOPE, branch=$CURRENT_BRANCH)") || {
        error "快照创建失败,为安全起见中止升级"
        warn "请检查数据库连接和磁盘空间后重试"
        exit 1
    }
    if [ -z "$snap_id" ]; then
        error "快照创建返回空 ID,中止升级"
        exit 1
    fi
    info "快照已创建: $snap_id"
    echo ""
    echo "  (如升级失败,将自动回退到此快照)"
    echo ""

    #==========================================================
    # 步骤 2:拉取最新代码/产物
    #==========================================================
    if [ "$NO_BUILD" = "true" ]; then
        step "步骤 2/${total_steps} 拉取最新产物"
    else
        step "步骤 2/${total_steps} 拉取最新代码"
    fi
    if [ -d "$PROJECT_DIR/.git" ]; then
        info "执行 git pull (分支: ${CURRENT_BRANCH})..."
        # 显式指定远程和分支,避免 detached HEAD 时 pull 失败
        if git -C "$PROJECT_DIR" pull origin "$CURRENT_BRANCH" 2>/dev/null; then
            info "已更新"
        else
            # git pull 失败不回退(可能是网络问题),但提醒用户
            warn "git pull 失败(可能是网络问题或冲突)"
            if [ "$NO_BUILD" = "true" ]; then
                warn "将使用当前产物继续。如需更新请手动 git pull origin deploy"
            else
                warn "将使用当前代码继续构建。如需更新代码请手动 git pull"
            fi
            read -p "$(echo -e "${CYAN}[?]${NC} 是否继续? [y/N]: ")" cont
            [ "$cont" != "y" ] && [ "$cont" != "Y" ] && {
                info "已取消升级"
                info "快照 $snap_id 已保留,可手动清理"
                exit 0
            }
        fi
    else
        info "非 Git 项目,跳过拉取"
    fi
    # git pull 后脚本可能丢失可执行位(Windows 仓库不保留 +x),统一修复
    chmod +x "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh 2>/dev/null || true
    echo ""

    #==========================================================
    # 步骤 3:构建产物(仅 main 分支,deploy 分支跳过)
    #==========================================================
    local current_step=3
    if [ "$NO_BUILD" = "false" ]; then
        step "步骤 3/${total_steps} 构建产物"
        chmod +x "$PROJECT_DIR/scripts/build.sh"

        local build_failed=false
        case "$SCOPE" in
            backend)
                info "构建后端..."
                "$PROJECT_DIR/scripts/build.sh" backend || build_failed=true
                ;;
            frontend)
                info "构建 admin-web..."
                "$PROJECT_DIR/scripts/build.sh" admin-web || build_failed=true
                if [ "$build_failed" = false ]; then
                    info "构建 h5..."
                    "$PROJECT_DIR/scripts/build.sh" h5 || build_failed=true
                fi
                ;;
            all)
                info "构建全部..."
                "$PROJECT_DIR/scripts/build.sh" all || build_failed=true
                ;;
        esac

        if [ "$build_failed" = true ]; then
            error "构建失败!"
            auto_rollback "$snap_id"
            exit 1
        fi
        info "产物构建完成"
        echo ""
        current_step=4
    else
        info "deploy 分支:产物已预构建,跳过构建步骤"
        # 验证产物存在
        if [ "$SCOPE" = "backend" ] || [ "$SCOPE" = "all" ]; then
            if [ ! -f "$PROJECT_DIR/deploy/backend/app.jar" ]; then
                error "后端产物不存在: deploy/backend/app.jar"
                warn "deploy 分支可能不完整,请检查 git pull 是否成功"
                auto_rollback "$snap_id"
                exit 1
            fi
        fi
        if [ "$SCOPE" = "frontend" ] || [ "$SCOPE" = "all" ]; then
            if [ ! -f "$PROJECT_DIR/deploy/admin-web/html/index.html" ]; then
                error "admin-web 产物不存在: deploy/admin-web/html/index.html"
                auto_rollback "$snap_id"
                exit 1
            fi
            if [ ! -f "$PROJECT_DIR/deploy/h5/html/index.html" ]; then
                error "h5 产物不存在: deploy/h5/html/index.html"
                auto_rollback "$snap_id"
                exit 1
            fi
        fi
        info "产物验证通过"
        echo ""
        current_step=3
    fi

    #==========================================================
    # 步骤:重启服务
    #==========================================================
    step "步骤 ${current_step}/${total_steps} 重启服务"
    info "重启服务(卷映射模式,用 up -d 确保重读配置)..."
    local restart_failed=false
    case "$SCOPE" in
        backend)
            docker compose up -d --no-deps backend || restart_failed=true
            ;;
        frontend)
            docker compose up -d --no-deps admin-web h5 || restart_failed=true
            ;;
        all)
            docker compose up -d || restart_failed=true
            ;;
    esac

    if [ "$restart_failed" = true ]; then
        error "服务重启失败!"
        auto_rollback "$snap_id"
        exit 1
    fi
    info "服务已重启"
    echo ""
    current_step=$((current_step + 1))

    #==========================================================
    # 步骤:健康检查
    #==========================================================
    step "步骤 ${current_step}/${total_steps} 健康检查"

    local health_ok=true

    # 后端检查(仅 backend/all 需要等待后端启动)
    if [ "$SCOPE" = "backend" ] || [ "$SCOPE" = "all" ]; then
        if ! wait_backend_healthy 120; then
            error "后端健康检查超时!"
            health_ok=false
        fi
    fi

    # 前端可达性检查
    if [ "$health_ok" = true ]; then
        for svc in "admin-web:18080" "h5:18081"; do
            name="${svc%%:*}"
            port="${svc##*:}"
            code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/" 2>/dev/null || echo "000")
            if [ "$code" = "200" ]; then
                info "${name} (port ${port}): 正常"
            else
                # 前端失败也触发回退(可能是构建产物为空导致 nginx 403)
                error "${name} (port ${port}): HTTP ${code}(前端不可用)"
                health_ok=false
            fi
        done
    fi
    current_step=$((current_step + 1))

    #==========================================================
    # 步骤:结果处理
    #==========================================================
    if [ "$health_ok" = false ]; then
        step "步骤 ${current_step}/${total_steps} 升级失败 - 自动回退"
        auto_rollback "$snap_id"
        exit 1
    fi

    step "步骤 ${current_step}/${total_steps} 升级成功"

    # 清理旧快照(保留最近 5 个)
    info "清理旧快照(保留最近 5 个)..."
    "$PROJECT_DIR/scripts/snapshot.sh" clean 5 2>/dev/null || true

    echo ""
    echo -e "${GREEN}==========================================${NC}"
    echo -e "${GREEN}  升级完成!${NC}"
    echo -e "${GREEN}==========================================${NC}"
    echo "  旧系统版本: $(cat "$PROJECT_DIR/backup/snapshots/$snap_id/version.txt" 2>/dev/null || echo '?')"
    echo "  新系统版本: v$(get_version)"
    echo ""
    echo "  各模块版本:"
    echo "    后端: v$(get_module_version backend)"
    echo "    管理后台: v$(get_module_version admin-web)"
    echo "    H5订餐端: v$(get_module_version h5)"
    echo ""
    echo "  快照 ID: $snap_id (已保留,可用于回退)"
    echo ""
    echo "  数据库迁移已由 Flyway 自动执行"
    if [ "$NO_BUILD" = "false" ]; then
        echo "  迁移脚本目录: backend/src/main/resources/db/migration/"
    fi
    echo ""
    echo "  如需回退:"
    echo "    canteen → 恢复备份 → 选择 $snap_id"
    echo "    或: ./scripts/snapshot.sh restore $snap_id"
    echo ""
}

main "$@"
