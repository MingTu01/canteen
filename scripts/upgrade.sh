#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 安全升级脚本
#==============================================================
# 升级流程(带回退保护):
#   1. 升级前快照(数据库 + deploy 产物 + git commit)
#   2. git pull 拉取最新代码
#   3. build.sh 重建产物
#   4. docker compose restart 重启服务
#   5. 健康检查(等待 120s)
#   6. 失败则自动回退到快照状态
#   7. 成功则清理旧快照(保留最近 5 个)
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

# 颜色
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

# 获取当前版本号
get_version() {
    if [ -f "$PROJECT_DIR/backend/src/main/resources/version.json" ]; then
        grep -m1 '"version"' "$PROJECT_DIR/backend/src/main/resources/version.json" 2>/dev/null \
            | sed 's/.*"\([0-9.]*\)".*/\1/' || echo "unknown"
    else
        echo "unknown"
    fi
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

    # 加载 .env
    if [ -f "$PROJECT_DIR/.env" ]; then
        set -a; . "$PROJECT_DIR/.env" 2>/dev/null || true; set +a
    fi
    local db_pass="${SPRING_DATASOURCE_PASSWORD:-${MYSQL_ROOT_PASSWORD:-canteen2026}}"
    local db_name="${MYSQL_DATABASE:-canteen}"

    # 1. 恢复 deploy/ 产物(先解压到临时目录,成功后再替换,避免 rm -rf 后 tar 失败导致产物丢失)
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

    # 3. 回退代码(失败时中止回退流程,避免代码与数据库版本不一致)
    if [ -f "$snap_path/git_commit.txt" ]; then
        local commit
        commit=$(cat "$snap_path/git_commit.txt")
        if [ "$commit" != "nongit" ] && [ -d "$PROJECT_DIR/.git" ]; then
            info "回退:代码回退到 ${commit:0:12}..."
            if git -C "$PROJECT_DIR" checkout "$commit" 2>/dev/null; then
                info "代码已回退"
            else
                error "代码回退失败,中止回退流程避免状态不一致"
                warn "数据库和产物已恢复到快照状态,但代码仍是当前版本"
                warn "请手动执行:git -C $PROJECT_DIR checkout $commit"
                warn "完成后重启服务:docker compose up -d"
                return 1
            fi
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
    echo "  当前版本: $(get_version)"
    echo "  升级范围: ${SCOPE}"
    echo "  升级时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo -e "${BLUE}==========================================${NC}"

    # 检查 Docker
    if ! command -v docker &>/dev/null; then
        error "未检测到 Docker,请先安装"
        exit 1
    fi

    #==========================================================
    # 步骤 1:创建升级前快照(关键!)
    #==========================================================
    step "步骤 1/6 创建升级前快照"
    chmod +x "$PROJECT_DIR/scripts/snapshot.sh"
    local snap_id
    snap_id=$("$PROJECT_DIR/scripts/snapshot.sh" create "升级前快照(scope=$SCOPE)") || {
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
    # 步骤 2:拉取最新代码
    #==========================================================
    step "步骤 2/6 拉取最新代码"
    if [ -d "$PROJECT_DIR/.git" ]; then
        info "执行 git pull..."
        if git -C "$PROJECT_DIR" pull 2>/dev/null; then
            info "代码已更新"
        else
            # git pull 失败不回退(可能是网络问题),但提醒用户
            warn "git pull 失败(可能是网络问题或冲突)"
            warn "将使用当前代码继续构建。如需更新代码请手动 git pull"
            read -p "$(echo -e "${CYAN}[?]${NC} 是否继续? [y/N]: ")" cont
            [ "$cont" != "y" ] && [ "$cont" != "Y" ] && {
                info "已取消升级"
                info "快照 $snap_id 已保留,可手动清理"
                exit 0
            }
        fi
    else
        info "非 Git 项目,跳过代码拉取"
    fi
    # git pull 后脚本可能丢失可执行位(Windows 仓库不保留 +x),统一修复
    chmod +x "$PROJECT_DIR"/*.sh "$PROJECT_DIR"/scripts/*.sh 2>/dev/null || true
    echo ""

    #==========================================================
    # 步骤 3:构建产物
    #==========================================================
    step "步骤 3/6 构建产物"
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

    #==========================================================
    # 步骤 4:重启服务
    #==========================================================
    step "步骤 4/6 重启服务"
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

    #==========================================================
    # 步骤 5:健康检查
    #==========================================================
    step "步骤 5/6 健康检查"

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
                # 前端失败不触发回退(可能只是 nginx 缓存问题),仅警告
                warn "${name} (port ${port}): HTTP ${code}(可能需要等待)"
            fi
        done
    fi

    #==========================================================
    # 步骤 6:结果处理
    #==========================================================
    if [ "$health_ok" = false ]; then
        step "步骤 6/6 升级失败 - 自动回退"
        auto_rollback "$snap_id"
        exit 1
    fi

    step "步骤 6/6 升级成功"

    # 清理旧快照(保留最近 5 个)
    info "清理旧快照(保留最近 5 个)..."
    "$PROJECT_DIR/scripts/snapshot.sh" clean 5 2>/dev/null || true

    echo ""
    echo -e "${GREEN}==========================================${NC}"
    echo -e "${GREEN}  升级完成!${NC}"
    echo -e "${GREEN}==========================================${NC}"
    echo "  旧版本: $(cat "$PROJECT_DIR/backup/snapshots/$snap_id/version.txt" 2>/dev/null || echo '?')"
    echo "  新版本: $(get_version)"
    echo "  快照 ID: $snap_id (已保留,可用于回退)"
    echo ""
    echo "  数据库迁移已由 Flyway 自动执行"
    echo "  迁移脚本目录: backend/src/main/resources/db/migration/"
    echo ""
    echo "  如需回退:"
    echo "    canteen → 恢复备份 → 选择 $snap_id"
    echo "    或: ./scripts/snapshot.sh restore $snap_id"
    echo ""
}

main "$@"
