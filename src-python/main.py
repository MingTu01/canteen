"""
企业智慧食堂终端 - Python Shell 主入口。

使用 PyQt5 + QWebEngineView 加载 Vue 前端,ctypes 调用 ICUSB.DLL 读卡器。
不依赖系统 WebView2,兼容 Win7/Win10/Win11(32/64 位)。

架构:
    ┌──────────────────────────────────────────┐
    │  QWebEngineView(加载 Vue 前端)           │
    │  ↑ runJavaScript(卡号推送)               │
    │  ↓ fetch /__api__/xxx(前端调用 Python)   │
    ├──────────────────────────────────────────┤
    │  本地 HTTP 服务器(静态文件 + API 端点)    │
    ├──────────────────────────────────────────┤
    │  CardReader(ctypes 调 ICUSB.DLL,后台线程)│
    └──────────────────────────────────────────┘
"""
import os
import sys
import glob
import traceback
from datetime import datetime

# ===== 日志重定向:同时输出到 CMD 窗口和日志文件 =====
# 日志文件放在用户目录(%LOCALAPPDATA%\CanteenTerminal\terminal.log),
# 方便排查安装版问题(用户看不到 CMD 窗口内容时可直接查日志文件)。
class TeeLogger:
    """同时写入 stdout 和日志文件。"""
    def __init__(self, *streams):
        self.streams = streams
    def write(self, data):
        for s in self.streams:
            try:
                s.write(data)
                s.flush()
            except Exception:
                pass
    def flush(self):
        for s in self.streams:
            try:
                s.flush()
            except Exception:
                pass

def _setup_logging():
    """配置日志:同时输出到控制台和文件。"""
    if sys.platform != 'win32':
        return
    # 日志文件路径:%LOCALAPPDATA%\CanteenTerminal\terminal.log
    local_appdata = os.environ.get('LOCALAPPDATA') or os.path.expanduser('~\\AppData\\Local')
    log_dir = os.path.join(local_appdata, 'CanteenTerminal')
    try:
        os.makedirs(log_dir, exist_ok=True)
    except Exception:
        return
    log_path = os.path.join(log_dir, 'terminal.log')
    try:
        # 每次启动覆盖旧日志(避免文件无限增长)
        log_file = open(log_path, 'w', encoding='utf-8', buffering=1)
        # 写入启动分隔线
        log_file.write(f'===== 终端启动 {datetime.now().strftime("%Y-%m-%d %H:%M:%S")} =====\n')
        log_file.flush()
        # 同时输出到原 stdout 和日志文件
        sys.stdout = TeeLogger(sys.stdout, log_file)
        sys.stderr = TeeLogger(sys.stderr, log_file)
        print(f'[Log] 日志文件: {log_path}', flush=True)
    except Exception as e:
        # 日志初始化失败不影响程序运行
        print(f'[Log] 日志文件初始化失败: {e}')

_setup_logging()

# 全局异常钩子:未捕获的异常写入日志
def _excepthook(exc_type, exc_value, exc_tb):
    print(f'[FATAL] 未捕获异常: {exc_type.__name__}: {exc_value}', flush=True)
    traceback.print_exception(exc_type, exc_value, exc_tb)
sys.excepthook = _excepthook

