# -*- coding: utf-8 -*-
"""
读卡助手(后台静默程序) - 把 OUR_IDR.dll 读卡器变成"模拟键盘输入+回车"

让 CH375/CH372 读卡器刷卡后,卡号像键盘打字一样自动输入到当前前台窗口,
并按可选设置追加回车。X86 终端保持前台时直接接收卡号,无需 SSE。

同时在本机 127.0.0.1:8765 开放一个状态接口(带 CORS),供 admin-web 显示
绿色/红色读卡状态图标、连接详情与驱动下载引导。

运行:
  * 开发/调试(带控制台):  C:\\Python310-32\\python.exe card_helper.py
  * 打包后(静默,无窗口):  card-helper.exe  (由 PyInstaller console=False 打包)

本机状态接口:
  GET /status    -> JSON 状态(connected / mode / driver_ok / version / auto_start)
  GET /beep      -> 蜂鸣测试

参数:
  --interval 秒    同卡防抖间隔(默认 1.5)
  --enter 0|1      刷卡后是否追加回车(默认 1,追加回车)
  --replace 0|1     输入前是否先 Ctrl+A 全选覆盖(默认 1,避免重复刷卡串号)
  --port 端口      状态接口端口(默认 8765)
  --no-autostart   不写入开机自启
"""
import ctypes
import datetime
import json
import os
import sys
import threading
import time
from ctypes import wintypes

# ---------------------------------------------------------------------------
# 0. 常量
# ---------------------------------------------------------------------------
VERSION = '1.4.0'
ERROR_CODES = {
    0: '操作成功',
    8: '卡不在感应区',
    21: '没有动态库',
    22: '动态库或驱动程序异常',
    23: '驱动程序错误或读卡器尚未安装',
    24: '操作超时',
    28: 'USB传输CRC校验错',
}

# 键盘注入常量
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
VK_RETURN = 0x0D
VK_CONTROL = 0x11
VK_A = 0x41

# 开机自启注册表项
RUN_KEY = r'Software\Microsoft\Windows\CurrentVersion\Run'
RUN_NAME = 'CanteenCardHelper'

def _bits():
    """Python 位数(加载 32 位 DLL 必须用 32 位 Python)。"""
    return ctypes.sizeof(ctypes.c_void_p) * 8


def log(msg):
    """写日志到 %LOCALAPPDATA%\\CanteenHelper\\helper.log。"""
    try:
        logdir = os.path.join(os.environ.get('LOCALAPPDATA', '.'), 'CanteenHelper')
        os.makedirs(logdir, exist_ok=True)
        logfile = os.path.join(logdir, 'helper.log')
        line = f'[{datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}] {msg}\n'
        with open(logfile, 'a', encoding='utf-8') as f:
            f.write(line)
    except Exception:
        pass
    # 调试时(有控制台)也打印
    if os.environ.get('CANTEEN_HELPER_DEBUG'):
        print(msg, flush=True)


# ---------------------------------------------------------------------------
# 1. 键盘注入(SendInput 模拟打字)
# ---------------------------------------------------------------------------
class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ('wVk', wintypes.WORD),
        ('wScan', wintypes.WORD),
        ('dwFlags', wintypes.DWORD),
        ('time', wintypes.DWORD),
        ('dwExtraInfo', ctypes.POINTER(wintypes.ULONG)),
    ]


class INPUT(ctypes.Structure):
    class _INPUT(ctypes.Union):
        _fields_ = [('ki', KEYBDINPUT), ('padding', ctypes.c_ubyte * 24)]

    _anonymous_ = ('_input',)
    _fields_ = [('type', wintypes.DWORD), ('_input', _INPUT)]


_user32 = ctypes.windll.user32


def _send_inputs(keys):
    """keys: list of (vk, scan, flags)"""
    n = len(keys)
    if n == 0:
        return 0
    arr = (INPUT * n)()
    for i, (vk, scan, flags) in enumerate(keys):
        arr[i].type = INPUT_KEYBOARD
        arr[i].ki.wVk = vk
        arr[i].ki.wScan = scan
        arr[i].ki.dwFlags = flags
        arr[i].ki.time = 0
        arr[i].ki.dwExtraInfo = None
    return _user32.SendInput(n, arr, ctypes.sizeof(INPUT))


