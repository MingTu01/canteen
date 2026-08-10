#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ==============================================================
# 企业智慧食堂系统 - 数据库/项目自检自愈脚本
# ==============================================================
# 功能:
#   1. 自检:检查 Docker/Compose、各服务容器状态、MySQL 是否 crash-loop
#      (数据损坏)、项目关键文件是否缺失/损坏、.env 是否完整。
#   2. 自动修复:
#      - MySQL 数据损坏:备份优先(从最新快照/备份恢复),无备份则重建空库。
#      - 项目关键文件缺失/损坏:配置 GitHub 加速器后自动从远程拉取修复。
#
# 用法:
#   python3 scripts/self_heal.py check         仅自检,输出问题清单,不修复
#   python3 scripts/self_heal.py fix           自检 + 自动修复(交互确认)
#   python3 scripts/self_heal.py fix-mysql     仅修复 MySQL
#   python3 scripts/self_heal.py fix-files     仅修复项目文件
#   python3 scripts/self_heal.py fix --yes     自检 + 自动修复(跳过交互确认)
# ==============================================================

import os
import re
import sys
import glob
import shutil
import subprocess
import argparse
from datetime import datetime

# ==============================================================
# 常量
# ==============================================================
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)

MYSQL_CONTAINER = "canteen-mysql"
COMPOSE_FILE = os.path.join(PROJECT_DIR, "docker-compose.yml")
ENV_FILE = os.path.join(PROJECT_DIR, ".env")

# 项目关键文件(缺失或为空即视为损坏,需从 GitHub 拉取修复)
CRITICAL_FILES = [
    "docker-compose.yml",
    "deploy.sh",
    "canteen.sh",
    "scripts/init-db-user.sh",
    "scripts/backup.sh",
    "scripts/snapshot.sh",
    "deploy/backend/app.jar",
    "deploy/admin-web/html/index.html",
    "deploy/h5/html/index.html",
]

# MySQL 数据损坏特征关键词(出现在容器日志中说明数据文件损坏)
CORRUPT_KEYWORDS = [
    "InnoDB: Corruption",
    "InnoDB: Database page corruption",
    "InnoDB: Page .* corrupted",
    "corrupted",
    "repair with mysqld --innodb-force-recovery",
    "Cannot open table",
    "data dictionary",
    "table does not exist",
]

# GitHub 加速器(国内服务器直连 GitHub 会超时)
GITHUB_PROXIES = [
    "https://api.gitproxy.dev/https://github.com/",
    "https://gh-proxy.com/https://github.com/",
    "https://ghfast.top/https://github.com/",
]

GREEN = "\033[0;32m"
YELLOW = "\033[1;33m"
RED = "\033[0;31m"
CYAN = "\033[0;36m"
NC = "\033[0m"


# ==============================================================
# 工具函数
# ==============================================================
def info(msg):
    print(GREEN + "[自愈]" + NC + " " + msg)


def warn(msg):
    print(YELLOW + "[警告]" + NC + " " + msg)


def error(msg):
    print(RED + "[错误]" + NC + " " + msg)


def title(msg):
    print("")
    print(CYAN + "========== " + msg + " ==========" + NC)


def confirm(msg, default=False):
    """交互确认,返回 bool。default=True 时默认 yes。"""
    if getattr(confirm, "auto_yes", False):
        return True
    suffix = " [Y/n]: " if default else " [y/N]: "
    try:
        ans = input(CYAN + "[?] " + NC + msg + suffix).strip().lower()
    except EOFError:
        return default
    if ans == "":
        return default
    return ans in ("y", "yes")


def run(cmd, cwd=None, check=False, timeout=180):
    """执行命令,返回 (rc, stdout, stderr)。"""
    try:
        proc = subprocess.run(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            timeout=timeout,
        )
        return proc.returncode, proc.stdout, proc.stderr
    except subprocess.TimeoutExpired:
        return 124, "", "timeout"
    except FileNotFoundError:
        return 127, "", "command not found: %s" % " ".join(cmd)