# 确保能导入同目录模块
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# ===== 修复 PyInstaller onedir 模式下 QtWebEngine 资源找不到的问题 =====
# 问题:QtWebEngine 内部使用 ANSI API 读取路径,无法处理含中文的路径
# (如 D:\文档\...),导致报 "resources not found" 和 "Couldn't mmap icu data file"。
# 解决:用 Windows 短路径名(GetShortPathName)转换路径,消除中文字符。
# 必须在导入 PyQt5.QtWebEngineWidgets 之前设置。
if getattr(sys, 'frozen', False) and sys.platform == 'win32':
    import ctypes
    from ctypes import wintypes

    def get_short_path(long_path):
        """将长路径转换为 Windows 短路径名(8.3 格式),消除中文/空格。"""
        buf = ctypes.create_unicode_buffer(260)
        kernel32 = ctypes.windll.kernel32
        kernel32.GetShortPathNameW.restype = wintypes.DWORD
        kernel32.GetShortPathNameW.argtypes = [wintypes.LPCWSTR, wintypes.LPWSTR, wintypes.DWORD]
        ret = kernel32.GetShortPathNameW(long_path, buf, 260)
        if ret > 0:
            return buf.value
        return long_path  # 转换失败则返回原路径

    _exe_dir = os.path.dirname(sys.executable)
    _exe_dir_short = get_short_path(_exe_dir)
    _qt_bin = os.path.join(_exe_dir_short, '_internal', 'PyQt5', 'Qt5', 'bin')
    _qt_root = os.path.join(_exe_dir_short, '_internal', 'PyQt5', 'Qt5')
    _qt_webengine_process = os.path.join(_qt_bin, 'QtWebEngineProcess.exe')
    if os.path.exists(_qt_webengine_process):
        os.environ['QTWEBENGINEPROCESS_PATH'] = _qt_webengine_process
    # Chromium 资源目录(含 icudtl.dat 和 .pak 文件)
    _qt_resources = os.path.join(_qt_root, 'resources')
    if os.path.exists(_qt_resources):
        os.environ['QTWEBENGINE_RESOURCES_PATH'] = _qt_resources
    # Chromium locales 目录
    _qt_locales = os.path.join(_qt_root, 'translations', 'qtwebengine_locales')
    if os.path.exists(_qt_locales):
        os.environ['QTWEBENGINE_LOCALES_PATH'] = _qt_locales
    # Chromium flags 说明:
    #   --no-sandbox: 完全禁用渲染器进程沙箱。
    #   必须使用 --no-sandbox!否则渲染器进程的文件系统沙箱会阻止 IndexedDB
    #   写入元数据(报 indexed_db_backing_store.cc SET_UP_METADATA 错误),
    #   导致菜品图片缓存、头像缓存全部失效。
    #
    #   之前在 onefile 模式下 --no-sandbox 会导致 network service 崩溃,
    #   但那是 onefile 解压临时目录权限不足导致的;onedir 模式下不存在此问题。
    #   若仍出现 network service 崩溃,可追加 --disable-features=NetworkService
    #   让网络服务在浏览器进程中运行。
    #
    #   --disable-gpu-sandbox: 禁用 GPU 沙箱(终端/虚拟机环境下 GPU 沙箱常因
    #   驱动问题导致渲染异常)。
    os.environ['QTWEBENGINE_CHROMIUM_FLAGS'] = '--no-sandbox --disable-gpu-sandbox --disable-software-rasterizer --enable-media-stream --use-fake-ui-for-media-stream'
    # 用短路径写 qt.conf(避免中文路径被 QtWebEngine 的 ANSI API 截断为 ??)
    # 关键:bin/qt.conf 的 Prefix 必须是 ".."(父目录),因为 QtWebEngineProcess.exe
    # 在 bin/ 下,而 resources/ 和 translations/ 在 bin/ 的父目录(Qt5/)下。
    # 之前写成 "Prefix = ." 导致 Qt 在 bin/resources/ 找 icudtl.dat,
    # 报 "Couldn't mmap icu data file" → network service 崩溃 → IndexedDB 无法工作。
    _bin_qt_conf = os.path.join(_qt_bin, 'qt.conf')
    try:
        with open(_bin_qt_conf, 'w', encoding='ascii') as f:
            f.write('[Paths]\n')
            f.write('Prefix = ..\n')
    except Exception:
        pass
    _exe_qt_conf = os.path.join(_exe_dir, 'qt.conf')  # 放 EXE 同目录,用原始路径(英文路径下无问题)
    try:
        with open(_exe_qt_conf, 'w', encoding='ascii') as f:
            f.write('[Paths]\n')
            f.write(f'Prefix = {_qt_root}\n')
            f.write(f'Binaries = {_qt_bin}\n')
            f.write(f'Libraries = {_qt_bin}\n')
            f.write(f'Plugins = {os.path.join(_qt_root, "plugins")}\n')
            f.write(f'Translations = {os.path.join(_qt_root, "translations")}\n')
            f.write(f'Resources = {os.path.join(_qt_root, "resources")}\n')
    except Exception:
        pass

