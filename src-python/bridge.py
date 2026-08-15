"""
Shell 桥接:处理前端 → Python 的 API 调用。

采用 HTTP API 方式(前端 fetch /__api__/xxx),不需要 QWebChannel。
跨线程操作 Qt 窗口通过 pyqtSignal(线程安全)。

API 端点:
    GET  /__api__/server_url          获取预设服务器地址
    GET  /__api__/config              获取完整配置(window_mode/card_interval/idle_timeout/server_url)
    GET  /__api__/token_load          读取 DPAPI 加密存储的终端 token
    POST /__api__/set_config          更新配置(部分字段,写入前逐项校验取值)
    POST /__api__/token_save          DPAPI 加密保存终端 token(空串即清除)
    POST /__api__/switch_to_config    切换到配置模式(取消全屏)
    POST /__api__/switch_to_fullscreen 切换回全屏模式
    POST /__api__/quit                退出应用
    POST /__api__/restart_card_reader 重启读卡器
"""
import json
import os
from PyQt5.QtCore import QObject, pyqtSignal

import dpapi
from config import read_full_config, write_config, get_appdata_dir, validate_config_value


class ShellBridge(QObject):
    """Shell 桥接对象。

    信号(跨线程安全,emit 后在主线程执行槽函数):
        switch_to_config_requested: 请求切换到配置模式(窗口化)
        switch_to_fullscreen_requested: 请求切换回全屏模式
        quit_requested: 请求退出应用
        config_updated: 配置已更新(参数为更新字段 dict,供 main.py 重新加载运行时配置)
    """
    switch_to_config_requested = pyqtSignal()
    switch_to_fullscreen_requested = pyqtSignal()
    quit_requested = pyqtSignal()
    config_updated = pyqtSignal(dict)

    def __init__(self, card_reader, get_server_url_func):
        """
        Args:
            card_reader: CardReader 实例
            get_server_url_func: 获取服务器地址的函数(返回 str)
        """
        super().__init__()
        self.card_reader = card_reader
        self.get_server_url_func = get_server_url_func

    def handle_api(self, method, body=None):
        """处理 API 调用,返回响应 dict。

        Args:
            method: API 方法名
            body: POST 请求体(dict),可选

        Returns:
            dict: {ok: bool, ...}
        """
        if method == 'server_url':
            return {'ok': True, 'server_url': self.get_server_url_func()}

        elif method == 'config':
            # 返回完整运行配置(供前端读取 window_mode/card_interval/idle_timeout)
            return {'ok': True, 'config': read_full_config()}

        elif method == 'set_config':
            # 更新部分配置字段,body = { window_mode?, card_interval?, idle_timeout?, server_url?, update_check_url? }
            if not isinstance(body, dict):
                return {'ok': False, 'error': '请求体必须是 JSON 对象'}
            # 字段白名单 + 类型校验
            allowed = {
                'window_mode': str,
                'card_interval': (int, float),
                'idle_timeout': (int, float),
                'server_url': str,
                'update_check_url': str,
            }
            updates = {}
            for key, expected_type in allowed.items():
                if key in body:
                    val = body[key]
                    if not isinstance(val, expected_type):
                        return {'ok': False, 'error': f'{key} 类型错误'}
                    # 逐项取值校验(URL 格式/数值范围/枚举值),
                    # 非法时直接返回错误,不写入 config.json
                    err = validate_config_value(key, val)
                    if err:
                        return {'ok': False, 'error': err}
                    updates[key] = val
            if not updates:
                return {'ok': False, 'error': '没有可更新的字段'}
            ok = write_config(updates)
            if ok:
                # 通知 main.py / card_reader 重新加载运行时参数
                self.config_updated.emit(updates)
                return {'ok': True, 'updated': updates}
            return {'ok': False, 'error': '写入 config.json 失败'}

        elif method == 'switch_to_config':
            # 通过信号在主线程执行 Qt 窗口操作(线程安全)
            print(f'[Bridge] 收到 switch_to_config 请求,emit 信号')
            self.switch_to_config_requested.emit()
            return {'ok': True}

        elif method == 'switch_to_fullscreen':
            # 切换回全屏无边框模式(配置页选择全屏后动态生效)
            print(f'[Bridge] 收到 switch_to_fullscreen 请求,emit 信号')
            self.switch_to_fullscreen_requested.emit()
            return {'ok': True}

        elif method == 'quit':
            # 写入正常退出标记(%APPDATA%\CanteenTerminal\exit.flag):
            # watchdog 巡检时发现该标记则不再拉起主进程并自行退出,
            # 否则用户主动退出后 15 秒内会被 watchdog 重新拉起,无法维护
            try:
                flag_dir = get_appdata_dir()
                os.makedirs(flag_dir, exist_ok=True)
                with open(os.path.join(flag_dir, 'exit.flag'), 'w', encoding='utf-8') as f:
                    f.write('quit')
                print('[Bridge] 已写入正常退出标记 exit.flag')
            except Exception as e:
                print(f'[Bridge] 写入退出标记失败: {e}')
            self.quit_requested.emit()
            return {'ok': True}

        elif method == 'restart_card_reader':
            running = self.card_reader.restart()
            return {'ok': True, 'running': running}

        elif method == 'device_status':
            # 返回读卡器设备状态(供前端设置页设备检查展示)
            reader_status = self.card_reader.status_info()
            return {'ok': True, 'card_reader': reader_status}

        elif method == 'token_save':
            # 终端 token 加密存储:DPAPI 加密(绑定当前 Windows 用户)后
            # 写入 <配置目录>/token.bin,localStorage 明文仅作降级兜底。
            # 传空串表示清除(解绑时调用)。
            if not isinstance(body, dict) or 'token' not in body or not isinstance(body['token'], str):
                return {'ok': False, 'error': '需要 token 字段(字符串)'}
            token = body['token']
            token_path = os.path.join(get_appdata_dir(), 'token.bin')
            try:
                os.makedirs(get_appdata_dir(), exist_ok=True)
                if token == '':
                    # 空串 = 清除 shell 侧存储
                    if os.path.exists(token_path):
                        os.remove(token_path)
                    return {'ok': True}
                encrypted = dpapi.protect(token.encode('utf-8'))
                if encrypted is None:
                    return {'ok': False, 'error': 'DPAPI 加密不可用,token 未保存'}
                with open(token_path, 'wb') as f:
                    f.write(encrypted)
                return {'ok': True}
            except Exception as e:
                return {'ok': False, 'error': f'保存 token 失败: {e}'}

        elif method == 'token_load':
            # 读取 DPAPI 加密存储的终端 token。
            # 文件不存在/解密失败一律返回 token=None(不抛 500),前端降级 localStorage。
            token_path = os.path.join(get_appdata_dir(), 'token.bin')
            if not os.path.exists(token_path):
                return {'ok': True, 'token': None}
            try:
                with open(token_path, 'rb') as f:
                    encrypted = f.read()
                data = dpapi.unprotect(encrypted)
                if data is None:
                    return {'ok': True, 'token': None}
                return {'ok': True, 'token': data.decode('utf-8', errors='replace')}
            except Exception:
                return {'ok': True, 'token': None}

        return {'ok': False, 'error': f'未知方法: {method}'}
