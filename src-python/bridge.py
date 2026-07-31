"""
Shell 桥接:处理前端 → Python 的 API 调用。

采用 HTTP API 方式(前端 fetch /__api__/xxx),不需要 QWebChannel。
跨线程操作 Qt 窗口通过 pyqtSignal(线程安全)。

API 端点:
    GET  /__api__/server_url          获取预设服务器地址
    GET  /__api__/config              获取完整配置(window_mode/card_interval/idle_timeout/server_url)
    POST /__api__/set_config          更新配置(部分字段)
    POST /__api__/switch_to_config    切换到配置模式(取消全屏)
    POST /__api__/switch_to_fullscreen 切换回全屏模式
    POST /__api__/quit                退出应用
    POST /__api__/restart_card_reader 重启读卡器
    POST /__api__/eval_js             临时诊断(执行前端 JS,生产环境应禁用)
"""
import json
import os
from PyQt5.QtCore import QObject, pyqtSignal

from config import read_full_config, write_config


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
    eval_js_requested = pyqtSignal(str)

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
            # 更新部分配置字段,body = { window_mode?, card_interval?, idle_timeout?, server_url? }
            if not isinstance(body, dict):
                return {'ok': False, 'error': '请求体必须是 JSON 对象'}
            # 字段白名单 + 类型校验
            allowed = {
                'window_mode': str,
                'card_interval': (int, float),
                'idle_timeout': (int, float),
                'server_url': str,
            }
            updates = {}
            for key, expected_type in allowed.items():
                if key in body:
                    val = body[key]
                    if not isinstance(val, expected_type):
                        return {'ok': False, 'error': f'{key} 类型错误'}
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
            self.quit_requested.emit()
            return {'ok': True}

        elif method == 'restart_card_reader':
            running = self.card_reader.restart()
            return {'ok': True, 'running': running}

        elif method == 'eval_js':
            # 临时诊断端点:在前端执行 JS 并返回结果
            # 生产环境应通过不设置 CANTEEN_DEBUG 环境变量来禁用此端点
            if not os.environ.get('CANTEEN_DEBUG'):
                return {'ok': False, 'error': '诊断端点已禁用(设置 CANTEEN_DEBUG=1 启用)'}
            if isinstance(body, dict) and body.get('js'):
                self.eval_js_requested.emit(body['js'])
                return {'ok': True}
            return {'ok': False, 'error': '需要 js 字段'}

        return {'ok': False, 'error': f'未知方法: {method}'}
