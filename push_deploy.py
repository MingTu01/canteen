"""
推送到 deploy 分支(orphan 分支,独立历史)
等效于 publish.sh 的 git 操作部分,适用于 Windows 环境
"""
import os
import shutil
import subprocess
import sys
import json

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKTREE_DIR = os.path.join(PROJECT_DIR, ".deploy-tmp")
DEPLOY_BRANCH = "deploy"

def run(cmd, cwd=None, check=True):
    """运行命令,打印输出"""
    print(f"  $ {cmd}")
    result = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True, text=True)
    if result.stdout.strip():
        print(f"    {result.stdout.strip()}")
    if result.stderr.strip():
        print(f"    [stderr] {result.stderr.strip()}")
    if check and result.returncode != 0:
        print(f"  [错误] 命令失败 (退出码 {result.returncode})")
        sys.exit(1)
    return result

def main():
    print("=" * 60)
    print("推送 deploy 分支")
    print("=" * 60)

    # 1. 获取远程仓库地址和当前 SHA
    result = run('git remote get-url origin', cwd=PROJECT_DIR)
    remote_url = result.stdout.strip()
    if not remote_url:
        print("[错误] 无法获取远程仓库地址")
        sys.exit(1)

    result = run('git rev-parse --short HEAD', cwd=PROJECT_DIR)
    main_sha = result.stdout.strip()
    print(f"  远程仓库: {remote_url}")
    print(f"  main SHA: {main_sha}")

    # 2. 读取版本号
    versions_path = os.path.join(PROJECT_DIR, "VERSIONS.json")
    with open(versions_path, "r", encoding="utf-8") as f:
        versions = json.load(f)
    version = versions["system"]["version"]
    be_ver = versions["backend"]["version"]
    hw_ver = versions["admin-web"]["version"]
    h5_ver = versions["h5"]["version"]
    print(f"  系统版本: v{version}")
    print(f"  后端: v{be_ver}  管理后台: v{hw_ver}  H5: v{h5_ver}")

    # 3. 准备临时工作区
    print("\n--- 准备 deploy 分支 ---")
    if os.path.exists(WORKTREE_DIR):
        shutil.rmtree(WORKTREE_DIR, ignore_errors=True)
    os.makedirs(WORKTREE_DIR)

    # 4. 初始化 orphan 分支
    run('git init --quiet', cwd=WORKTREE_DIR)
    run(f'git remote add origin "{remote_url}"', cwd=WORKTREE_DIR)
    run(f'git checkout --orphan {DEPLOY_BRANCH} 2>nul || git checkout -B {DEPLOY_BRANCH}', cwd=WORKTREE_DIR)
    run('git rm -rf . 2>nul || echo ok', cwd=WORKTREE_DIR)

    # 5. 复制文件
    print("\n--- 复制产物到 deploy 分支 ---")

    def copy_file(src_rel, dst_rel=None):
        src = os.path.join(PROJECT_DIR, src_rel)
        dst = os.path.join(WORKTREE_DIR, dst_rel or src_rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        print(f"  [复制] {src_rel}")

    def copy_dir(src_rel, dst_rel=None):
        src = os.path.join(PROJECT_DIR, src_rel)
        dst = os.path.join(WORKTREE_DIR, dst_rel or src_rel)
        if os.path.exists(dst):
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
        print(f"  [复制目录] {src_rel}")

    # docker-compose.yml
    copy_file("docker-compose.yml")
    # .env.example
    copy_file(".env.example")
    # canteen.sh
    copy_file("canteen.sh")
    # deploy.sh
    copy_file("deploy.sh")
    # VERSIONS.json
    copy_file("VERSIONS.json")
    # backend/Dockerfile.runtime
    copy_file("backend/Dockerfile.runtime", "backend/Dockerfile.runtime")

    # 运行时脚本
    scripts = ["upgrade.sh", "snapshot.sh", "backup.sh", "restore.sh",
               "clean-redeploy.sh", "init-db-user.sh", "cron_backup.sh"]
    for s in scripts:
        src = os.path.join(PROJECT_DIR, "scripts", s)
        if os.path.isfile(src):
            dst = os.path.join(WORKTREE_DIR, "scripts", s)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
            print(f"  [复制] scripts/{s}")

    # 构建产物
    copy_dir("deploy/backend", "deploy/backend")
    copy_dir("deploy/admin-web", "deploy/admin-web")
    copy_dir("deploy/h5", "deploy/h5")

    # 6. 提交
    print("\n--- 提交 ---")
    run('git add -A', cwd=WORKTREE_DIR)
    commit_msg = f"deploy: v{version} (all)\n\n后端: v{be_ver}\n管理后台: v{hw_ver}\nH5: v{h5_ver}\n\n发布时间: 2026-08-03\n源码: main@{main_sha}"
    # 写入临时文件
    msg_file = os.path.join(WORKTREE_DIR, ".commit-msg.txt")
    with open(msg_file, "w", encoding="utf-8") as f:
        f.write(commit_msg)
    run(f'git commit -F .commit-msg.txt --allow-empty', cwd=WORKTREE_DIR)
    os.remove(msg_file)

    # 7. 推送
    print("\n--- 推送 deploy 分支 ---")
    # 增大 HTTP 缓冲区,防止大文件(jar 45MB)推送超时
    run('git config http.postBuffer 1048576000', cwd=WORKTREE_DIR, check=False)
    run('git config http.lowSpeedLimit 0', cwd=WORKTREE_DIR, check=False)
    run('git config http.lowSpeedTime 999999', cwd=WORKTREE_DIR, check=False)
    run('git config http.version HTTP/2', cwd=WORKTREE_DIR, check=False)

    result = run(f'git push origin {DEPLOY_BRANCH} --force', cwd=WORKTREE_DIR, check=False)
    if result.returncode != 0:
        print("  force 推送失败,重试(第2次)...")
        import time
        time.sleep(5)
        result = run(f'git push origin {DEPLOY_BRANCH} --force', cwd=WORKTREE_DIR, check=False)
    if result.returncode != 0:
        print("  force 推送失败,重试(第3次)...")
        time.sleep(10)
        result = run(f'git push origin {DEPLOY_BRANCH} --force', cwd=WORKTREE_DIR, check=False)
    if result.returncode != 0:
        print("\n[错误] deploy 分支推送失败(3次重试后仍失败)")
        print("  可能原因:网络不稳定或 jar 文件太大(45MB)")
        print("  解决方案:")
        print("    1. 使用 zip 包手动部署: canteen-deploy-v0.0.6.zip")
        print("    2. 稍后重试: python push_deploy.py")
        print("    3. 在网络稳定时重试")
        # 不退出,继续清理

    # 8. 清理
    print("\n--- 清理临时工作区 ---")
    shutil.rmtree(WORKTREE_DIR, ignore_errors=True)

    print("\n" + "=" * 60)
    print(f"  发布完成!")
    print(f"  系统版本: v{version}")
    print(f"  后端: v{be_ver}  管理后台: v{hw_ver}  H5: v{h5_ver}")
    print(f"  源码: main@{main_sha}")
    print("=" * 60)

if __name__ == "__main__":
    main()
