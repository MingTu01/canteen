"""
读卡器集成 - 使用 OUR_IDR.dll (广州荣士电子 SDK)

通过 ctypes 加载 32 位 OUR_IDR.dll,后台线程轮询读取 ID 卡卡号,
通过 pyqtSignal 发送到主线程(线程安全)。

工作原理:
1. 加载 OUR_IDR.dll(优先 PyInstaller 临时目录,其次 EXE 同目录)
2. 同时加载 IDUSB.DLL(OUR_IDR.dll 的依赖)
3. 后台线程循环调用 idr_read() 读取卡号
4. 解析卡号:后4字节大端序转十进制(与员工录入格式一致)
5. 防抖:同一卡号 1.5 秒内不重复触发
6. 通过 card_read 信号发送到主线程

降级模式(读卡助手代理):
  当 OUR_IDR.dll 加载失败(64位进程无法加载32位DLL)时,
  自动降级为通过 HTTP/SSE 连接本机读卡助手(127.0.0.1:8765/events)
  获取卡号。读卡助手是独立的32位进程,负责加载DLL和实际读卡,
  X86终端通过SSE长连接接收卡号事件,实现间接读卡。

返回码:
  0=成功  8=卡不在感应区  21=没有动态库  22=动态库或驱动异常
  23=驱动未装  24=超时  28=CRC校验错
"""
import ctypes
import json
import os
import threading
import time
import urllib.request

from PyQt5.QtCore import QObject, pyqtSignal


# 错误码描述
ERROR_CODES = {
    0: '操作成功',
    8: '卡不在感应区',
    21: '没有动态库',
    22: '动态库或驱动程序异常',
    23: '驱动程序错误或读卡器尚未安装',
    24: '操作超时',
    28: 'USB传输CRC校验错',
}