# ===== 单实例限制(Windows 命名 Mutex)=====
# 防止多开 EXE 导致:
# 1. 本地 HTTP 端口(15118)被占用,第二个实例 fallback 到其他端口,
#    origin 变化导致 localStorage/IndexedDB 数据"丢失"
# 2. QtWebEngine 持久化目录(data/)的 SQLite/LevelDB 文件锁冲突,
#    导致 "database is locked" 错误,IndexedDB 写入失败
# 3. 读卡器 USB 设备被多个实例争抢
if sys.platform == 'win32':
    import ctypes
    from ctypes import wintypes

    # 命名 Mutex(全局唯一,跨会话)
    MUTEX_NAME = 'Global\\CanteenTerminal_SingleInstance_v1'
    kernel32 = ctypes.windll.kernel32
    # CreateMutex(lpMutexAttributes, bInitialOwner, lpName)
    # 返回句柄;若 Mutex 已存在,GetLastError 返回 ERROR_ALREADY_EXISTS (183)
    kernel32.CreateMutexW.restype = wintypes.HANDLE
    kernel32.CreateMutexW.argtypes = [wintypes.LPCVOID, wintypes.BOOL, wintypes.LPCWSTR]
    mutex_handle = kernel32.CreateMutexW(None, False, MUTEX_NAME)
    last_error = kernel32.GetLastError()
    if last_error == 183:  # ERROR_ALREADY_EXISTS
        # 已有实例运行,提示并退出
        ctypes.windll.user32.MessageBoxW(
            0,
            '企业智慧食堂终端已在运行,请勿重复打开。\n\n'
            '如需重启,请先关闭已有窗口(或通过任务管理器结束 canteen-terminal.exe 进程)。',
            '提示',
            0x40,  # MB_ICONINFORMATION
        )
        sys.exit(0)
    # 保存 mutex_handle 到全局,防止被 GC 回收(Mutex 生命周期 = 进程生命周期)
    _single_instance_mutex = mutex_handle

from PyQt5.QtCore import Qt, QUrl, QObject, pyqtSignal, QThread, QTimer
from PyQt5.QtWidgets import QApplication, QWidget, QVBoxLayout, QMessageBox, QProgressDialog
from PyQt5.QtWebEngineWidgets import QWebEngineView, QWebEnginePage, QWebEngineProfile, QWebEngineScript
from PyQt5.QtGui import QKeyEvent

from config import read_config, ensure_config_json, get_exe_dir, get_local_appdata_dir, read_full_config, write_config
from server import find_web_dist, start_server
from bridge import ShellBridge
from card_reader import CardReader
import updater


class FullscreenWebPage(QWebEnginePage):
    """自定义 WebEnginePage,允许 JavaScript 控制台日志打印到 Python。"""

    def javaScriptConsoleMessage(self, level, message, line, source):
        # 前端 console.log 会打印到这里,方便调试
        prefix = {0: 'JS Log', 1: 'JS Warn', 2: 'JS Error', 3: 'JS Info'}.get(level, 'JS')
        # 显示来源文件和行号,便于定位前端报错
        src_name = source.split('/')[-1] if source else '?'
        print(f'[{prefix}] {src_name}:{line} {message}')

    def createWindow(self, _type):
        # 阻止 target=_blank 弹出新窗口
        return None

    def featurePermissionRequested(self, url, feature):
        """自动授予摄像头/媒体权限。

        PyQtWebEngine 默认拒绝 getUserMedia 请求,导致前端
        navigator.mediaDevices.enumerateDevices() 拿不到摄像头设备。
        终端是本地应用,自动授予 MediaVideoCapture 权限即可。
        """
        if feature == QWebEnginePage.MediaVideoCapture:
            self.setFeaturePermission(url, QWebEnginePage.MediaVideoCapture, QWebEnginePage.PermissionGrantedByUser)
            print(f'[WebEngine] 已授予摄像头权限: {url.toString()}')
        else:
            super().featurePermissionRequested(url, feature)


