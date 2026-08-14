"""
读卡器集成(X86 终端)- DLL 直读为主,读卡助手 HID 为次级回退

架构选择(主次级模式):
  X86 终端为 32 位 Python 打包,优先直接加载 32 位 OUR_IDR.dll
  独占读卡器硬件,效率最高、最稳定(无需读卡助手、无需保持前台)。

  仅当 DLL 加载失败(驱动未装 / DLL 缺失 / 设备未连接)时,
  才回退到读卡助手 HID 键盘注入模式(读卡助手在线检测):
    - 读卡助手独占 OUR_IDR.dll,刷卡后模拟键盘输入到前台窗口
    - X86 终端前端通过 keydown 监听拼接卡号
    - 本模块仅检测读卡助手是否在线(/status 接口)

兼容性:
  X86 终端 + 读卡助手均使用 32 位 Python 打包,全面兼容 Win7 32 位及以上。

返回码(DLL 模式):
  0=成功  8=卡不在感应区  21=没有动态库  22=动态库或驱动异常
  23=驱动未装  24=超时  28=CRC校验错

信号:
  card_read(str): 读到卡号(DLL 模式触发;HID 模式下卡号由前端 keydown 捕获)
  status(str): 状态信息(用于调试)
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
    """读卡器封装(主:DLL 直读;次:读卡助手 HID 检测)。

    信号:
        card_read(str): 读到卡号(DLL 模式触发)
        status(str): 状态信息(用于调试)
    """
    card_read = pyqtSignal(str)
    status = pyqtSignal(str)

    # 读卡助手状态接口端口(HID 回退模式使用)
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
        # 当前工作模式:'DLL'(直读) / 'HID'(读卡助手回退) / None(未启动)
        self._mode = None
        # 最近一次 DLL 返回码(用于 status_info)
        self._last_ret = None
        # 读卡助手在线状态(HID 回退模式)
        self._helper_online = False
        self._helper_port = self.HELPER_PORT

    def set_interval(self, seconds):
        """动态更新防抖间隔(配置页修改后立即生效,无需重启读卡器)。"""
        if isinstance(seconds, (int, float)) and seconds > 0:
            self._card_interval = max(0.5, float(seconds))
            self.status.emit(f'防抖间隔已更新: {self._card_interval} 秒')

    def start(self):
        """启动读卡器:优先 DLL 直读,DLL 加载失败则回退 HID 模式。

        DLL 模式:加载 OUR_IDR.dll,后台线程轮询读取卡号,通过 card_read 信号推送。
        HID  模式:仅检测读卡助手是否在线(卡号由前端 keydown 直接捕获)。
        """
        if self._running:
            return

        # ===== 主模式:尝试直接加载 OUR_IDR.dll =====
        if self._try_start_dll():
            return  # DLL 直读启动成功

        # ===== 次级模式:DLL 加载失败,回退到读卡助手 HID 检测 =====
        self._start_helper_mode()

    def _try_start_dll(self):
        """尝试加载 OUR_IDR.dll 并启动直读线程。

        Returns:
            True: DLL 加载成功,直读线程已启动
            False: DLL 加载失败(需回退 HID 模式)
        """
        dll_dir = self._find_dll_dir()
        if not dll_dir:
            self.status.emit('OUR_IDR.dll 未找到,将回退到读卡助手 HID 模式')
            return False

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
                self.status.emit('IDUSB.DLL 预加载成功')
            except Exception as e:
                self.status.emit(f'IDUSB.DLL 预加载失败: {e}')

        # 加载 OUR_IDR.dll
        try:
            self._dll = ctypes.WinDLL(our_idr_path)
            self._setup_prototypes()
            self.status.emit('OUR_IDR.dll 加载成功(DLL 直读模式)')
        except Exception as e:
            self.status.emit(f'OUR_IDR.dll 加载失败: {e},将回退到读卡助手 HID 模式')
            self._dll = None
            return False

        self._mode = 'DLL'
        self._running = True
        self._thread = threading.Thread(target=self._dll_read_loop, daemon=True)
        self._thread.start()
        return True

    def _start_helper_mode(self):
        """启动读卡助手 HID 在线检测模式(次级回退)。

        HID 模式下卡号由前端 keydown 监听直接捕获(读卡助手键盘注入),
        本模块仅启动后台线程定期检测读卡助手是否在线。
        """
        self._mode = 'HID'
        if not self._check_helper_online():
            self.status.emit('读卡助手未运行(DLL 直读失败且读卡助手离线,请检查驱动或启动读卡助手)')
        else:
            self.status.emit('已回退到读卡助手 HID 模式(前端 keydown 监听就绪)')

        self._running = True
        self._thread = threading.Thread(target=self._helper_monitor_loop, daemon=True)
        self._thread.start()

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
            if d and os.path.exists(os.path.join(d, 'OUR_IDR.dll')):
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

    def _dll_read_loop(self):
        """DLL 直读后台线程主循环。"""
        # 1. 蜂鸣确认读卡器连接
        try:
            ret = self._dll.idr_beep(38)  # 38*2ms = 76ms 短响
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

        self.status.emit('开始监听刷卡(DLL 直读模式)...')

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

        self.status.emit('读卡器线程退出(DLL 直读模式)')

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

    def _helper_monitor_loop(self):
        """读卡助手在线状态监控线程(每 15 秒一次,HID 回退模式)。

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
        """停止读卡器线程。"""
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2)
        self._thread = None
        self._dll = None
        self._mode = None
        self._helper_online = False

    def restart(self):
        """重启读卡器(前端设置页可调用)。"""
        self.stop()
        time.sleep(0.5)
        self.start()
        return self._running

    def status_info(self):
        """返回读卡器状态字典(供前端设备状态页展示)。

        根据 _mode 返回不同字段:
          DLL 模式:dll_loaded=True, connected 依据设备通信,mode='DLL'
          HID 模式:dll_loaded=False, connected=读卡助手在线, mode='HID'

        Returns:
            dict: 状态信息
        """
        mode = self._mode
        if mode == 'DLL':
            # DLL 直读模式:已加载 DLL 即视为驱动 OK
            last_ret = self._last_ret
            return {
                'running': self._running,
                'dll_loaded': True,
                'connected': True,
                'driver_ok': True,
                'description': 'DLL 直读模式(直接加载 OUR_IDR.dll,无需读卡助手)',
                'mode': 'DLL',
                'interval': self._card_interval,
                'last_ret': last_ret if last_ret is not None else 0,
                'last_ret_desc': ERROR_CODES.get(last_ret, '') if last_ret is not None else '操作成功',
                'helper_online': False,
                'helper_port': self._helper_port,
            }
        # HID 回退模式:实时检测读卡助手在线状态
        online = self._check_helper_online()
        return {
            'running': self._running,
            'dll_loaded': False,
            'connected': online,
            'driver_ok': online,
            'description': 'HID 键盘注入模式(DLL 直读失败回退,读卡助手模拟键盘)',
            'mode': 'HID',
            'interval': self._card_interval,
            'last_ret': 0 if online else None,
            'last_ret_desc': 'HID 模式-读卡助手在线' if online else 'HID 模式-读卡助手未运行',
            'helper_online': online,
            'helper_port': self._helper_port,
        }


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
