"""
打包 deploy 分支为 zip(供手动上传服务器部署)
排除源码/测试文件,仅含运行时必需文件
"""
import os
import zipfile
import sys

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))


def get_system_version():
    """从 VERSIONS.json 读取系统整体版本,用于生成 zip 文件名"""
    try:
        import json
        with open(os.path.join(PROJECT_DIR, "VERSIONS.json"), encoding="utf-8") as f:
            return json.load(f)["system"]["version"]
    except Exception:
        return "x"


OUTPUT_ZIP = os.path.join(os.path.dirname(PROJECT_DIR), f"canteen-deploy-v{get_system_version()}.zip")

# 需要打包的文件/目录(相对于项目根目录)
# 格式: (相对路径, 在zip中的路径)
INCLUDES = [
    # 根目录文件
    ("docker-compose.yml", "docker-compose.yml"),
    (".env.example", ".env.example"),
    ("canteen.sh", "canteen.sh"),
    ("deploy.sh", "deploy.sh"),
    ("VERSIONS.json", "VERSIONS.json"),
    # 后端运行时镜像
    ("backend/Dockerfile.runtime", "backend/Dockerfile.runtime"),
    # 运行时脚本
    ("scripts/upgrade.sh", "scripts/upgrade.sh"),
    ("scripts/snapshot.sh", "scripts/snapshot.sh"),
    ("scripts/backup.sh", "scripts/backup.sh"),
    ("scripts/restore.sh", "scripts/restore.sh"),
    ("scripts/clean-redeploy.sh", "scripts/clean-redeploy.sh"),
    ("scripts/init-db-user.sh", "scripts/init-db-user.sh"),
    ("scripts/cron_backup.sh", "scripts/cron_backup.sh"),
    # 构建产物 - 后端
    ("deploy/backend/app.jar", "deploy/backend/app.jar"),
    # 构建产物 - admin-web
    ("deploy/admin-web/nginx.conf", "deploy/admin-web/nginx.conf"),
    # 构建产物 - h5
    ("deploy/h5/nginx.conf", "deploy/h5/nginx.conf"),
]

# 需要递归打包的目录
INCLUDE_DIRS = [
    ("deploy/admin-web/html", "deploy/admin-web/html"),
    ("deploy/h5/html", "deploy/h5/html"),
]

# 排除的文件名模式
EXCLUDE_PATTERNS = [
    ".DS_Store",
    "Thumbs.db",
    "__pycache__",
    ".gitignore",
]


def should_exclude(name):
    return any(p in name for p in EXCLUDE_PATTERNS)


def add_file(zf, src_path, arc_path):
    """添加单个文件到 zip"""
    if not os.path.isfile(src_path):
        print(f"  [警告] 文件不存在,跳过: {src_path}")
        return False
    zf.write(src_path, arc_path)
    size = os.path.getsize(src_path)
    if size > 1024 * 1024:
        print(f"  [添加] {arc_path} ({size / 1024 / 1024:.1f} MB)")
    else:
        print(f"  [添加] {arc_path} ({size / 1024:.1f} KB)")
    return True


def add_dir(zf, src_dir, arc_dir):
    """递归添加目录到 zip"""
    if not os.path.isdir(src_dir):
        print(f"  [警告] 目录不存在,跳过: {src_dir}")
        return 0
    count = 0
    for root, dirs, files in os.walk(src_dir):
        # 过滤排除项
        dirs[:] = [d for d in dirs if not should_exclude(d)]
        for f in files:
            if should_exclude(f):
                continue
            full_path = os.path.join(root, f)
            rel_path = os.path.relpath(full_path, src_dir)
            arc_path = os.path.join(arc_dir, rel_path).replace("\\", "/")
            zf.write(full_path, arc_path)
            count += 1
    return count


def main():
    print(f"打包 deploy 分支为 zip...")
    print(f"项目目录: {PROJECT_DIR}")
    print(f"输出文件: {OUTPUT_ZIP}")
    print()

    # 检查关键文件
    missing = []
    for src, _ in INCLUDES:
        full = os.path.join(PROJECT_DIR, src)
        if not os.path.exists(full):
            missing.append(src)
    for src, _ in INCLUDE_DIRS:
        full = os.path.join(PROJECT_DIR, src)
        if not os.path.exists(full):
            missing.append(src)

    if missing:
        print("[错误] 以下文件缺失,请先运行 publish.sh 构建:")
        for m in missing:
            print(f"  - {m}")
        sys.exit(1)

    # 打包
    with zipfile.ZipFile(OUTPUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        # 添加单个文件
        print("--- 添加文件 ---")
        for src, arc in INCLUDES:
            add_file(zf, os.path.join(PROJECT_DIR, src), arc)

        # 添加目录
        print("\n--- 添加目录 ---")
        total_files = 0
        for src, arc in INCLUDE_DIRS:
            count = add_dir(zf, os.path.join(PROJECT_DIR, src), arc)
            total_files += count
            print(f"  目录 {src}: {count} 个文件")

    # 统计
    zip_size = os.path.getsize(OUTPUT_ZIP)
    print(f"\n=== 打包完成 ===")
    print(f"文件: {OUTPUT_ZIP}")
    print(f"大小: {zip_size / 1024 / 1024:.1f} MB")
    print(f"总文件数: {len(INCLUDES) + total_files}")


if __name__ == "__main__":
    main()