class TerminalWindow(QWidget):
    """终端主窗口:全屏无边框 + QWebEngineView。"""

    def __init__(self, url, fullscreen=True):
        """
        Args:
            url: 前端加载地址
            fullscreen: True=全屏无边框,False=窗口模式(1280x800 可调整)
        """
        super().__init__()
        self.url = url
        self._is_fullscreen = fullscreen
        self._init_ui()

    def _init_ui(self):
        """初始化 UI。"""
        if self._is_fullscreen:
            # 全屏无边框
            self.setWindowFlags(Qt.FramelessWindowHint)
            self.showFullScreen()
        else:
            # 窗口模式:有标题栏,1280x800,可调整大小
            self.setWindowFlags(Qt.Window)
            self.resize(1280, 800)
            self.showNormal()

        # QWebEngineView
        self.view = QWebEngineView(self)
        self.page = FullscreenWebPage(self.view)
        self.view.setPage(self.page)

        # 诊断信号:加载进度 / 加载完成 / 渲染进程崩溃
        self.view.loadProgress.connect(
            lambda p: print(f'[WebEngine] 加载进度: {p}%'))
        self.view.loadFinished.connect(
            lambda ok: print(f'[WebEngine] 加载完成: ok={ok}'))
        # 渲染进程崩溃(QtWebEngineProcess.exe 异常退出)会导致页面白屏/网络失效
        self.page.renderProcessTerminated.connect(
            self._on_render_crash)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.view)

        # window.__pythonShell 标记已通过 QWebEngineScript 在 DocumentCreation 阶段注入
        # (见 main() 函数中的 marker_script),无需在 loadFinished 中重复注入

        # 加载前端
        self.view.setUrl(QUrl(self.url))

        print(f'[Window] 窗口已创建(模式: {"全屏" if self._is_fullscreen else "窗口"}),加载: {self.url}')

    def _on_render_crash(self, termination_type, exit_code):
        """渲染进程崩溃诊断。"""
        type_map = {
            0: 'Normal(正常结束)',
            1: 'Abnormal(异常退出)',
            2: 'Crashed(崩溃)',
            3: 'Killed(被杀死)',
        }
        type_str = type_map.get(termination_type, f'未知({termination_type})')
        print(f'[WebEngine ERROR] 渲染进程崩溃! 类型={type_str} 退出码={exit_code}')
        print(f'[WebEngine ERROR] 可能原因: GPU 驱动问题 / 资源加载失败 / 沙箱冲突')

    def keyPressEvent(self, event: QKeyEvent):
        """键盘事件:Alt+F4 / Ctrl+Shift+Q 退出。"""
        if event.key() == Qt.Key_F4 and (event.modifiers() & Qt.AltModifier):
            QApplication.quit()
        elif event.key() == Qt.Key_Q and (event.modifiers() & Qt.ControlModifier) and (event.modifiers() & Qt.ShiftModifier):
            QApplication.quit()
        else:
            super().keyPressEvent(event)

    def switch_to_config_mode(self):
        """切换到配置模式:取消全屏,显示标题栏,可调整大小。"""
        # 幂等:已是窗口模式则跳过(避免重复 setWindowFlags 导致窗口消失)
        if not self._is_fullscreen:
            return
        print('[Window] 切换到配置模式(窗口化)')
        self._is_fullscreen = False
        # Windows 上 setWindowFlags 对可见窗口会自动销毁并重建原生窗口,
        # 重建后窗口保持可见状态。不要手动 hide(),否则 showNormal() 可能无法恢复。
        self.setWindowFlags(Qt.Window)  # 恢复标题栏
        self.resize(1280, 800)
        self.move(100, 100)
        self.showNormal()
        self.activateWindow()
        self.raise_()
        print('[Window] 已切换到窗口模式')

    def switch_to_fullscreen_mode(self):
        """切换到全屏无边框模式(配置页选择全屏后动态生效)。"""
        # 幂等:已是全屏则跳过
        if self._is_fullscreen:
            return
        print('[Window] 切换到全屏无边框模式')
        self._is_fullscreen = True
        self.setWindowFlags(Qt.FramelessWindowHint)
        self.showFullScreen()
        self.activateWindow()
        self.raise_()
        print('[Window] 已切换到全屏模式')


