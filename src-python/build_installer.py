"""
企业智慧食堂终端 - Windows 安装包打包脚本

一键完成:
  1. 构建 Vue 前端 → terminal/dist
  2. 运行 PyInstaller → dist/canteen-terminal/(绿色目录版)
  3. 运行 Inno Setup → output/CanteenTerminal-Setup-<版本>.exe(正式安装包)

前置条件:
  - Node.js 18+ (构建前端)
  - Python 3.10+ 32 位 (PyInstaller 打包,因 OUR_IDR.dll 是 32 位)
    pip install PyQt5 PyQtWebEngine pyinstaller
  - Inno Setup 6+ (打包安装包)
    https://jrsoftware.org/isdl.php
  - CH375 驱动文件放入 src-python/drivers/

用法:
  cd src-python
  python build_installer.py            # 完整打包
  python build_installer.py --skip-web # 跳过前端构建(已构建过)
  python build_installer.py --skip-py  # 跳过 PyInstaller(已构建过)
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

# 颜色输出(Windows cmd 也支持 ANSI)
class Color:
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    CYAN = '\033[96m'
    NC = '\033[0m'

def info(msg):
    print(f'{Color.CYAN}[打包]{Color.NC} {msg}')

def ok(msg):
    print(f'{Color.GREEN}[完成]{Color.NC} {msg}')

def warn(msg):
    print(f'{Color.YELLOW}[警告]{Color.NC} {msg}')

def err(msg):
    print(f'{Color.RED}[错误]{Color.NC} {msg}')

# 路径常量
SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_DIR = SCRIPT_DIR.parent
TERMINAL_DIR = PROJECT_DIR / 'terminal'
DRIVERS_DIR = SCRIPT_DIR / 'drivers'
DIST_DIR = SCRIPT_DIR / 'dist'              # PyInstaller 产物
OUTPUT_DIR = SCRIPT_DIR / 'output'          # 安装包产物

# Inno Setup 编译器路径(按常见安装位置查找)
ISCC_CANDIDATES = [
    r'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    r'C:\Program Files\Inno Setup 6\ISCC.exe',
    r'D:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    r'D:\Program Files\Inno Setup 6\ISCC.exe',
]


def run(cmd, cwd=None, env=None, check=True):
    """运行命令,实时输出。失败抛出 CalledProcessError。"""
    info(f'执行: {" ".join(cmd) if isinstance(cmd, list) else cmd}')
    result = subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        shell=isinstance(cmd, str),
        encoding='utf-8',
        errors='replace',
    )
    if check and result.returncode != 0:
        raise RuntimeError(f'命令失败(退出码 {result.returncode}): {cmd}')
    return result


def build_frontend():
    """构建 Vue 前端 → terminal/dist"""
    info('步骤 1/3: 构建 Vue 前端...')
    if not (TERMINAL_DIR / 'package.json').exists():
        raise RuntimeError(f'未找到前端项目: {TERMINAL_DIR}')

    # 检查 node 是否可用
    try:
        run(['node', '--version'], check=True)
    except (FileNotFoundError, RuntimeError):
        raise RuntimeError('未找到 Node.js,请安装 Node.js 18+')

    # 安装依赖(若 node_modules 不存在)
    if not (TERMINAL_DIR / 'node_modules').exists():
        info('安装前端依赖(国内 npmmirror 源)...')
        run(['npm', 'install', '--registry=https://registry.npmmirror.com'], cwd=str(TERMINAL_DIR))

    # 构建
    run(['npm', 'run', 'build'], cwd=str(TERMINAL_DIR))

    dist_dir = TERMINAL_DIR / 'dist'
    if not dist_dir.exists():
        raise RuntimeError(f'前端构建产物未生成: {dist_dir}')

    ok(f'前端构建完成: {dist_dir}')


def build_pyinstaller():
    """运行 PyInstaller → dist/canteen-terminal/"""
    info('步骤 2/3: 运行 PyInstaller...')

    spec_file = SCRIPT_DIR / 'canteen-terminal.spec'
    if not spec_file.exists():
        raise RuntimeError(f'未找到 PyInstaller spec: {spec_file}')

    # 检查 PyInstaller 是否安装
    try:
        run([sys.executable, '-m', 'PyInstaller', '--version'], check=True)
    except (FileNotFoundError, RuntimeError):
        raise RuntimeError('未安装 PyInstaller,执行: pip install pyinstaller')

    # 清理旧产物
    build_dir = SCRIPT_DIR / 'build'
    if build_dir.exists():
        shutil.rmtree(build_dir, ignore_errors=True)

    # 运行 PyInstaller
    run([
        sys.executable, '-m', 'PyInstaller',
        str(spec_file),
        '--clean',
        '--noconfirm',
        '--distpath', str(DIST_DIR),
        '--workpath', str(SCRIPT_DIR / 'build'),
    ], cwd=str(SCRIPT_DIR))

    app_dir = DIST_DIR / 'canteen-terminal'
    if not app_dir.exists():
        raise RuntimeError(f'PyInstaller 产物未生成: {app_dir}')

    # 校验关键文件
    exe_path = app_dir / 'canteen-terminal.exe'
    if not exe_path.exists():
        raise RuntimeError(f'EXE 未生成: {exe_path}')

    ok(f'PyInstaller 打包完成: {exe_path}')


def check_drivers():
    """检查驱动文件是否齐全。返回 True/False。"""
    inf_file = DRIVERS_DIR / 'CH375WDM.INF'
    if not inf_file.exists():
        warn(f'未找到 CH375WDM.INF,安装包将不含驱动(需用户手动安装)')
        warn(f'请将驱动文件放入: {DRIVERS_DIR}')
        return False

    # 列出驱动文件
    driver_files = list(DRIVERS_DIR.glob('*'))
    driver_files = [f for f in driver_files if f.name != 'README.txt']
    info(f'驱动文件清单: {", ".join(f.name for f in driver_files)}')
    return True


def build_inno_setup():
    """运行 Inno Setup → output/CanteenTerminal-Setup-<版本>.exe"""
    info('步骤 3/3: 运行 Inno Setup 打包安装包...')

    iss_file = SCRIPT_DIR / 'installer.iss'
    if not iss_file.exists():
        raise RuntimeError(f'未找到 Inno Setup 脚本: {iss_file}')

    # 查找 ISCC.exe
    iscc = os.environ.get('ISCC_PATH')
    if not iscc or not Path(iscc).exists():
        for candidate in ISCC_CANDIDATES:
            if Path(candidate).exists():
                iscc = candidate
                break
    if not iscc or not Path(iscc).exists():
        raise RuntimeError(
            '未找到 Inno Setup 编译器 ISCC.exe\n'
            '请安装 Inno Setup 6+: https://jrsoftware.org/isdl.php\n'
            '或设置环境变量 ISCC_PATH 指向 ISCC.exe 的完整路径'
        )

    info(f'使用 ISCC: {iscc}')

    # 确保 output 目录存在
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # 运行 ISCC
    run([iscc, str(iss_file)], cwd=str(SCRIPT_DIR))

    # 查找生成的安装包
    setup_files = list(OUTPUT_DIR.glob('CanteenTerminal-Setup-*.exe'))
    if not setup_files:
        raise RuntimeError(f'安装包未生成,请检查 ISCC 输出')

    setup_file = max(setup_files, key=lambda p: p.stat().st_mtime)
    size_mb = setup_file.stat().st_size / 1024 / 1024
    ok(f'安装包生成完成: {setup_file} ({size_mb:.1f} MB)')


def main():
    parser = argparse.ArgumentParser(description='企业智慧食堂终端 - 安装包打包脚本')
    parser.add_argument('--skip-web', action='store_true', help='跳过前端构建(已构建过)')
    parser.add_argument('--skip-py', action='store_true', help='跳过 PyInstaller(已构建过)')
    parser.add_argument('--only-iss', action='store_true', help='仅运行 Inno Setup')
    args = parser.parse_args()

    print(f'{Color.CYAN}{"=" * 60}{Color.NC}')
    print(f'{Color.CYAN}企业智慧食堂终端 - 安装包打包脚本{Color.NC}')
    print(f'{Color.CYAN}{"=" * 60}{Color.NC}')

    try:
        # 0. 检查驱动文件
        check_drivers()

        if not args.only_iss and not args.skip_web:
            build_frontend()
        else:
            info('跳过前端构建')

        if not args.only_iss and not args.skip_py:
            build_pyinstaller()
        else:
            info('跳过 PyInstaller')

        build_inno_setup()

        print(f'\n{Color.GREEN}{"=" * 60}{Color.NC}')
        print(f'{Color.GREEN}全部打包完成!{Color.NC}')
        print(f'{Color.GREEN}安装包位于: {OUTPUT_DIR}{Color.NC}')
        print(f'{Color.GREEN}{"=" * 60}{Color.NC}')

    except Exception as e:
        print(f'\n{Color.RED}{"=" * 60}{Color.NC}')
        err(f'打包失败: {e}')
        print(f'{Color.RED}{"=" * 60}{Color.NC}')
        sys.exit(1)


if __name__ == '__main__':
    main()