class CardReader(QObject):
    """读卡器封装(基于 OUR_IDR.dll)。

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
        self._dll = None
        self._running = False
        self._thread = None
        self._card_interval = max(0.5, float(card_interval))
        # 最近一次 idr_read 返回码,用于判断真实硬件连接状态
        # 0=读到卡 8=卡不在感应区(正常) 22/23/24=设备异常(拔掉/驱动错)
        self._last_ret = None
        # 读卡助手代理模式(DLL加载失败时自动启用)
        self._proxy_mode = False
        self._proxy_thread = None
        self._helper_port = self.HELPER_PORT

    def set_interval(self, seconds):
        """动态更新防抖间隔(配置页修改后立即生效,无需重启读卡器)。"""
        if isinstance(seconds, (int, float)) and seconds > 0:
            self._card_interval = max(0.5, float(seconds))
            self.status.emit(f'防抖间隔已更新: {self._card_interval} 秒')

    def start(self):
        """加载 DLL 并启动读卡器后台线程。

        如果 DLL 不存在或加载失败(64位进程无法加载32位DLL),
        自动降级为读卡助手代理模式(通过HTTP/SSE连接本机读卡助手)。
        """
        if self._running:
            return

        # 查找 DLL 路径
        dll_dir = self._find_dll_dir()
        if not dll_dir:
            self.status.emit('OUR_IDR.dll 未找到,尝试读卡助手代理模式...')
            self._start_proxy_mode()
            return

        our_idr_path = os.path.join(dll_dir, 'OUR_IDR.dll')
        idusb_path = os.path.join(dll_dir, 'IDUSB.DLL')

        # 把 DLL 目录加到搜索路径,让 OUR_IDR.dll 能找到 IDUSB.DLL
        try:
            os.add_dll_directory(dll_dir)
        except Exception:
            pass
        os.environ['PATH'] = dll_dir + os.pathsep + os.environ.get('PATH', '')

        # 先加载 IDUSB.DLL(OUR_IDR.dll 依赖它)
        if os.path.exists(idusb_path):
            try:
                ctypes.WinDLL(idusb_path)
                self.status.emit(f'IDUSB.DLL 预加载成功')
            except Exception as e:
                self.status.emit(f'IDUSB.DLL 预加载失败: {e}')

        # 加载 OUR_IDR.dll
        try:
            # __stdcall 调用约定
            self._dll = ctypes.WinDLL(our_idr_path)
            self._setup_prototypes()
            self.status.emit(f'OUR_IDR.dll 加载成功')
        except Exception as e:
            self.status.emit(f'OUR_IDR.dll 加载失败(可能是64位进程加载32位DLL): {e}')
            self._dll = None
            # 降级为读卡助手代理模式
            self._start_proxy_mode()
            return

        self._running = True
        self._thread = threading.Thread(target=self._read_loop, daemon=True)
        self._thread.start()

    def _start_proxy_mode(self):
        """降级为读卡助手代理模式:通过HTTP/SSE连接本机读卡助手获取卡号。

        读卡助手(card_helper)是独立的32位进程,负责加载OUR_IDR.dll和实际读卡,
        并在127.0.0.1:8765提供SSE事件流(/events)。
        X86终端通过SSE长连接接收卡号事件,实现间接读卡。
        """
        # 先检测读卡助手是否在线
        if not self._check_helper_online():
            self.status.emit('读卡助手未运行,无法启用代理模式(请先启动读卡助手)')
            return

        self._proxy_mode = True
        self._running = True
        self._last_ret = 0  # 代理模式下视为正常
        self.status.emit('已启用读卡助手代理模式(通过SSE获取卡号)')
        self._proxy_thread = threading.Thread(target=self._proxy_loop, daemon=True)
        self._proxy_thread.start()

    def _check_helper_online(self):
        """检测读卡助手是否在线(请求/status接口)。"""
        try:
            url = f'http://127.0.0.1:{self._helper_port}/status'
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=3) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                return data.get('connected', False) or data.get('dll_loaded', False)
        except Exception:
            return False

    def _proxy_loop(self):
        """读卡助手代理模式后台线程:连接SSE接收卡号事件。

        连接 http://127.0.0.1:8765/events,解析SSE data行,
        提取卡号后通过card_read信号发送到主线程。
        断线后自动重连(3秒间隔)。
        """
        last_card = ''
        last_card_time = 0

        while self._running:
            try:
                url = f'http://127.0.0.1:{self._helper_port}/events'
                req = urllib.request.Request(url, headers={'Accept': 'text/event-stream'})
                self.status.emit('读卡助手SSE连接中...')
                with urllib.request.urlopen(req, timeout=10) as resp:
                    self.status.emit('读卡助手SSE已连接,等待刷卡...')
                    buf = ''
                    while self._running:
                        chunk = resp.read(256)
                        if not chunk:
                            break
                        buf += chunk.decode('utf-8', errors='replace')
                        # SSE以双换行分隔事件
                        while '\n\n' in buf:
                            event_str, buf = buf.split('\n\n', 1)
                            # 解析data行
                            for line in event_str.split('\n'):
                                if line.startswith('data:'):
                                    raw = line[5:].strip()
                                    if not raw or raw.startswith(':'):
                                        continue
                                    try:
                                        msg = json.loads(raw)
                                        if msg.get('type') == 'card' and msg.get('data'):
                                            card_no = msg['data'].get('card_no', '')
                                            if card_no:
                                                # 防抖
                                                now = time.time()
                                                if card_no == last_card and (now - last_card_time) < self._card_interval:
                                                    continue
                                                last_card = card_no
                                                last_card_time = now
                                                self.status.emit(f'读到卡号(代理): {card_no}')
                                                self.card_read.emit(card_no)
                                    except (json.JSONDecodeError, KeyError):
                                        pass
            except Exception as e:
                if self._running:
                    self.status.emit(f'读卡助手SSE断开: {e},3秒后重连...')
                    time.sleep(3)

    def stop(self):
        """停止读卡器线程。"""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2)
        self._thread = None
        if self._proxy_thread and self._proxy_thread.is_alive():
            self._proxy_thread.join(timeout=2)
        self._proxy_thread = None
        self._dll = None
        self._proxy_mode = False

    def restart(self):
        """重启读卡器(前端设置页可调用)。"""
        self.stop()
        time.sleep(0.5)
        self.start()
        return self._running

    def status_info(self):
        """返回读卡器状态字典(供前端设备状态页展示)。

        代理模式下:
            dll_loaded=False(本进程未加载DLL),但connected=True(读卡助手在线)
            mode='PROXY',description标注代理模式

        Returns:
            dict: {
                running: 读卡线程是否运行,
                dll_loaded: DLL 是否加载成功,
                connected: 设备是否真实连接(基于返回码,非线程状态),
                driver_ok: 驱动是否正常,
                description: 设备描述,
                mode: 读卡器模式,
                interval: 防抖间隔,
                last_ret: 最近一次返回码,
                last_ret_desc: 返回码描述,
            }
        """
        if self._proxy_mode:
            return {
                'running': self._running,
                'dll_loaded': False,
                'connected': self._running,
                'driver_ok': True,
                'description': '读卡助手代理模式(通过SSE连接读卡助手)',
                'mode': 'PROXY',
                'interval': self._card_interval,
                'last_ret': self._last_ret,
                'last_ret_desc': '代理模式-正常运行' if self._running else '代理模式-未连接',
            }
        dll_loaded = self._dll is not None
        last = self._last_ret
        # 连接判定:最近一次读卡返回码为 0 或 8(感应区正常轮询)
        connected = dll_loaded and last is not None and last in (0, 8)
        driver_ok = dll_loaded and last is not None and last != 23
        return {
            'running': self._running,
            'dll_loaded': dll_loaded,
            'connected': connected,
            'driver_ok': driver_ok,
            'description': 'CH375/CH372 USB 读卡器(OUR_IDR.dll)',
            'mode': 'OUR_IDR',
            'interval': self._card_interval,
            'last_ret': last,
            'last_ret_desc': ERROR_CODES.get(last, '未知') if last is not None else '未检测',
        }

    def _find_dll_dir(self):
        """查找 OUR_IDR.dll 所在目录。

        优先级:
        1. PyInstaller 临时目录(_MEIPASS,打包时内嵌)
        2. EXE 同目录(外部放置)
        3. 开发模式下的 src-python 目录
        """
        from config import get_meipass, get_exe_dir

        candidates = [
            get_meipass(),
            get_exe_dir(),
            # 开发模式
            os.path.dirname(os.path.abspath(__file__)),
        ]

        for d in candidates:
            if os.path.exists(os.path.join(d, 'OUR_IDR.dll')):
                return d
        return None

    def _setup_prototypes(self):
        """设置函数的参数和返回值类型(__stdcall)。"""
        d = self._dll

        # unsigned char __stdcall idr_read(unsigned char *serial)
        # 读取5字节卡号:第1字节厂商码,后4字节卡序列号
        d.idr_read.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        d.idr_read.restype = ctypes.c_ubyte

        # unsigned char __stdcall idr_read_once(unsigned char *serial)
        # 读一次(需拿开卡再放回才能再读)
        d.idr_read_once.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        d.idr_read_once.restype = ctypes.c_ubyte

        # unsigned char __stdcall idr_beep(unsigned long xms)
        # 蜂鸣,xms 单位2毫秒
        d.idr_beep.argtypes = [ctypes.c_ulong]
        d.idr_beep.restype = ctypes.c_ubyte

        # unsigned char __stdcall pcdgetdevicenumber(unsigned char *devicenumbe)
        # 4字节设备号
        d.pcdgetdevicenumber.argtypes = [ctypes.POINTER(ctypes.c_ubyte)]
        d.pcdgetdevicenumber.restype = ctypes.c_ubyte

    def _read_loop(self):
        """读卡器后台线程主循环。"""
        # 1. 蜂鸣确认读卡器连接
        try:
            ret = self._dll.idr_beep(38)  # 38*2ms = 76ms 短响
            self._last_ret = ret
            if ret == 0:
                self.status.emit('读卡器连接成功(蜂鸣确认)')
            else:
                self.status.emit(f'读卡器蜂鸣失败: {ret} ({ERROR_CODES.get(ret, "未知")})')
        except Exception as e:
            self.status.emit(f'读卡器蜂鸣异常: {e}')

        # 2. 读取设备号(确认通信正常)
        try:
            dev_buf = (ctypes.c_ubyte * 4)()
            ret = self._dll.pcdgetdevicenumber(dev_buf)
            if ret == 0:
                dev_num = '-'.join(str(dev_buf[i]) for i in range(4))
                self.status.emit(f'设备号: {dev_num}')
        except Exception:
            pass

        self.status.emit('开始监听刷卡...')

        # 3. 循环读卡
        card_buf = (ctypes.c_ubyte * 5)()
        last_card = ''
        last_card_time = 0
        error_count = 0

        while self._running:
            try:
                ret = self._dll.idr_read(card_buf)
            except Exception as e:
                self.status.emit(f'idr_read 异常: {e}')
                time.sleep(1)
                continue
            # 记录最近一次返回码,供 status_info() 判断真实连接状态
            self._last_ret = ret

            if ret == 0:
                # 读卡成功
                error_count = 0
                data = bytes(card_buf[:5])
                card_no = parse_card_number(data)

                if card_no:
                    # 防抖:同一卡号在 _card_interval 秒内不重复触发
                    now = time.time()
                    if card_no == last_card and (now - last_card_time) < self._card_interval:
                        time.sleep(0.1)
                        continue

                    last_card = card_no
                    last_card_time = now

                    # 蜂鸣提示
                    try:
                        self._dll.idr_beep(38)
                    except Exception:
                        pass

                    self.status.emit(f'读到卡号: {card_no}')
                    # 通过信号发送到主线程(Qt 会自动跨线程传递)
                    self.card_read.emit(card_no)

            elif ret == 8:
                # 卡不在感应区,正常情况
                error_count = 0
            else:
                # 异常错误
                error_count += 1
                err_msg = ERROR_CODES.get(ret, f'未知错误 {ret}')
                if error_count <= 3:
                    self.status.emit(f'读卡异常: {ret} ({err_msg})')
                # 22/23/24 说明设备异常,等2秒再试,避免日志刷屏
                if ret in (22, 23, 24):
                    time.sleep(2)
                else:
                    time.sleep(0.5)
                continue

            # 短暂休眠,避免 CPU 占用过高
            time.sleep(0.1)

        self.status.emit('读卡器线程退出')


def parse_card_number(data):
    """从 OUR_IDR.dll 读取的5字节数据中解析卡号。

    数据格式:
      byte 0: 厂商码
      byte 1-4: 卡序列号(大端序)

    卡号格式:后4字节大端序转十进制(与员工录入格式一致)
    例如: 03 00 83 AC 41 -> 8629313
          69 00 DE F1 B2 -> 14610866
    """
    if not data or len(data) < 5:
        return ''

    # 后4字节大端序转十进制
    card_serial = data[1:5]
    card_no = str(int.from_bytes(card_serial, 'big'))

    return card_no