def push_card_to_frontend(page, card_no):
    """通过 runJavaScript 把卡号推送给前端。

    前端在 useCardReader.ts 中定义 window.__onCardRead 全局函数。
    """
    # 转义卡号中的特殊字符(防止注入)
    safe_card = card_no.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
    js = f"window.__onCardRead && window.__onCardRead('{safe_card}');"
    page.runJavaScript(js)


# ===== 在线更新 =====
class UpdateCheckWorker(QThread):
    """后台线程:检测 GitHub 是否有新版本,避免阻塞主线程。"""
    result = pyqtSignal(object)  # 传递查到的 release dict,或 None

    def __init__(self, cfg, parent=None):
        super().__init__(parent)
        self.cfg = cfg

    def run(self):
        try:
            release = updater.check_for_update(
                config_update_check_url=self.cfg.get('update_check_url', ''),
                ignored_version=self.cfg.get('ignored_version', ''),
            )
        except Exception as e:
            print(f'[Updater] 检查更新异常: {e}')
            release = None
        self.result.emit(release)


def _show_update_dialog(parent, release):
    """弹出更新提示对话框。

    提供三个选项:
      下载更新 / 取消 / 忽略此版本
    """
    version = release.get('version', '')
    notes = (release.get('notes') or '').strip()
    asset_name = release.get('asset_name') or ''
    msg = f'检测到新版本 {version},是否立即更新?\n\n当前版本:{updater.get_current_version()}'
    if notes:
        msg += f'\n\n更新说明:\n{notes[:500]}'
    msg += '\n\n点击"下载更新"将自动下载并安装,您的配置会保留。'
    box = QMessageBox(parent)
    box.setWindowTitle('发现新版本')
    box.setIcon(QMessageBox.Information)
    box.setText(msg)
    btn_update = box.addButton('下载更新', QMessageBox.AcceptRole)
    box.addButton('取消', QMessageBox.RejectRole)
    btn_ignore = box.addButton('忽略此版本', QMessageBox.DestructiveRole)
    box.exec_()
    if box.clickedButton() == btn_update:
        return 'update'
    if box.clickedButton() == btn_ignore:
        return 'ignore'
    return 'cancel'


def _save_ignored_version(version):
    """记录用户忽略的版本号,下次检测到该版本时不再提示。"""
    try:
        write_config({'ignored_version': version})
    except Exception as e:
        print(f'[Updater] 记录忽略版本失败: {e}')


class UpdateDownloadWorker(QThread):
    """下载安装包线程。"""
    finished = pyqtSignal(bool, str)  # (成功, 安装包路径或错误信息)

    def __init__(self, release, dest_path, parent=None):
        super().__init__(parent)
        self.release = release
        self.dest_path = dest_path

    def run(self):
        try:
            url = self.release.get('asset_url', '')
            if not url:
                self.finished.emit(False, '未找到安装包下载地址')
                return
            updater.download_installer(url, self.dest_path)
            self.finished.emit(True, self.dest_path)
        except Exception as e:
            self.finished.emit(False, str(e))


