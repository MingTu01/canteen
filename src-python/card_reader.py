"""
读卡器集成(X86 终端)- 读卡助手代理模式

X86 终端不再直接加载 OUR_IDR.dll(64 位 Python 无法加载 32 位 DLL),
而是通过 HTTP/SSE 连接本机读卡助手(127.0.0.1:8765)获取卡号。

架构:
  读卡助手(card_helper.exe, 32 位进程)独占 OUR_IDR.dll 硬件访问,
  在本机 127.0.0.1:8765 开放:
    GET /status  -> JSON 状态(connected / driver_ok / version / sse_clients)
    GET /events  -> SSE 推送读卡事件
    GET /beep    -> 蜂鸣测试

  X86 终端通过 SSE 长连接接收卡号事件,实现间接读卡。
  当 X86 终端订阅 SSE 后,读卡助手会自动暂停键盘注入,避免抢占。

信号:
  card_read(str): 读到卡号(已去重防抖,十进制格式)
  status(str): 状态信息(用于调试)
"""
import json
import threading
import time
import urllib.request

from PyQt5.QtCore import QObject, pyqtSignal


class CardReader(QObject):
    """读卡器封装(读卡助手代理模式)。

    信号:
        card_read(str): 读到卡号(已去重防抖,十进制格式)
        status(str): 状态信息(用于调试)
    """
    card_read = pyqtSignal(str)
    status = pyqtSignal(str)

    # 读卡助手代理模式的默认端口
    HELPER_PORT = 8765

    def __init__(self, card_interval=1.5):
        """
        Args:
            card_interval: 同卡号防抖间隔(秒),同一张卡在此间隔内不重复触发
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
        """启动读卡助手代理模式(通过 HTTP/SSE 连接本机读卡助手)。

        读卡助手(card_helper)是独立的 32 位进程,负责加载 OUR_IDR.dll
        和实际读卡,并在 127.0.0.1:8765 提供 SSE 事件流(/events)。
        X86 终端通过 SSE 长连接接收卡号事件,实现间接读卡。
        """
        if self._running:
            return

        # 先检测读卡助手是否在线
        if not self._check_helper_online():
            self.status.emit('读卡助手未运行,无法读卡(请先启动读卡助手)')
            return

        self._running = True
        self._last_ret = 0  # 代理模式下视为正常
        self.status.emit('已启用读卡助手代理模式(通过 SSE 获取卡号)')
        self._thread = threading.Thread(target=self._proxy_loop, daemon=True)
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

    def _proxy_loop(self):
        """读卡助手代理模式后台线程:连接 SSE 接收卡号事件。

        连接 http://127.0.0.1:8765/events,解析 SSE data 行,
        提取卡号后通过 card_read 信号发送到主线程。
        断线后自动重连(3 秒间隔),并重新检测读卡助手在线状态。
        """
        last_card = ''
        last_card_time = 0

        while self._running:
            try:
                url = f'http://127.0.0.1:{self._helper_port}/events'
                req = urllib.request.Request(url, headers={'Accept': 'text/event-stream'})
                self.status.emit('读卡助手 SSE 连接中...')
                with urllib.request.urlopen(req, timeout=10) as resp:
                    self.status.emit('读卡助手 SSE 已连接,等待刷卡...')
                    buf = ''
                    while self._running:
                        chunk = resp.read(256)
                        if not chunk:
                            break
                        buf += chunk.decode('utf-8', errors='replace')
                        # SSE 以双换行分隔事件
                        while '\n\n' in buf:
                            event_str, buf = buf.split('\n\n', 1)
                            # 解析 data 行
                            for line in event_str.split('\n'):
                                if line.startswith('data:'):
                                    raw = line[5:].strip()
                                    if not raw or raw.startswith(':'):
                                        continue
                                    try:
                                        msg = json.loads(raw)
                                        if msg.get('type') == 'status' and isinstance(msg.get('data'), str):
                                            # 转发读卡助手状态消息
                                            self.status.emit(f'[读卡助手] {msg["data"]}')
                                        elif msg.get('type') == 'card' and msg.get('data'):
                                            card_no = msg['data'].get('card_no', '')
                                            if card_no:
                                                # 防抖
                                                now = time.time()
                                                if card_no == last_card and (now - last_card_time) < self._card_interval:
                                                    continue
                                                last_card = card_no
                                                last_card_time = now
                                                self.status.emit(f'读到卡号: {card_no}')
                                                self.card_read.emit(card_no)
                                    except (json.JSONDecodeError, KeyError):
                                        pass
            except Exception as e:
                self._helper_online = False
                if self._running:
                    self.status.emit(f'读卡助手 SSE 断开: {e},3 秒后重连...')
                    time.sleep(3)
                    # 重连前再次检测读卡助手是否在线
                    self._check_helper_online()

    def stop(self):
        """停止读卡器线程。"""
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

        代理模式下:
            dll_loaded=False(本进程未加载 DLL),但 connected=True(读卡助手在线)
            mode='PROXY',description 标注代理模式

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
            'description': '读卡助手代理模式(通过 SSE 连接读卡助手)',
            'mode': 'PROXY',
            'interval': self._card_interval,
            'last_ret': 0 if online else None,
            'last_ret_desc': '代理模式-正常运行' if online else '代理模式-读卡助手未运行',
            'helper_online': online,
            'helper_port': self._helper_port,
        }
