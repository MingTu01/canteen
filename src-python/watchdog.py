"""
X86 终端守护进程
监控主进程(canteen-terminal.exe),崩溃时自动重启。

用法:
  python watchdog.py            # 开发模式直接运行
  watchdog.exe                  # PyInstaller 打包后(随主程序安装,开机自启)

日志:
  %LOCALAPPDATA%\\CanteenTerminal\\watchdog.log

工作机制:
  - 每 15 秒检查一次主进程是否存活(通过 tasklist)
  - 首次检查(开机启动)发现主进程未运行时立即拉起,不等待冷却
  - 主进程崩溃后冷却 10 秒再重启,避免快速崩溃循环
  - 每小时最多重启 10 次,超过则停止(防止无限崩溃循环)
  - 由 installer.iss 注册为开机自启({commonstartup} 快捷方式)
"""
import os
import sys
import time
import subprocess
import logging
from pathlib import Path

# ===== 日志配置:同时输出到文件和控制台(若有) =====
# 日志文件放在 %LOCALAPPDATA%\CanteenTerminal\watchdog.log,与终端主日志同目录
LOG_DIR = Path(os.environ.get('LOCALAPPDATA', '')) / 'CanteenTerminal'
try:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
except Exception:
    pass
LOG_FILE = LOG_DIR / 'watchdog.log'

logging.basicConfig(
    filename=str(LOG_FILE),
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
)
# 控制台输出(开发调试用)
# PyInstaller windowed 模式(console=False)下 sys.stdout/stderr 为 None,需跳过,
# 否则 StreamHandler 写入 None 会抛异常
if sys.stdout is not None or sys.stderr is not None:
    console = logging.StreamHandler()
    console.setLevel(logging.INFO)
    console.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))
    logging.getLogger().addHandler(console)

# 主进程 EXE 路径(与 watchdog.exe 同目录)
# 打包后用 sys.executable 定位 EXE 目录;开发模式用脚本所在目录
if getattr(sys, 'frozen', False):
    SCRIPT_DIR = Path(sys.executable).parent.resolve()
else:
    SCRIPT_DIR = Path(__file__).parent.resolve()
MAIN_EXE = SCRIPT_DIR / 'canteen-terminal.exe'

RESTART_COOLDOWN = 10        # 崩溃后冷却 10 秒再重启
MAX_RESTART_PER_HOUR = 10    # 每小时最多重启 10 次(防止无限崩溃循环)
CHECK_INTERVAL = 15          # 每 15 秒检查一次

# CREATE_NO_WINDOW:watchdog.exe 以 console=False(无控制台)模式打包,
# 调用 tasklist(控制台程序)时若不加此标志,Windows 会为子进程创建控制台窗口,
# 每 15 秒在终端全屏画面上闪烁一次。此标志确保子进程静默运行。
CREATE_NO_WINDOW = 0x08000000


def get_exit_flag_path():
    """获取正常退出标记文件路径(%APPDATA%\\CanteenTerminal\\exit.flag)。

    目录与 config.py 的 get_appdata_dir() 保持一致;watchdog 按打包规格
    仅依赖标准库(见 canteen-terminal.spec),不 import config,故本地实现
    同款目录逻辑。bridge.py 处理 /__api__/quit 时写入该标记。
    """
    appdata = os.environ.get('APPDATA')
    if not appdata:
        appdata = os.path.expanduser('~\\AppData\\Roaming')
    return Path(appdata) / 'CanteenTerminal' / 'exit.flag'


def is_process_running(exe_name):
    """检查指定进程是否在运行(通过 Windows tasklist 命令)。"""
    try:
        result = subprocess.run(
            ['tasklist', '/FI', f'IMAGENAME eq {exe_name}'],
            capture_output=True, text=True, timeout=5,
            creationflags=CREATE_NO_WINDOW,
        )
        return exe_name.lower() in result.stdout.lower()
    except Exception:
        return False


def main():
    exe_name = MAIN_EXE.name
    logging.info(f'守护进程启动,监控目标: {MAIN_EXE}')
    restart_count = 0
    restart_times = []   # 最近 1 小时内的重启时间戳
    first_check = True   # 首次检查标志(开机启动时立即拉起,不等待冷却)

    while True:
        # 检查正常退出标记:主进程通过 /__api__/quit 主动退出时写入 exit.flag,
        # 属用户主动退出(维护/配置),不再拉起并删除标记后自行退出
        exit_flag = get_exit_flag_path()
        if exit_flag.exists():
            logging.info('检测到正常退出标记,watchdog 退出')
            try:
                exit_flag.unlink()
            except Exception:
                pass
            break

        if not is_process_running(exe_name):
            now = time.time()
            # 清理 1 小时前的重启记录
            restart_times = [t for t in restart_times if now - t < 3600]
            if len(restart_times) >= MAX_RESTART_PER_HOUR:
                logging.error(
                    f'1小时内已重启 {MAX_RESTART_PER_HOUR} 次,可能存在严重问题,停止重启'
                )
                break

            if first_check:
                # 首次检查(开机启动):立即拉起主进程,不等待冷却
                logging.info(f'主进程 {exe_name} 未运行,立即启动(首次启动)...')
            else:
                logging.warning(f'主进程 {exe_name} 未运行,{RESTART_COOLDOWN}秒后重启...')
                time.sleep(RESTART_COOLDOWN)

            try:
                subprocess.Popen([str(MAIN_EXE)], cwd=str(SCRIPT_DIR),
                                 creationflags=CREATE_NO_WINDOW)
                restart_times.append(time.time())
                restart_count += 1
                logging.info(f'主进程已启动/重启(第 {restart_count} 次)')
                time.sleep(10)  # 等待启动完成,避免下一次检查误判为未运行
            except Exception as e:
                logging.error(f'启动失败: {e}')
                time.sleep(30)
        else:
            time.sleep(CHECK_INTERVAL)

        first_check = False


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        logging.info('守护进程被手动中断')
    except Exception as e:
        logging.exception(f'守护进程异常退出: {e}')