def start_update_check(window, card_reader):
    """启动在线更新检测(后台线程)。

    检测到新版本时弹窗提示;用户选择"下载更新"后自动下载、
    退出本程序并静默运行新安装包。
    """
    cfg = read_full_config()

    def on_check_result(release):
        if not release:
            return
        choice = _show_update_dialog(window, release)
        if choice == 'ignore':
            _save_ignored_version(release.get('version', ''))
            return
        if choice != 'update':
            return

        # 6. 下载安装包
        asset_name = release.get('asset_name') or f'CanteenTerminal-Setup-{release.get("version", "")}.exe'
        from config import get_local_appdata_dir
        dest_dir = os.path.join(get_local_appdata_dir(), 'updates')
        os.makedirs(dest_dir, exist_ok=True)
        dest_path = os.path.join(dest_dir, asset_name)

        progress = QProgressDialog('正在下载更新,请稍候...', '取消', 0, 100, window)
        progress.setWindowTitle('下载更新')
        progress.setWindowModality(Qt.WindowModal)
        progress.setMinimumDuration(0)
        progress.show()

        worker = UpdateDownloadWorker(release, dest_path)

        def on_download_progress(done, total):
            if total > 0:
                progress.setValue(int(done * 100 / total))

        worker.finished.connect(progress.close)

        def on_download_finished(ok, msg):
            progress.close()
            if not ok:
                QMessageBox.critical(window, '更新失败', f'下载失败:{msg}\n请稍后重试或手动下载。')
                return
            print(f'[Updater] 下载完成,启动安装: {msg}')
            updater.run_installer(msg)
            # 退出本程序,让安装包接管
            QTimer.singleShot(500, card_reader.stop)
            QTimer.singleShot(1000, QApplication.quit)

        worker.finished.connect(on_download_finished)
        worker.start()
        # 引用 worker,防止被 GC(进度条关闭时释放)
        worker.setParent(window)

    check_thread = UpdateCheckWorker(cfg)
    check_thread.result.connect(on_check_result)
    check_thread.finished.connect(check_thread.deleteLater)
    check_thread.start()
    # 持有引用,防 GC
    check_thread.setParent(window)


