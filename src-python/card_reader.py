"""
读卡器集成(X86 终端)- HID 键盘注入模式(读卡助手在线检测)

架构选择(单一硬件访问者):
  X86 终端虽为 32 位 Python 打包(可直接加载 OUR_IDR.dll),
  但为避免读卡器硬件被多进程同时访问导致冲突/失灵,
  统一由读卡助手(card_helper.exe)独占 OUR_IDR.dll 硬件访问。
  这样无论 X86 终端、admin-web 还是其他程序需要刷卡,
  都通过同一个读卡助手进程,彻底杜绝抢占问题。

工作原理(HID 键盘注入):
  读卡助手独占 OUR_IDR.dll 硬件访问,
  刷卡后将卡号模拟键盘输入(SendInput)到当前前台窗口。
  X86 终端保持绝对前台,前端 useCardReader.ts 通过 keydown 监听
  拼接卡号(Enter 结束),实现直接读卡,效率最高最稳定。

  本模块仅负责检测读卡助手是否在线(/status 接口),
  不再通过 SSE 接收卡号(卡号由前端 keydown 直接捕获)。

兼容性:
  X86 终端 + 读卡助手均使用 32 位 Python 打包,全面兼容 Win7 32 位及以上。

信号:
  card_read(str): 兼容保留(HID 模式下不触发,卡号由前端 keydown 捕获)
  status(str): 状态信息(用于调试)
"""
import json
import threading
import time
import urllib.request

from PyQt5.QtCore import QObject, pyqtSignal


class CardReader(QObject):
    """读卡器封装(HID 键盘注入模式 + 读卡助手在线检测)。

    信号:
        card_read(str): 兼容保留(HID 模式下不触发)
        status(str): 状态信息(用于调试)
    """
    card_read = pyqtSignal(str)
    status = pyqtSignal(str)

    # 读卡助手状态接口端口
    HELPER_PORT = 8765

    def __init__(self, card_interval=1.5):
        """
        Args:
            card_interval: 同卡号防抖间隔(秒,前端 keydown 监听使用)
        """
        super().__init__()
        self._running = False
        self._thread = None
        self._card_interval = max(0.5, float(card_interval))
        # 最近一次状态(用于 status_info)
        self._last_ret = None
        # 读卡助手在线状态
        self._helper_online = False
        self._helper_port = self.HELPER_PORT

    def set_interval(self, seconds):
        """动态更新防抖间隔(配置页修改后立即生效,无需重启读卡器)。"""
        if isinstance(seconds, (int, float)) and seconds > 0:
            self._card_interval = max(0.5, float(seconds))
            self.status.emit(f'防抖间隔已更新: {self._card_interval} 秒')

    def start(self):
        """启动读卡助手在线检测。

        HID 模式下卡号由前端 keydown 监听直接捕获(读卡助手键盘注入),
        本模块仅启动后台线程定期检测读卡助手是否在线,
        不再通过 SSE 接收卡号。
        """
        if self._running:
            return

        # 先检测读卡助手是否在线
        if not self._check_helper_online():
            self.status.emit('读卡助手未运行(请先启动读卡助手,刷卡输入依赖读卡助手)')
        else:
            self.status.emit('读卡助手已在线(HID 键盘注入模式,前端 keydown 监听就绪)')

        self._running = True
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self._thread.start()

    def _check_helper_online(self):
        """检测读卡助手是否在线(请求 /status 接口)。"""
        try:
            url = f'http://127.0.0.1:{self._helper_port}/status'
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=3) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                self._helper_online = True
                return data.get('connected', False) or data.get('dll_loaded', False)
        except Exception:
            self._helper_online = False
            return False

    def _monitor_loop(self):
        """后台监控线程:定期检测读卡助手在线状态(每 15 秒一次)。

        HID 模式下不接收卡号(前端 keydown 直接捕获),
        仅监控读卡助手在线状态,掉线时通知前端。
        """
        while self._running:
            time.sleep(15)
            if not self._running:
                break
            online = self._check_helper_online()
            if not online and self._helper_online:
                self.status.emit('读卡助手已离线,刷卡可能无效(请检查读卡助手)')
            elif online and not self._helper_online:
                self.status.emit('读卡助手已恢复在线')

    def stop(self):
        """停止监控线程。"""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2)
        self._thread = None
        self._helper_online = False

    def restart(self):
        """重启读卡器(前端设置页可调用)。"""
        self.stop()
        time.sleep(0.5)
        self.start()
        return self._running

    def status_info(self):
        """返回读卡器状态字典(供前端设备状态页展示)。

        HID 模式下:
            dll_loaded=False(本进程未加载 DLL)
            connected=True(读卡助手在线)
            mode='HID',description 标注 HID 键盘注入模式

        Returns:
            dict: 状态信息
        """
        # 实时检测一次读卡助手在线状态(轻量 HTTP 请求)
        online = self._check_helper_online()
        return {
            'running': self._running,
            'dll_loaded': False,
            'connected': online,
            'driver_ok': online,
            'description': 'HID 键盘注入模式(读卡助手模拟键盘,前端 keydown 捕获)',
            'mode': 'HID',
            'interval': self._card_interval,
            'last_ret': 0 if online else None,
            'last_ret_desc': 'HID 模式-读卡助手在线' if online else 'HID 模式-读卡助手未运行',
            'helper_online': online,
            'helper_port': self._helper_port,
        }