def read_env(key):
    """安全读取 .env 变量(不做 shell 展开)。"""
    try:
        with open(ENV_FILE, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if re.match(r"^%s=" % re.escape(key), line):
                    val = line.split("=", 1)[1].strip()
                    if len(val) >= 2 and val[0] == val[-1] and val[0] in ("'", '"'):
                        val = val[1:-1]
                    return val
    except Exception:
        pass
    return None


def has_cmd(name):
    rc, _, _ = run(["which", name])
    return rc == 0


def docker_available():
    rc, _, _ = run(["docker", "version", "--format", "{{.Server.Version}}"])
    return rc == 0


def compose_available():
    rc, _, _ = run(["docker", "compose", "version"])
    return rc == 0


def container_state(name):
    """返回容器状态字典,不存在返回 None。"""
    cmd = ["docker", "inspect", "-f",
           "{{.State.Status}}|{{.RestartCount}}|{{.State.Health.Status}}|{{.State.Running}}",
           name]
    rc, out, _ = run(cmd)
    if rc != 0:
        return None
    parts = out.strip().split("|")
    return {
        "status": parts[0] if len(parts) > 0 else "?",
        "restart_count": int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0,
        "health": parts[2] if len(parts) > 2 else "?",
        "running": parts[3] if len(parts) > 3 else "?",
    }


def container_logs(name, tail=200):
    rc, out, _ = run(["docker", "logs", "--tail", str(tail), name])
    return out if rc == 0 else ""


def mysql_volume_name():
    """通过 docker compose config 解析 MySQL 数据卷名。"""
    rc, out, _ = run(["docker", "compose", "config", "--volumes"], cwd=PROJECT_DIR)
    if rc == 0:
        for line in out.splitlines():
            line = line.strip()
            if line.endswith("mysql_data"):
                prefix = line[: -len("mysql_data")]
                return prefix + "mysql_data"
            if "mysql_data" in line and ":" in line:
                return line
    # 兜底:从现有卷中查找
    rc2, out2, _ = run(["docker", "volume", "ls", "--format", "{{.Name}}"])
    if rc2 == 0:
        for v in out2.splitlines():
            if v.endswith("mysql_data"):
                return v
    return None


# ==============================================================
# 自检:MySQL 数据损坏检测
# ==============================================================
def check_mysql():
    """检测 MySQL 是否 crash-loop / 数据损坏。返回 (问题描述, 严重级别)。"""
    if not docker_available():
        return ("Docker 不可用,无法检查 MySQL", "fatal")

    if not has_cmd("docker"):
        return ("docker 命令不存在", "fatal")

    state = container_state(MYSQL_CONTAINER)
    if state is None:
        rc, _, _ = run(["docker", "ps", "-a", "--filter",
                        "name=" + MYSQL_CONTAINER, "--format", "{{.ID}}"])
        if rc == 0 and "".join(run(["docker", "ps", "-a", "--filter",
                                    "name=" + MYSQL_CONTAINER, "--format", "{{.ID}}"])[1]).strip():
            return ("MySQL 容器存在但状态未知(inspect 失败)", "medium")
        return ("MySQL 容器不存在", "medium")

    status = state["status"]
    health = state["health"]
    restart = state["restart_count"]

    # crash-loop:状态是 restarting,或重启次数异常高
    if status == "restarting":
        logs = container_logs(MYSQL_CONTAINER)
        for kw in CORRUPT_KEYWORDS:
            if re.search(kw, logs, re.IGNORECASE):
                return ("MySQL 疯狂重启且日志含数据损坏特征(%s)" % kw, "critical")
        return ("MySQL 容器处于 restarting 状态(可能数据损坏)", "critical")

    if status == "exited":
        return ("MySQL 容器已退出(exit)", "critical")

    if status == "running" and health == "unhealthy":
        logs = container_logs(MYSQL_CONTAINER)
        for kw in CORRUPT_KEYWORDS:
            if re.search(kw, logs, re.IGNORECASE):
                return ("MySQL running 但 unhealthy,日志含数据损坏特征(%s)" % kw, "critical")
        return ("MySQL running 但 health 检查不通过", "medium")

    if status == "running" and restart >= 3:
        return ("MySQL 重启次数较高(%d),需关注" % restart, "low")

    # 检查数据卷是否存在(空卷说明从未初始化或已丢失)
    vol = mysql_volume_name()
    if vol is None:
        return ("MySQL 数据卷不存在", "low")

    return ("MySQL 正常", "ok")


# ==============================================================
# 自检:项目文件完整性
# ==============================================================
def check_files():
    """检查项目关键文件是否缺失/损坏。返回 (缺失列表, 描述文本)。"""
    missing = []
    for rel in CRITICAL_FILES:
        path = os.path.join(PROJECT_DIR, rel)
        if not os.path.exists(path):
            missing.append(rel + " (缺失)")
        elif os.path.getsize(path) == 0:
            missing.append(rel + " (空文件)")
    if not missing:
        return ([], "项目关键文件完整")
    return (missing, "以下项目文件缺失或损坏: %s" % "; ".join(missing))


def check_env():
    """检查 .env 是否完整。"""
    if not os.path.exists(ENV_FILE):
        return "缺少 .env 配置文件"
    need = ["MYSQL_ROOT_PASSWORD", "REDIS_PASSWORD", "JWT_SECRET"]
    missing = [k for k in need if not read_env(k)]
    if missing:
        return ".env 缺少必要配置: %s" % ", ".join(missing)
    return ""


# ==============================================================
# 自检:服务容器状态
# ==============================================================
def check_services():
    """检查各服务容器状态。返回问题列表。

    用 docker compose ps 动态读取真实服务名/容器名/状态,避免硬编码
    容器名与实际部署不一致(或 docker inspect 异常)导致误报"不存在"。
    """
    problems = []
    rc, out, err = run(["docker", "compose", "ps", "-a",
                        "--format", "{{.Service}}|{{.Name}}|{{.State}}|{{.Health}}"],
                       cwd=PROJECT_DIR)
    if rc != 0:
        problems.append("无法读取容器状态(docker compose ps 失败): %s"
                        % (err or out).strip())
        return problems

    containers = {}
    for line in out.splitlines():
        parts = line.strip().split("|")
        if len(parts) < 4 or not parts[0]:
            continue
        containers[parts[0]] = {
            "name": parts[1].strip(),
            "state": parts[2].strip(),
            "health": parts[3].strip(),
        }

    for svc in ["mysql", "redis", "backend", "admin-web", "h5"]:
        c = containers.get(svc)
        if c is None:
            problems.append("%s 容器不存在" % svc)
        elif c["state"] != "running":
            problems.append("%s 未运行(status=%s)" % (svc, c["state"]))
        elif c["health"] not in ("healthy", "n/a", ""):
            problems.append("%s 不健康(health=%s)" % (svc, c["health"]))
    return problems


# ==============================================================
# 修复:MySQL 数据损坏(备份优先,无备份则重建)
# ==============================================================
def find_latest_snapshot():
    """返回最新快照目录(含 database.sql.gz),无则 None。"""
    snap_root = os.path.join(PROJECT_DIR, "backup", "snapshots")
    if not os.path.isdir(snap_root):
        return None
    snaps = sorted([d for d in glob.glob(os.path.join(snap_root, "*"))
                    if os.path.isdir(d)], reverse=True)
    for snap in snaps:
        if os.path.isfile(os.path.join(snap, "database.sql.gz")) and \
                os.path.getsize(os.path.join(snap, "database.sql.gz")) > 0:
            return snap
    return None


def find_latest_backup():
    """返回最新独立备份文件(.tar.gz/.tar.gz.enc),无则 None。"""
    backup_dir = os.path.join(PROJECT_DIR, "backup")
    if not os.path.isdir(backup_dir):
        return None
    files = glob.glob(os.path.join(backup_dir, "*.tar.gz")) + \
            glob.glob(os.path.join(backup_dir, "*.tar.gz.enc"))
    if not files:
        return None
    # 按修改时间取最新
    return max(files, key=os.path.getmtime)


def restore_from_snapshot(snap_path, db_pass, db_name):
    """从快照恢复数据库。返回 bool。"""
    info("从快照恢复数据库: %s" % os.path.basename(snap_path))
    gz = os.path.join(snap_path, "database.sql.gz")
    if not os.path.isfile(gz):
        return False
    # 校验 gzip 完整性
    rc, _, _ = run(["gzip", "-t", gz])
    if rc != 0:
        warn("快照 gzip 校验失败,跳过")
        return False
    cmd = "gunzip -c %s | docker exec -i %s mysql -uroot -p%s %s" % (
        gz, MYSQL_CONTAINER, db_pass, db_name)
    rc, out, err = run(["bash", "-c", cmd])
    if rc != 0:
        warn("快照恢复失败: %s" % err.strip())
        return False
    return True


def restore_from_backup_file(backup_file, db_pass, db_name, enc_key):
    """从独立备份文件恢复。返回 bool。"""
    info("从备份文件恢复: %s" % os.path.basename(backup_file))
    tmp = subprocess.check_output(["mktemp", "-d"], universal_newlines=True).strip()
    try:
        if backup_file.endswith(".tar.gz.enc"):
            if not enc_key:
                warn("加密备份缺少 BACKUP_ENCRYPTION_KEY,跳过")
                return False
            rc, _, err = run(["bash", "-c",
                              "openssl enc -d -aes-256-cbc -pbkdf2 -pass pass:%s -in %s | tar -xzf - -C %s"
                              % (enc_key, backup_file, tmp)])
            if rc != 0:
                warn("解密备份失败: %s" % err.strip())
                return False
        else:
            rc, _, err = run(["tar", "-xzf", backup_file, "-C", tmp])
            if rc != 0:
                warn("解压备份失败: %s" % err.strip())
                return False
        sql_files = [f for f in glob.glob(os.path.join(tmp, "**", "database.sql"),
                                          recursive=True)]
        if not sql_files:
            warn("备份中未找到 database.sql")
            return False
        sql_file = sql_files[0]
        cmd = "docker exec -i %s mysql -uroot -p%s %s < %s" % (
            MYSQL_CONTAINER, db_pass, db_name, sql_file)
        rc, _, err = run(["bash", "-c", cmd])
        if rc != 0:
            warn("备份恢复失败: %s" % err.strip())
            return False
        return True
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def repair_mysql():
    """修复 MySQL:备份损坏数据 → 备份优先恢复 → 无备份重建空库。"""
    title("修复 MySQL")
    snap = find_latest_snapshot()
    backup_file = find_latest_backup()
    enc_key = read_env("BACKUP_ENCRYPTION_KEY")
    db_pass = read_env("MYSQL_ROOT_PASSWORD") or read_env("SPRING_DATASOURCE_PASSWORD")
    if not db_pass:
        error("数据库密码未配置,无法修复")
        return False

    # 1. 停止所有服务(避免后端写入新数据)
    info("停止所有服务...")
    run(["docker", "compose", "down"], cwd=PROJECT_DIR)

    # 2. 备份损坏数据目录(尽量抢救,不依赖 MySQL 能启动)
    vol = mysql_volume_name()
    if not vol:
        warn("未找到 MySQL 数据卷,可能尚未初始化")
    else:
        backup_dir = os.path.join(PROJECT_DIR, "backup")
        os.makedirs(backup_dir, exist_ok=True)
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        corrupted_gz = os.path.join(backup_dir, "corrupted_data_%s.tar.gz" % ts)
        info("抢救损坏数据目录到 %s ..." % os.path.basename(corrupted_gz))
        cmd = ["docker", "run", "--rm",
               "-v", "%s:/var/lib/mysql" % vol,
               "-v", "%s:/backup" % backup_dir,
               "alpine", "sh", "-c",
               "tar czf /backup/%s -C /var/lib/mysql . 2>/dev/null || true"
               % os.path.basename(corrupted_gz)]
        rc, _, _ = run(cmd, timeout=600)
        if rc != 0 or not os.path.exists(corrupted_gz):
            warn("抢救数据失败(可能已无法读取),继续重建流程")
        else:
            info("损坏数据已抢救备份")

    # 3. 删除数据卷,让 MySQL 重新初始化
    if vol:
        info("删除损坏的 MySQL 数据卷: %s" % vol)
        rc, _, err = run(["docker", "volume", "rm", "-f", vol])
        if rc != 0:
            warn("删除数据卷失败(可能仍被占用): %s" % err.strip())

    # 4. 启动 MySQL + Redis,等待健康
    info("启动 MySQL...")
    run(["docker", "compose", "up", "-d", "mysql", "redis"], cwd=PROJECT_DIR)
    info("等待 MySQL 健康检查通过...")
    healthy = wait_mysql_healthy(timeout=180)
    if not healthy:
        error("MySQL 重建后仍无法启动,请手动检查: docker compose logs mysql")
        return False
    info("MySQL 已健康")

    # 5. 恢复数据:先快照,再独立备份;都没有则重建空库
    db_name = read_env("MYSQL_DATABASE") or "canteen"
    restored = False
    if snap:
        restored = restore_from_snapshot(snap, db_pass, db_name)
        if not restored and backup_file:
            restored = restore_from_backup_file(backup_file, db_pass, db_name, enc_key)
    elif backup_file:
        restored = restore_from_backup_file(backup_file, db_pass, db_name, enc_key)

    if restored:
        info("数据已从备份/快照恢复")
    else:
        warn("无可用备份(或恢复失败),重建为空库")

    # 6. 重建应用专用用户
    init_script = os.path.join(SCRIPT_DIR, "init-db-user.sh")
    if os.path.exists(init_script):
        info("创建 MySQL 应用专用用户...")
        run(["bash", init_script], cwd=PROJECT_DIR)

    # 7. 启动全部服务
    info("启动全部服务...")
    rc, _, err = run(["docker", "compose", "up", "-d"], cwd=PROJECT_DIR)
    if rc != 0:
        warn("启动服务出错: %s" % err.strip())

    info("MySQL 修复流程完成")
    return True


# ==============================================================
# 修复:项目文件缺失/损坏(从 GitHub 拉取)
# ==============================================================
def wait_mysql_healthy(timeout=180):
    import time
    for _ in range(int(timeout / 3)):
        st = container_state(MYSQL_CONTAINER)
        if st and st["status"] == "running" and st["health"] == "healthy":
            return True
        time.sleep(3)
    return False


def repair_files():
    """修复缺失/损坏的项目文件:从 GitHub 拉取。"""
    title("修复项目文件")
    git_dir = os.path.join(PROJECT_DIR, ".git")
    if not os.path.isdir(git_dir):
        error("项目不是 Git 仓库,无法自动拉取,请手动重新部署")
        return False

    # 1. 探测可用的 GitHub 加速器
    remote = None
    for proxy in GITHUB_PROXIES:
        rc, _, _ = run(["git", "-C", PROJECT_DIR, "config", "url.%s.insteadOf" % proxy,
                        "https://github.com/"])
        rc, out, _ = run(["git", "-C", PROJECT_DIR, "ls-remote", "origin", "HEAD"])
        if rc == 0 and out.strip():
            remote = proxy
            info("使用加速器: %s" % proxy)
            break
        run(["git", "-C", PROJECT_DIR, "config", "--unset-all",
             "url.%s.insteadOf" % proxy])
    if not remote:
        # 尝试直连
        run(["git", "-C", PROJECT_DIR, "config", "url.https://github.com/.insteadOf",
             "https://github.com/"])
        rc, out, _ = run(["git", "-C", PROJECT_DIR, "ls-remote", "origin", "HEAD"])
        if not (rc == 0 and out.strip()):
            error("无法连接 GitHub(加速器与直连均失败),请手动处理")
            return False
        info("使用直连接口")

    # 2. 确定目标分支(deploy 优先,含 CI 预构建产物)
    branch = "deploy"
    rc, out, _ = run(["git", "-C", PROJECT_DIR, "ls-remote", "--heads",
                      "origin", "refs/heads/deploy"])
    if not (rc == 0 and out.strip()):
        rc, out, _ = run(["git", "-C", PROJECT_DIR, "branch", "--show-current"])
        branch = out.strip() or "main"

    info("从远程拉取(分支: %s)以修复缺失/损坏文件..." % branch)
    rc, out, err = run(["git", "-C", PROJECT_DIR, "fetch", "origin", branch],
                       timeout=600)
    if rc != 0:
        error("git fetch 失败: %s" % err.strip())
        return False

    # reset --hard 会恢复所有被跟踪文件(缺失/损坏的),不触碰未跟踪文件(如 .env/uploads/backup)
    rc, out, err = run(["git", "-C", PROJECT_DIR, "reset", "--hard",
                        "origin/%s" % branch], timeout=300)
    if rc != 0:
        error("git reset 失败: %s" % err.strip())
        return False
    info("已从远程恢复项目文件")

    # 3. 恢复脚本可执行权限 + 运行时目录
    for pattern in (os.path.join(PROJECT_DIR, "*.sh"),
                    os.path.join(SCRIPT_DIR, "*.sh")):
        for f in glob.glob(pattern):
            os.chmod(f, os.stat(f).st_mode | 0o111)
    for d in ["backup", "uploads", "logs"]:
        os.makedirs(os.path.join(PROJECT_DIR, d), exist_ok=True)

    # 4. 若 deploy 产物仍缺失,尝试重新构建
    need_build = False
    for rel in CRITICAL_FILES:
        path = os.path.join(PROJECT_DIR, rel)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            if rel.startswith("deploy/"):
                need_build = True
    if need_build:
        info("deploy 产物缺失,尝试重新构建/启动...")
        build_script = os.path.join(SCRIPT_DIR, "build.sh")
        if os.path.exists(build_script):
            run(["bash", build_script, "all"], cwd=PROJECT_DIR, timeout=1800)
        run(["docker", "compose", "up", "-d"], cwd=PROJECT_DIR)

    info("项目文件修复完成")
    return True


# ==============================================================
# 主流程
# ==============================================================
def do_check():
    print("")
    print(CYAN + "==================================" + NC)
    print(CYAN + "  企业智慧食堂 - 自检" + NC)
    print(CYAN + "==================================" + NC)

    problems = []

    # Docker/Compose
    title("环境")
    if not docker_available():
        error("Docker 不可用")
        problems.append(("Docker 不可用", "fatal"))
    else:
        info("Docker 可用")
    if not compose_available():
        error("docker compose 不可用")
        problems.append(("docker compose 不可用", "fatal"))

    if docker_available():
        # 服务状态
        title("服务状态")
        svc_problems = check_services()
        if svc_problems:
            for p in svc_problems:
                warn(p)
                problems.append((p, "medium"))
        else:
            info("各服务容器状态正常")

        # MySQL
        title("MySQL 数据完整性")
        mysql_desc, sev = check_mysql()
        if sev == "ok":
            info("MySQL 正常")
        else:
            warn(mysql_desc)
            problems.append((mysql_desc, sev))

    # 项目文件
    title("项目文件完整性")
    missing, desc = check_files()
    if missing:
        for m in missing:
            warn(m)
        problems.append((desc, "critical"))
    else:
        info(desc)

    # .env
    title("配置")
    env_problem = check_env()
    if env_problem:
        warn(env_problem)
        problems.append((env_problem, "critical"))
    else:
        info(".env 配置完整")

    # 汇总
    print("")
    print(CYAN + "---------- 自检结果 ----------" + NC)
    if not problems:
        info("未发现问题")
        return 0
    print(YELLOW + "发现 %d 个问题:%s" % (len(problems), NC))
    for desc, sev in problems:
        tag = {"critical": RED + "[严重]", "medium": YELLOW + "[一般]",
               "low": YELLOW + "[提示]", "fatal": RED + "[致命]"}.get(sev, YELLOW + "[?]")
        print("  %s%s   %s" % (tag, NC, desc))
    return 1 if any(sev in ("critical", "fatal") for _, sev in problems) else 0


def do_fix(scope, auto_yes):
    confirm.auto_yes = auto_yes
    problems = []

    if scope in ("all", "mysql"):
        mysql_desc, sev = check_mysql()
        if sev != "ok":
            problems.append(("mysql", mysql_desc, sev))
    if scope in ("all", "files"):
        missing, desc = check_files()
        if missing:
            problems.append(("files", desc, "critical"))

    if scope == "all" and not problems:
        info("自检未发现问题,无需修复")
        return 0

    if scope == "all":
        print("")
        print(YELLOW + "将自动修复以下问题:%s" % NC)
        for _, desc, sev in problems:
            print("  - %s" % desc)
        if not confirm("确认执行自动修复?"):
            info("已取消")
            return 0

    # 统一按问题清单执行修复(避免 "all" 之外的单独模式下重复修复)
    ok = True
    if any(p[0] == "mysql" for p in problems):
        ok = repair_mysql() and ok
    if any(p[0] == "files" for p in problems):
        ok = repair_files() and ok

    # 单独修复模式兜底:即使自检未列入问题(fix-mysql/fix-files 强制执行)
    if scope == "mysql" and not any(p[0] == "mysql" for p in problems):
        ok = repair_mysql() and ok
    elif scope == "files" and not any(p[0] == "files" for p in problems):
        ok = repair_files() and ok

    print("")
    if ok:
        info("修复流程完成")
    else:
        error("修复流程未完全成功,请查看上方日志")
    return 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(
        description="企业智慧食堂 - 数据库/项目自检自愈")
    parser.add_argument("action", nargs="?", default="check",
                        choices=["check", "fix", "fix-mysql", "fix-files"],
                        help="check=仅自检; fix=自检+修复; fix-mysql=仅修复MySQL; fix-files=仅修复项目文件")
    parser.add_argument("--yes", action="store_true", help="跳过交互确认")
    args = parser.parse_args()

    if not os.path.isdir(PROJECT_DIR):
        print("项目目录不存在: %s" % PROJECT_DIR)
        return 1

    if args.action == "check":
        return do_check()
    elif args.action == "fix":
        return do_fix("all", args.yes)
    elif args.action == "fix-mysql":
        return do_fix("mysql", args.yes)
    elif args.action == "fix-files":
        return do_fix("files", args.yes)
    return do_check()


if __name__ == "__main__":
    sys.exit(main())