def main():
    """主入口。"""
    # 启用 stdout 无缓冲,确保 print 立即输出(PyInstaller onedir 模式下默认全缓冲)
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass
    print('=' * 60, flush=True)
    print('企业智慧食堂终端 (Python Shell) - 诊断模式', flush=True)
    print('=' * 60, flush=True)

    # 0. 环境诊断:打印关键路径和权限,帮助定位问题
    import ctypes
    from config import get_appdata_dir, get_local_appdata_dir, get_config_path
    print(f'[Diag] EXE 路径: {sys.executable}', flush=True)
    print(f'[Diag] EXE 目录: {get_exe_dir()}', flush=True)
    print(f'[Diag] 配置目录: {get_appdata_dir()}', flush=True)
    print(f'[Diag] 配置文件: {get_config_path()}', flush=True)
    print(f'[Diag] 数据目录: {get_local_appdata_dir()}', flush=True)
    print(f'[Diag] Python: {sys.version}', flush=True)
    print(f'[Diag] 用户: {os.environ.get("USERNAME", "?")}', flush=True)
    print(f'[Diag] 是否管理员: {bool(ctypes.windll.shell32.IsUserAnAdmin()) if sys.platform == "win32" else "?"}', flush=True)

    # 1. 确保配置文件存在
    ensure_config_json()

    # 1.1 读取完整配置(window_mode / card_interval / idle_timeout / server_url)
    cfg = read_full_config()
    print(f'[Init] 配置: server_url={cfg["server_url"]}, 窗口模式={cfg["window_mode"]}, '
          f'读卡间隔={cfg["card_interval"]}s, 待机超时={cfg["idle_timeout"]}s', flush=True)
    print(f'[Init] 配置文件实际路径: {get_config_path()}', flush=True)

    # 2. 查找 Vue 前端 dist 目录
    web_dir = find_web_dist()
    if not web_dir:
        print('[Fatal] 未找到 Vue 前端 dist 目录!')
        print('  请确保 terminal/dist 已构建,或 web 目录存在。')
        input('按回车键退出...')
        sys.exit(1)
    print(f'[Init] Vue 前端目录: {web_dir}')

    # 3. 创建 QApplication(必须在创建任何 Qt 对象之前)
    app = QApplication(sys.argv)
    app.setApplicationName('企业智慧食堂终端')

    # 3.1 配置 QtWebEngine 持久化存储到 %LOCALAPPDATA%\CanteenTerminal\data,
    # 而非 EXE 同目录(安装版 EXE 在 Program Files 下只读,QtWebEngine 的
    # Network Service 子进程无法写入会导致 NetworkError)。
    # 必须在创建任何 QWebEngineView / QWebEngineProfile 使用方之前设置。
    #
    # 重要:必须禁用 HTTP 磁盘缓存(setHttpCacheType NoCache),
    # 否则重新打包 EXE 后,QtWebEngine 会加载旧缓存的 index.html,
    # 它引用旧哈希的 JS 文件(如 index-OldHash.js),但新包里只有
    # index-NewHash.js → 旧 JS 404 → 前端崩溃(localStorage 仍持久化)。
    data_dir = os.path.join(get_local_appdata_dir(), 'data')
    try:
        os.makedirs(data_dir, exist_ok=True)
    except Exception as e:
        print(f'[Init] data 目录创建失败: {e}')
    # 诊断:测试 data 目录是否真的可写(权限问题的最直接验证)
    try:
        test_file = os.path.join(data_dir, '.write_test')
        with open(test_file, 'w') as f:
            f.write('ok')
        os.remove(test_file)
        print(f'[Diag] data 目录可写测试: 通过 ({data_dir})', flush=True)
    except Exception as e:
        print(f'[Diag] data 目录可写测试: 失败! ({data_dir}) 错误: {e}', flush=True)

    # 清理上次崩溃/异常退出残留的 SQLite 锁文件和 journal 文件。
    # 残留的 -journal/-wal/-shm 文件会导致下次启动时 "database is locked" 死锁,
    # 进而导致 IndexedDB 无法写入(菜品图片缓存失效)。
    try:
        for lock_file in glob.glob(os.path.join(data_dir, '**', '*-journal'), recursive=True):
            os.remove(lock_file)
        for lock_file in glob.glob(os.path.join(data_dir, '**', '*-wal'), recursive=True):
            os.remove(lock_file)
        for lock_file in glob.glob(os.path.join(data_dir, '**', '*-shm'), recursive=True):
            os.remove(lock_file)
        for lock_file in glob.glob(os.path.join(data_dir, '**', 'LOCK'), recursive=True):
            os.remove(lock_file)
        print('[Init] 已清理 SQLite/LevelDB 残留锁文件')
    except Exception as e:
        print(f'[Init] 清理锁文件时出错(可忽略): {e}')

    profile = QWebEngineProfile.defaultProfile()
    profile.setPersistentStoragePath(data_dir)
    profile.setPersistentCookiesPolicy(QWebEngineProfile.AllowPersistentCookies)
    # 禁用 HTTP 磁盘缓存:每次启动都从本地服务器重新加载前端资源
    # localStorage / IndexedDB 仍由 setPersistentStoragePath 持久化
    profile.setHttpCacheType(QWebEngineProfile.NoCache)
    print(f'[Init] QtWebEngine 持久化目录: {data_dir} (HTTP 缓存已禁用)')

    # 关键:用 QWebEngineScript 在 DocumentCreation 阶段注入 window.__pythonShell = true
    # 必须早于 Vue onMounted 执行,否则 detectShell() 会返回 'browser',
    # 导致 Settings 运行设置卡不显示、switchToConfigMode/quitApp 走 browser 分支失效。
    # (loadFinished 信号触发时 Vue 早已挂载,太晚了)
    marker_script = QWebEngineScript()
    marker_script.setSourceCode('window.__pythonShell = true;')
    marker_script.setInjectionPoint(QWebEngineScript.DocumentCreation)
    marker_script.setWorldId(QWebEngineScript.MainWorld)
    marker_script.setName('python-shell-marker')
    marker_script.setRunsOnSubFrames(False)
    profile.scripts().insert(marker_script)
    print('[Init] 已注册 QWebEngineScript(DocumentCreation 阶段注入 __pythonShell)')

    # 4. 创建读卡器(传入初始防抖间隔)
    card_reader = CardReader(card_interval=cfg['card_interval'])
    card_reader.status.connect(lambda msg: print(f'[CardReader] {msg}'))

    # 5. 创建 ShellBridge
    bridge = ShellBridge(card_reader, read_config)

    # 6. 启动 HTTP 服务器(serve Vue dist + API 端点)
    server, server_url = start_server(web_dir, bridge)
    print(f'[Init] HTTP 服务器: {server_url}', flush=True)

    # 诊断:测试本地服务器是否真的能访问(index.html 是否可加载)
    try:
        import urllib.request
        with urllib.request.urlopen(server_url + '/', timeout=2) as resp:
            html = resp.read(200)
            print(f'[Diag] 本地服务器连通测试: 通过 (HTTP {resp.status}, 前{len(html)}字节)', flush=True)
    except Exception as e:
        print(f'[Diag] 本地服务器连通测试: 失败! 错误: {e}', flush=True)

    # 7. 创建主窗口(根据 window_mode 决定全屏/窗口)
    is_fullscreen = (cfg['window_mode'] == 'fullscreen')
    window = TerminalWindow(server_url, fullscreen=is_fullscreen)

    # 8. 连接 bridge 信号到窗口操作
    bridge.switch_to_config_requested.connect(window.switch_to_config_mode)
    bridge.switch_to_fullscreen_requested.connect(window.switch_to_fullscreen_mode)
    bridge.quit_requested.connect(QApplication.quit)

    # 8.1 配置更新信号:动态应用运行时参数(无需重启)
    def on_config_updated(updates):
        if 'card_interval' in updates:
            card_reader.set_interval(updates['card_interval'])
        # window_mode 变为 fullscreen 时动态切换全屏(无需重启)
        if updates.get('window_mode') == 'fullscreen':
            window.switch_to_fullscreen_mode()
        elif updates.get('window_mode') == 'windowed':
            window.switch_to_config_mode()

    bridge.config_updated.connect(on_config_updated)

    # 临时诊断:eval_js 信号 → 在前端执行 JS 并通过回调返回结果
    def on_eval_js(js_code):
        print(f'[EvalJS] 执行诊断 JS...')
        # runJavaScript 第二个参数是回调,返回 JS 表达式的值
        # JS 代码必须是表达式(或 IIFE),返回值会被序列化传回
        window.page.runJavaScript(js_code, 0, lambda result: print(f'[EvalJS Result] {result}'))
    bridge.eval_js_requested.connect(on_eval_js)

    # 9. 连接读卡器卡号信号 → 推送给前端
    card_reader.card_read.connect(
        lambda card_no: push_card_to_frontend(window.page, card_no)
    )

    # 9.1 在线更新检测(后台线程,不阻塞启动)
    try:
        start_update_check(window, card_reader)
    except Exception as e:
        print(f'[Init] 在线更新检测启动失败(可忽略): {e}')

    # 10. 启动读卡器(页面加载后)
    # 延迟启动,确保前端已就绪
    from PyQt5.QtCore import QTimer
    QTimer.singleShot(2000, card_reader.start)

    print('[Init] 初始化完成,进入事件循环')
    print('-' * 60)

    # 11. 运行事件循环
    exit_code = app.exec_()

    # 12. 清理
    print('[Cleanup] 正在停止读卡器...')
    card_reader.stop()
    print('[Cleanup] 正在关闭服务器...')
    server.shutdown()
    print('[Cleanup] 完成,退出')
    sys.exit(exit_code)


if __name__ == '__main__':
    main()
