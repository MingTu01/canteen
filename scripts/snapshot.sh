#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 升级快照管理
#==============================================================
# 快照是升级安全链路的核心:每次升级前对数据库 + deploy 产物 + git commit
# 做完整快照,升级失败时可精确回退到升级前状态。
#
# 快照目录结构:
#   backup/snapshots/<timestamp>/
#   ├── database.sql.gz      数据库压缩备份
#   ├── deploy.tar.gz        deploy/ 产物打包(含 jar/dist/nginx.conf)
#   ├── git_commit.txt       升级前的 git commit SHA
#   ├── git_branch.txt       升级前的 git 分支名
#   ├── version.txt          升级前的版本号
#   └── meta.txt             升级元信息(时间、操作者、说明)
#
# 用法:
#   ./scripts/snapshot.sh create [说明]     # 创建快照
#   ./scripts/snapshot.sh list              # 列出所有快照
#   ./scripts/snapshot.sh restore <id>      # 恢复到指定快照
#   ./scripts/snapshot.sh clean [保留数]    # 清理旧快照(默认保留 5 个)
#   ./scripts/snapshot.sh latest            # 输出最新快照 ID
#==============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

SNAPSHOT_DIR="$PROJECT_DIR/backup/snapshots"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[快照]${NC} $1" >&2; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1" >&2; }
error() { echo -e "${RED}[错误]${NC} $1" >&2; }

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

# 兼容旧调用(不再 source,保留空函数避免引用错误)
load_env() {
    :
}

# 获取数据库密码(优先环境变量,其次从 .env 读取)
# P0-2 安全修复:移除弱默认密码,未配置则返回空(调用方需校验)
get_db_pass() {
    local pass="${SPRING_DATASOURCE_PASSWORD:-}"
    if [ -z "$pass" ]; then
        pass=$(read_env_var "SPRING_DATASOURCE_PASSWORD" 2>/dev/null) || pass=""
    fi
    if [ -z "$pass" ]; then
        pass="${MYSQL_ROOT_PASSWORD:-}"
        if [ -z "$pass" ]; then
            pass=$(read_env_var "MYSQL_ROOT_PASSWORD" 2>/dev/null) || pass=""
        fi
    fi
    echo "$pass"
}

# 获取当前版本号
get_current_version() {
    if [ -f "$PROJECT_DIR/backend/src/main/resources/version.json" ]; then
        grep -m1 '"version"' "$PROJECT_DIR/backend/src/main/resources/version.json" 2>/dev/null \
            | sed 's/.*"\([0-9.]*\)".*/\1/' || echo "unknown"
    else
        echo "unknown"
    fi
}

# 检测 Docker MySQL 是否运行
mysql_running() {
    command -v docker &>/dev/null && docker ps 2>/dev/null | grep -q canteen-mysql
}

