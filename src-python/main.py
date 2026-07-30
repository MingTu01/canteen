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
    os.environ['QTWEBENGINE_CHROMIUM_FLAGS'] = '--no-sandbox --disable-gpu-sandbox --disable-software-rasterizer'
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
# 1. 本地 HTTP 端口(1287)被占用,第二个实例 fallback 到其他端口,
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

from PyQt5.QtCore import Qt, QUrl, QObject, pyqtSignal
from PyQt5.QtWidgets import QApplication, QWidget, QVBoxLayout
from PyQt5.QtWebEngineWidgets import QWebEngineView, QWebEnginePage, QWebEngineProfile, QWebEngineScript
from PyQt5.QtGui import QKeyEvent

from config import read_config, ensure_config_json, get_exe_dir, read_full_config
from server import find_web_dist, start_server
from bridge import ShellBridge
from card_reader import CardReader


class FullscreenWebPage(QWebEnginePage):
    """自定义 WebEnginePage,允许 JavaScript 控制台日志打印到 Python。"""

    def javaScriptConsoleMessage(self, level, message, line, source):
        # 前端 console.log 会打印到这里,方便调试
        prefix = {0: 'JS Log', 1: 'JS Warn', 2: 'JS Error', 3: 'JS Info'}.get(level, 'JS')
        print(f'[{prefix}] {message}')


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

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.view)

        # 页面加载完成后注入 Python Shell 标记
        self.view.loadFinished.connect(self._on_load_finished)

        # 加载前端
        self.view.setUrl(QUrl(self.url))

        print(f'[Window] 窗口已创建(模式: {"全屏" if self._is_fullscreen else "窗口"}),加载: {self.url}')

    def _on_load_finished(self, ok):
        """页面加载完成后注入 Python Shell 环境标记。"""
        if ok:
            self.page.runJavaScript('window.__pythonShell = true;')
            print('[Window] 已注入 window.__pythonShell = true')

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


def main():
    """主入口。"""
    # 启用 stdout 无缓冲,确保 print 立即输出(PyInstaller onedir 模式下默认全缓冲)
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass
    print('=' * 60, flush=True)
    print('企业智慧食堂终端 (Python Shell)', flush=True)
    print('=' * 60, flush=True)

    # 1. 确保配置文件存在
    ensure_config_json()

    # 1.1 读取完整配置(window_mode / card_interval / idle_timeout / server_url)
    cfg = read_full_config()
    print(f'[Init] 配置: 窗口模式={cfg["window_mode"]}, 读卡间隔={cfg["card_interval"]}s, '
          f'待机超时={cfg["idle_timeout"]}s')

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

    # 3.1 配置 QtWebEngine 持久化存储到 EXE 同目录的 data 子目录,
    # 避免默认存到系统临时目录导致 localStorage(终端绑定状态)在重启后丢失。
    # 必须在创建任何 QWebEngineView / QWebEngineProfile 使用方之前设置。
    #
    # 重要:必须禁用 HTTP 磁盘缓存(setHttpCacheType NoCache),
    # 否则重新打包 EXE 后,QtWebEngine 会加载旧缓存的 index.html,
    # 它引用旧哈希的 JS 文件(如 index-OldHash.js),但新包里只有
    # index-NewHash.js → 旧 JS 404 → 前端崩溃(localStorage 仍持久化)。
    data_dir = os.path.join(get_exe_dir(), 'data')
    try:
        os.makedirs(data_dir, exist_ok=True)
    except Exception as e:
        print(f'[Init] data 目录创建失败: {e}')

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
    print(f'[Init] HTTP 服务器: {server_url}')

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