def _press_ctrl_a():
    _send_inputs([
        (VK_CONTROL, 0, 0),
        (VK_A, 0, 0),
        (VK_A, 0, KEYEVENTF_KEYUP),
        (VK_CONTROL, 0, KEYEVENTF_KEYUP),
    ])


def type_text(text):
    """用 Unicode 扫描码输入一段文本(只支持可打印字符)。"""
    keys = []
    for ch in text:
        code = ord(ch)
        keys.append((0, code, KEYEVENTF_UNICODE))
        keys.append((0, code, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
    return _send_inputs(keys)


def press_enter():
    _send_inputs([
        (VK_RETURN, 0, 0),
        (VK_RETURN, 0, KEYEVENTF_KEYUP),
    ])


# ---------------------------------------------------------------------------
# 2. 开机自启
# ---------------------------------------------------------------------------
def ensure_autostart():
    """写入 HKCU Run 开机自启(幂等)。返回是否成功。"""
    try:
        import winreg
        exe = os.path.abspath(sys.executable)
        # 打包后 sys.executable 是 exe;开发时用 pythonw 避免弹窗
        if exe.lower().endswith('.exe') and 'python' not in os.path.basename(exe).lower():
            pass  # 打包后的 exe,直接用它
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0,
                            winreg.KEY_SET_VALUE) as key:
            winreg.SetValueEx(key, RUN_NAME, 0, winreg.REG_SZ, f'"{exe}"')
        log(f'开机自启已注册: {exe}')
        return True
    except Exception as e:
        log(f'注册开机自启失败: {e}')
        return False


# ---------------------------------------------------------------------------
# 3. 读卡器封装(ctypes + 线程,无 PyQt)
# ---------------------------------------------------------------------------
class CardReader:
    def __init__(self, interval, send_enter, replace_field):
        self._dll = None
        self._running = False
        self._thread = None
        self._interval = max(0.5, float(interval))
        self._send_enter = bool(send_enter)
        self._replace_field = bool(replace_field)
        self._last_ret = None  # 最近一次读卡返回码(用于状态)
        self._active = True    # 是否处于读卡状态(False 时暂停,让出读卡器给其他程序)

    # ---- DLL 加载 ----
    def _find_dll_dir(self):
        here = os.path.dirname(os.path.abspath(__file__))
        candidates = [here, os.path.dirname(here)]
        # PyInstaller 打包后 DLL 位于 _internal 目录
        meipass = getattr(sys, '_MEIPASS', None)
        if meipass:
            candidates.insert(0, meipass)
        for d in candidates:
            if os.path.exists(os.path.join(d, 'OUR_IDR.dll')):
                return d
        return None

    def load(self):
        dll_dir = self._find_dll_dir()
        if not dll_dir:
            log('未找到 OUR_IDR.dll')
            return False, '未找到 OUR_IDR.dll'
        our_idr = os.path.join(dll_dir, 'OUR_IDR.dll')
        idusb = os.path.join(dll_dir, 'IDUSB.DLL')
        try:
            os.add_dll_directory(dll_dir)
        except Exception:
            pass
        os.environ['PATH'] = dll_dir + os.pathsep + os.environ.get('PATH', '')
        if os.path.exists(idusb):
            try:
                ctypes.WinDLL(idusb)
                log('IDUSB.DLL 预加载成功')
            except Exception as e:
                log(f'IDUSB.DLL 预加载失败: {e}')
        try:
            self._dll = ctypes.WinDLL(our_idr)
            self._setup_prototypes()
            log('OUR_IDR.dll 加载成功')
            return True, 'OUR_IDR.dll 加载成功'
        except Exception as e:
            self._dll = None
            log(f'OUR_IDR.dll 加载失败: {e}')
            return False, f'OUR_IDR.dll 加载失败: {e}'

    def _setup_prototypes(self):
        d = self._dll
        d.idr_read.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        d.idr_read.restype = ctypes.c_ubyte
        d.idr_read_once.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        d.idr_read_once.restype = ctypes.c_ubyte
        d.idr_beep.argtypes = [ctypes.c_ulong]
        d.idr_beep.restype = ctypes.c_ubyte
        d.pcdgetdevicenumber.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        d.pcdgetdevicenumber.restype = ctypes.c_ubyte

    def beep(self, xms=38):
        if not self._dll:
            return -1
        try:
            return self._dll.idr_beep(xms)
        except Exception:
            return -1

    def status_info(self):
        """返回状态 dict,供 /status 使用。"""
        dll_loaded = self._dll is not None
        # 连接判定:最近一次读卡返回码为 0 或 8(感应区正常轮询)
        last = self._last_ret
        connected = dll_loaded and last is not None and last in (0, 8)
        driver_ok = dll_loaded and last is not None and last != 23
        return {
            'running': self._running,
            'dll_loaded': dll_loaded,
            'connected': connected,
            'driver_ok': driver_ok,
            'mode': 'HID',
            'active': self._active,
            'description': 'CH375/CH372 USB 读卡器(OUR_IDR.dll 模拟键盘,HID 模式)',
            'last_ret': last,
            'last_ret_desc': ERROR_CODES.get('' if last is None else last, '未知'),
            'interval': self._interval,
            'send_enter': self._send_enter,
            'python_bits': ctypes.sizeof(ctypes.c_void_p) * 8,
            'version': VERSION,
        }

    def start(self):
        if self._running:
            return
        ok, msg = self.load()
        if not ok:
            self._last_ret = 23
            log(msg)
            return
        self._running = True
        self._thread = threading.Thread(target=self._read_loop, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2)
        self._thread = None

    def set_active(self, active):
        """暂停/恢复读卡。暂停时不再调用 idr_read,读卡器让给其他程序。"""
        self._active = bool(active)
        if self._active:
            log('读卡已启用')
        else:
            log('读卡已暂停,读卡器已让出')

    def _read_loop(self):
        """纯 HID 键盘注入模式:刷卡后始终模拟键盘输入到当前前台窗口。

        X86 终端通过保持绝对前台接收键盘输入(useCardReader keydown 监听),
        无需 SSE 通道,效率更高更稳定。
        """
        # 蜂鸣确认
        try:
            ret = self.beep(38)
            self._last_ret = ret
            if ret == 0:
                log('读卡器连接成功(蜂鸣确认)')
            else:
                log(f'读卡器蜂鸣失败: {ret} ({ERROR_CODES.get(ret, "未知")})')
        except Exception as e:
            log(f'读卡器蜂鸣异常: {e}')

        # 设备号
        try:
            dev_buf = (ctypes.c_ubyte * 4)()
            ret = self._dll.pcdgetdevicenumber(dev_buf)
            if ret == 0:
                log(f'设备号: {"-".join(str(dev_buf[i]) for i in range(4))}')
        except Exception:
            pass

        log('开始监听刷卡(HID 键盘注入模式)...')

        card_buf = (ctypes.c_ubyte * 5)()
        last_card = ''
        last_time = 0
        error_count = 0

        while self._running:
            # 暂停时让出读卡器(不再调用 idr_read),供其他程序使用
            if not self._active:
                time.sleep(0.5)
                continue
            try:
                ret = self._dll.idr_read(card_buf)
            except Exception as e:
                log(f'idr_read 异常: {e}')
                time.sleep(1)
                continue
            self._last_ret = ret

            if ret == 0:
                error_count = 0
                data = bytes(card_buf[:5])
                card_no = parse_card_number(data)
                if card_no:
                    now = time.time()
                    if card_no == last_card and (now - last_time) < self._interval:
                        time.sleep(0.1)
                        continue
                    last_card = card_no
                    last_time = now
                    try:
                        self._dll.idr_beep(38)
                    except Exception:
                        pass
                    log(f'刷卡: {card_no}')
                    # 始终键盘注入(纯 HID 模式)
                    self._inject(card_no)
            elif ret == 8:
                error_count = 0
            else:
                error_count += 1
                err_msg = ERROR_CODES.get(ret, f'未知错误 {ret}')
                if error_count <= 3:
                    log(f'读卡异常: {ret} ({err_msg})')
                if ret in (22, 23, 24):
                    time.sleep(2)
                else:
                    time.sleep(0.5)
                continue

            time.sleep(0.1)

        log('读卡器线程退出')

    def _inject(self, card_no):
        """把卡号模拟成键盘输入到当前聚焦输入框。"""
        try:
            if self._replace_field:
                _press_ctrl_a()  # 全选,覆盖已有内容,避免重复刷卡串号
            time.sleep(0.02)
            type_text(card_no)
            if self._send_enter:
                time.sleep(0.02)
                press_enter()
        except Exception as e:
            log(f'键盘注入失败: {e}')


def parse_card_number(data):
    """5 字节数据解析卡号:第1字节厂商码,后4字节大端序转十进制。"""
    if not data or len(data) < 5:
        return ''
    return str(int.from_bytes(data[1:5], 'big'))


# ---------------------------------------------------------------------------
# 4. HTTP 状态服务(纯状态接口,无 SSE)
# ---------------------------------------------------------------------------
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_reader = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.end_headers()

    def do_GET(self):
        path = self.path.split('?')[0]
        if path == '/status':
            body = json.dumps(_reader.status_info(), ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self._cors()
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == '/beep':
            ret = _reader.beep(38)
            body = json.dumps({'ok': ret == 0, 'ret': ret}).encode('utf-8')
            self.send_response(200)
            self._cors()
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            body = b'not found'
            self.send_response(404)
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Content-Type', 'text/plain')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)


# ---------------------------------------------------------------------------
# 5. 托盘图标(pystray)
# ---------------------------------------------------------------------------
def message_box(title, text):
    """原生 Windows 消息框(置顶 + 前台显示,确保可见可关闭)。"""
    try:
        MB_OK = 0x0
        MB_ICONINFORMATION = 0x40
        MB_SETFOREGROUND = 0x00010000
        MB_TOPMOST = 0x00040000
        ctypes.windll.user32.MessageBoxW(
            None, text, title,
            MB_OK | MB_ICONINFORMATION | MB_SETFOREGROUND | MB_TOPMOST,
        )
    except Exception:
        pass


def _load_tray_image():
    """从打包目录/运行目录加载 icon.png;找不到就用纯色占位。"""
    try:
        from PIL import Image
        here = os.path.dirname(os.path.abspath(__file__))
        candidates = [here, os.path.dirname(here)]
        meipass = getattr(sys, '_MEIPASS', None)
        if meipass:
            candidates.insert(0, meipass)
        for d in candidates:
            p = os.path.join(d, 'icon.png')
            if os.path.exists(p):
                return Image.open(p)
    except Exception:
        pass
    from PIL import Image
    img = Image.new('RGBA', (64, 64), (34, 197, 94, 255))
    return img


class TrayController:
    """系统托盘:暂停使用 / 详情 / 退出。"""

    def __init__(self, reader, on_quit=None):
        self._reader = reader
        self._on_quit = on_quit
        self._paused = False
        self._icon = None

    def _make_menu(self):
        import pystray
        return pystray.Menu(
            pystray.MenuItem(lambda item: f'读卡助手 v{VERSION}', None, enabled=False),
            pystray.MenuItem(
                lambda item: '▶ 继续使用' if self._paused else '⏸ 暂停使用',
                self._toggle_pause,
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem('详情...', self._show_detail),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem('退出', self._quit),
        )

    def _toggle_pause(self, icon, item):
        # 暂停 = 让出读卡器给其他程序;继续 = 恢复读卡
        self._paused = not self._paused
        self._reader.set_active(not self._paused)
        log(f'读卡状态切换: paused={self._paused} active={not self._paused}')
        icon.update_menu()

    def _show_detail(self, icon, item):
        """在新线程中显示详情弹窗,避免阻塞 pystray 菜单线程。

        pystray 菜单回调在同一线程执行,如果直接调用阻塞式 MessageBoxW,
        可能导致菜单无法正常关闭、MessageBox 窗口无法获得焦点。
        新线程 + 延迟 0.15s 等菜单关闭后再弹窗,确保可点击关闭。
        """
        threading.Thread(target=self._do_show_detail, daemon=True).start()

    def _do_show_detail(self):
        time.sleep(0.15)  # 等待 pystray 菜单关闭
        active = self._reader._active
        kb_status = '已启用' if active else '已暂停(手动暂停中)'
        text = (
            f'读卡助手 v{VERSION}(开机静默自启)\n\n'
            '作用:\n'
            '在本机加载 CH375/CH372 读卡器(OUR_IDR.dll)。\n'
            '刷卡后自动把卡号模拟键盘输入到当前前台窗口,\n'
            'X86 终端保持前台时直接接收卡号,无需 SSE。\n\n'
            '当前状态:\n'
            f'· 键盘注入: {kb_status}\n'
            f'· 运行中: {"是" if self._reader._running else "否"}\n\n'
            '托盘操作:\n'
            '· 暂停使用: 暂时让出读卡器给其他程序,\n'
            '  再点「继续使用」恢复刷卡\n'
            '· 退出: 结束读卡助手\n\n'
            '注意: X86 终端必须在前台才能接收到刷卡输入。'
        )
        message_box('读卡助手', text)

    def _quit(self, icon, item):
        try:
            icon.stop()
        except Exception:
            pass
        if self._on_quit:
            self._on_quit()

    def start_in_thread(self):
        import pystray
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        import pystray
        self._icon = pystray.Icon(
            'canteen_card_helper',
            _load_tray_image(),
            '读卡助手',
            self._make_menu(),
        )
        self._icon.run()

    def stop(self):
        if self._icon:
            try:
                self._icon.stop()
            except Exception:
                pass


def parse_args(argv):
    import argparse
    p = argparse.ArgumentParser(description='读卡助手(OUR_IDR.dll 模拟键盘)')
    p.add_argument('--interval', type=float, default=1.5, help='同卡防抖间隔(秒)')
    p.add_argument('--enter', type=int, default=1, help='刷卡后是否追加回车(0/1)')
    p.add_argument('--replace', type=int, default=1, help='输入前是否 Ctrl+A 覆盖(0/1)')
    p.add_argument('--port', type=int, default=8765, help='状态接口端口')
    p.add_argument('--no-autostart', action='store_true', help='不写入开机自启')
    p.add_argument('--no-tray', action='store_true', help='不显示托盘(纯命令行调试)')
    return p.parse_args(argv)


def main(argv=None):
    global _reader
    args = parse_args(argv if argv is not None else sys.argv[1:])

    log('=' * 50)
    log(f'读卡助手 v{VERSION} 启动')
    log(f'Python 位数: {_bits()} 位')

    if _bits() != 32:
        log('[错误] 当前是 64 位 Python,无法加载 32 位 OUR_IDR.dll;请用 32 位 Python 运行')

    if not args.no_autostart:
        ensure_autostart()

    _reader = CardReader(interval=args.interval,
                         send_enter=args.enter,
                         replace_field=args.replace)

    tray = None
    if not args.no_tray:
        tray = TrayController(_reader, on_quit=lambda: (log('托盘退出'), os._exit(0)))
        tray.start_in_thread()
        log('托盘图标已启动')

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    log(f'状态接口: http://127.0.0.1:{args.port}/status')

    threading.Thread(target=lambda: (time.sleep(0.5), _reader.start()),
                     daemon=True).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log('正在退出...')
    finally:
        _reader.stop()
        server.shutdown()
        if tray:
            tray.stop()


if __name__ == '__main__':
    main()