#==============================================================
# 创建快照
# 用法: snapshot_create [说明]
#==============================================================
snapshot_create() {
    local desc="${1:-手动快照}"
    local ts
    ts=$(date +%Y%m%d_%H%M%S)
    local snap_path="$SNAPSHOT_DIR/$ts"

    info "创建升级前快照: $ts"
    info "说明: $desc"

    # 创建快照目录(失败时输出诊断信息,不因 set -e 直接退出)
    if ! mkdir -p "$snap_path" 2>/dev/null; then
        error "快照目录创建失败(权限不足)"
        warn "  目标路径: $snap_path"
        warn "  当前用户: $(whoami 2>/dev/null || echo unknown) (uid=$(id -u 2>/dev/null || echo '?'))"
        warn "  父目录 backup/ 权限: $(ls -ld "$PROJECT_DIR/backup" 2>/dev/null || echo '不存在')"
        warn ""
        warn "修复方法:"
        warn "  sudo chown -R \$(whoami):\$(whoami) backup/"
        warn "  或: sudo chmod 777 backup/"
        return 1
    fi

    # 1. 数据库备份
    info "备份数据库..."
    load_env
    local db_pass
    db_pass=$(get_db_pass)
    if [ -z "$db_pass" ]; then
        error "数据库密码未配置,请在 .env 中设置 MYSQL_ROOT_PASSWORD 或 SPRING_DATASOURCE_PASSWORD"
        rm -rf "$snap_path"
        return 1
    fi
    local db_name="${MYSQL_DATABASE:-}"
    if [ -z "$db_name" ]; then
        db_name=$(read_env_var "MYSQL_DATABASE" 2>/dev/null) || db_name="canteen"
    fi

    if mysql_running; then
        # P0 修复:去掉 sh -c,避免容器内 shell 对密码二次展开($ 被解析)
        # 直接通过 docker exec 参数传递,密码由本机 shell 展开后作为单个 argv 传入 mysqldump
        set -o pipefail
        docker exec canteen-mysql mysqldump -uroot -p"${db_pass}" \
            --single-transaction --routines --triggers --events "${db_name}" 2>/dev/null \
            | gzip > "$snap_path/database.sql.gz"
        local dump_rc=$?
        set +o pipefail

        if [ $dump_rc -ne 0 ] || [ ! -s "$snap_path/database.sql.gz" ]; then
            error "数据库备份失败(mysqldump退出码: $dump_rc)"
            rm -rf "$snap_path"
            return 1
        fi
        # 额外验证gzip完整性,防止管道中断产生损坏文件
        if ! gzip -t "$snap_path/database.sql.gz" 2>/dev/null; then
            error "数据库备份文件损坏(gzip校验失败)"
            rm -rf "$snap_path"
            return 1
        fi
        info "数据库备份完成 ($(du -h "$snap_path/database.sql.gz" | cut -f1))"
    else
        warn "MySQL 容器未运行,跳过数据库备份"
        touch "$snap_path/database.sql.gz"
    fi

    # 2. deploy/ 产物快照
    info "备份 deploy/ 产物..."
    if [ -d "$PROJECT_DIR/deploy" ]; then
        tar -czf "$snap_path/deploy.tar.gz" -C "$PROJECT_DIR" deploy/ 2>/dev/null
        info "产物快照完成 ($(du -h "$snap_path/deploy.tar.gz" | cut -f1))"
    else
        warn "deploy/ 目录不存在,跳过产物快照"
        touch "$snap_path/deploy.tar.gz"
    fi

    # 3. Git 状态记录
    if [ -d "$PROJECT_DIR/.git" ]; then
        git -C "$PROJECT_DIR" rev-parse HEAD > "$snap_path/git_commit.txt" 2>/dev/null || echo "unknown" > "$snap_path/git_commit.txt"
        git -C "$PROJECT_DIR" rev-parse --abbrev-ref HEAD > "$snap_path/git_branch.txt" 2>/dev/null || echo "main" > "$snap_path/git_branch.txt"
    else
        echo "nongit" > "$snap_path/git_commit.txt"
        echo "nongit" > "$snap_path/git_branch.txt"
    fi

    # 4. 版本号
    get_current_version > "$snap_path/version.txt"

    # 5. 元信息
    cat > "$snap_path/meta.txt" <<EOF
timestamp=$ts
datetime=$(date '+%Y-%m-%d %H:%M:%S')
description=$desc
version_before=$(get_current_version)
operator=$(whoami 2>/dev/null || echo unknown)
EOF

    info "快照创建完成: $snap_path"
    echo "$ts"
    return 0
}

#==============================================================
# 列出所有快照
#==============================================================
snapshot_list() {
    if [ ! -d "$SNAPSHOT_DIR" ] || [ -z "$(ls -A "$SNAPSHOT_DIR" 2>/dev/null)" ]; then
        info "暂无快照"
        return 0
    fi

    echo ""
    echo -e "${BLUE}==========================================${NC}"
    echo -e "${BLUE}  升级快照列表${NC}"
    echo -e "${BLUE}==========================================${NC}"
    echo ""

    local idx=0
    for snap in "$SNAPSHOT_DIR"/*/; do
        [ -d "$snap" ] || continue
        local id
        id=$(basename "$snap")
        local version="?"
        local desc="?"
        local datetime="?"

        if [ -f "$snap/version.txt" ]; then
            version=$(cat "$snap/version.txt")
        fi
        if [ -f "$snap/meta.txt" ]; then
            desc=$(grep "^description=" "$snap/meta.txt" 2>/dev/null | cut -d= -f2-)
            datetime=$(grep "^datetime=" "$snap/meta.txt" 2>/dev/null | cut -d= -f2-)
        fi

        # 格式化时间戳显示
        local display_ts
        display_ts=$(echo "$id" | sed 's/\([0-9]\{8\}\)_\([0-9]\{6\}\)/\1 \2/' | \
            awk '{print substr($1,1,4)"-"substr($1,5,2)"-"substr($1,7,2)" "substr($2,1,2)":"substr($2,3,2)":"substr($2,5,2)}')

        local size="?"
        size=$(du -sh "$snap" 2>/dev/null | cut -f1)

        printf "  [%d] %s  v%s  %s  (%s)\n" "$idx" "$display_ts" "$version" "$desc" "$size"
        printf "      ID: %s\n" "$id"
        idx=$((idx + 1))
    done

    echo ""
    info "共 $idx 个快照"
    echo ""
}

#==============================================================
# 按序号或 ID 获取快照路径
# 用法: snapshot_resolve <序号或ID> -> 输出路径到 stdout
#==============================================================
snapshot_resolve() {
    local input="$1"
    if [ -z "$input" ]; then
        error "请指定快照序号或 ID"
        return 1
    fi

    # 如果是纯数字,按序号查找
    if [[ "$input" =~ ^[0-9]+$ ]]; then
        local idx=0
        for snap in "$SNAPSHOT_DIR"/*/; do
            [ -d "$snap" ] || continue
            if [ "$idx" -eq "$input" ]; then
                echo "$(cd "$snap" && pwd)"
                return 0
            fi
            idx=$((idx + 1))
        done
        error "序号 $input 不存在"
        return 1
    fi

    # 按 ID 查找
    local path="$SNAPSHOT_DIR/$input"
    if [ -d "$path" ]; then
        echo "$path"
        return 0
    fi

    error "快照 ID '$input' 不存在"
    return 1
}

#==============================================================
# 恢复快照
# 用法: snapshot_restore <序号或ID>
#==============================================================
snapshot_restore() {
    local snap_path
    snap_path=$(snapshot_resolve "$1") || return 1

    local id
    id=$(basename "$snap_path")

    echo ""
    echo -e "${BLUE}==========================================${NC}"
    echo -e "${BLUE}  恢复快照${NC}"
    echo -e "${BLUE}==========================================${NC}"
    cat "$snap_path/meta.txt" 2>/dev/null | sed 's/^/  /'
    echo ""
    warn "此操作将:"
    warn "  1. 恢复数据库到快照时间点(当前数据将被覆盖)"
    warn "  2. 恢复 deploy/ 产物到快照时间点"
    if [ -f "$snap_path/git_commit.txt" ]; then
        local commit
        commit=$(cat "$snap_path/git_commit.txt")
        if [ "$commit" != "nongit" ]; then
            warn "  3. 回退代码到 commit: ${commit:0:12}"
        fi
    fi
    warn "  4. 重启所有服务"
    echo ""

    read -p "$(echo -e "${CYAN}[?]${NC} 确认恢复? 输入 yes 继续: ")" confirm
    if [ "$confirm" != "yes" ]; then
        info "已取消"
        return 1
    fi

    load_env
    local db_pass
    db_pass=$(get_db_pass)
    local db_name="${MYSQL_DATABASE:-}"
    if [ -z "$db_name" ]; then
        db_name=$(read_env_var "MYSQL_DATABASE" 2>/dev/null) || db_name="canteen"
    fi

    # P1 修复:停止后端服务,避免恢复过程中后端写入新数据导致状态不一致
    info "停止后端服务(避免恢复过程中写入数据)..."
    docker compose stop backend 2>/dev/null || true

    # 1. 恢复数据库(检查退出码,失败不报告成功)
    if [ -s "$snap_path/database.sql.gz" ]; then
        info "恢复数据库..."
        if mysql_running; then
            set -o pipefail
            if gunzip -c "$snap_path/database.sql.gz" | \
                docker exec -i canteen-mysql mysql -uroot -p"${db_pass}" "${db_name}" 2>/dev/null; then
                info "数据库恢复完成"
            else
                error "数据库恢复失败(密码错误或 SQL 执行异常)"
                set +o pipefail
                return 1
            fi
            set +o pipefail
        else
            error "MySQL 容器未运行,无法恢复数据库"
            warn "请先启动服务: docker compose up -d mysql"
            return 1
        fi
    else
        warn "快照无数据库备份,跳过"
    fi

    # 2. 恢复 deploy/ 产物(先解压到临时目录,成功后再替换)
    if [ -s "$snap_path/deploy.tar.gz" ]; then
        info "恢复 deploy/ 产物..."
        local tmp_extract
        tmp_extract=$(mktemp -d)
        if tar -xzf "$snap_path/deploy.tar.gz" -C "$tmp_extract" 2>/dev/null; then
            rm -rf "$PROJECT_DIR/deploy"
            mv "$tmp_extract/deploy" "$PROJECT_DIR/deploy" 2>/dev/null || cp -r "$tmp_extract/deploy" "$PROJECT_DIR/deploy"
            rm -rf "$tmp_extract"
            info "产物恢复完成"
        else
            rm -rf "$tmp_extract"
            error "产物恢复失败(deploy.tar.gz 可能损坏),deploy 目录未修改"
            return 1
        fi
    else
        warn "快照无产物备份,跳过"
    fi

    # 3. 回退代码(用 git reset --hard 保持分支上下文,避免 detached HEAD)
    if [ -f "$snap_path/git_commit.txt" ]; then
        local commit
        commit=$(cat "$snap_path/git_commit.txt")
        if [ "$commit" != "nongit" ] && [ -d "$PROJECT_DIR/.git" ]; then
            info "回退代码到 ${commit:0:12}(git reset --hard,保持分支上下文)..."
            if ! git -C "$PROJECT_DIR" reset --hard "$commit" 2>/dev/null; then
                error "代码回退失败,中止恢复流程"
                warn "数据库和产物已恢复,但代码仍是当前版本"
                warn "请手动执行:git -C $PROJECT_DIR reset --hard $commit"
                return 1
            fi
            info "代码已回退"
        fi
    fi

    # 4. 重启服务(用 up -d 而非 restart,确保重读配置)
    info "重启服务..."
    docker compose up -d 2>/dev/null || docker compose restart 2>/dev/null || true

    # 5. 健康检查
    info "等待后端启动..."
    for i in $(seq 1 60); do
        if curl -sf http://localhost:18082/api/system/health >/dev/null 2>&1; then
            info "后端已恢复健康"
            echo ""
            info "快照恢复完成!"
            return 0
        fi
        sleep 2
        printf "."
    done
    echo ""
    warn "后端启动较慢,请稍后检查: ./deploy.sh status"
    return 0
}

#==============================================================
# 清理旧快照(默认保留最近 5 个)
#==============================================================
snapshot_clean() {
    local keep="${1:-5}"

    if [ ! -d "$SNAPSHOT_DIR" ]; then
        info "暂无快照"
        return 0
    fi

    # 按名称(时间戳)降序排列,跳过前 keep 个,删除其余
    local removed=0
    local all_snaps=()
    for snap in "$SNAPSHOT_DIR"/*/; do
        [ -d "$snap" ] && all_snaps+=("$(basename "$snap")")
    done

    # 降序排序
    IFS=$'\n' sorted=($(sort -r <<<"${all_snaps[*]}")); unset IFS

    local total=${#sorted[@]}
    if [ "$total" -le "$keep" ]; then
        info "共 $total 个快照,无需清理(保留 $keep 个)"
        return 0
    fi

    local idx=0
    for id in "${sorted[@]}"; do
        idx=$((idx + 1))
        if [ "$idx" -gt "$keep" ]; then
            rm -rf "$SNAPSHOT_DIR/$id"
            removed=$((removed + 1))
        fi
    done

    info "已清理 $removed 个旧快照(保留最近 $keep 个)"
}

#==============================================================
# 输出最新快照 ID
#==============================================================
snapshot_latest() {
    if [ ! -d "$SNAPSHOT_DIR" ]; then
        return 0
    fi
    local latest=""
    for snap in "$SNAPSHOT_DIR"/*/; do
        [ -d "$snap" ] || continue
        local id
        id=$(basename "$snap")
        if [ -z "$latest" ] || [[ "$id" > "$latest" ]]; then
            latest="$id"
        fi
    done
    [ -n "$latest" ] && echo "$latest"
}

#==============================================================
# 主入口
#==============================================================
ACTION="${1:-help}"
shift 2>/dev/null || true

case "$ACTION" in
    create)
        snapshot_create "$@"
        ;;
    list|ls)
        snapshot_list
        ;;
    restore|rollback)
        snapshot_restore "$@"
        ;;
    clean)
        snapshot_clean "$@"
        ;;
    latest)
        snapshot_latest
        ;;
    help|-h|--help)
        echo "用法: ./scripts/snapshot.sh <命令>"
        echo ""
        echo "命令:"
        echo "  create [说明]      创建快照"
        echo "  list               列出所有快照"
        echo "  restore <序号|ID>  恢复到指定快照"
        echo "  clean [保留数]     清理旧快照(默认保留 5 个)"
        echo "  latest             输出最新快照 ID"
        ;;
    *)
        error "未知命令: $ACTION"
        echo "运行 ./scripts/snapshot.sh help 查看可用命令"
        exit 1
        ;;
esac
