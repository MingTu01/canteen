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
    POST /__api__/net_diagnose        网络诊断(DNS/代理/证书/连通性逐步探测,定位"无法连接服务器")
    POST /__api__/osk                 屏幕键盘控制({action: show/hide},触屏设备点击输入框自动唤起)
"""
import json
import os
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

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
                'chromium_flags_extra': str,
                'gpu_mode': str,
                'osk_mode': str,
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

        elif method == 'net_diagnose':
            # 网络诊断:逐步探测 DNS → 直连(禁代理) → 证书校验,
            # 定位"浏览器能访问但终端连不上"类问题(后端无日志 = 请求根本没到后端)。
            # body = {url: "https://canteen.xxx.com"}
            if not isinstance(body, dict):
                return {'ok': False, 'error': '请求体必须是 JSON 对象'}
            url = str(body.get('url', '')).strip()
            return self._net_diagnose(url)

        elif method == 'device_status':
            # 返回读卡器设备状态(供前端设置页设备检查展示)
            reader_status = self.card_reader.status_info()
            return {'ok': True, 'card_reader': reader_status}

        elif method == 'osk':
            # 屏幕键盘控制(触屏设备点击输入框时前端 v-osk 指令调用)
            # body = {action: 'show' | 'hide'}
            if not isinstance(body, dict):
                return {'ok': False, 'error': '请求体必须是 JSON 对象'}
            action = str(body.get('action', ''))
            if action == 'show':
                if self._osk_running():
                    return {'ok': True, 'already_running': True}
                try:
                    # 经 cmd start 拉起 osk.exe:osk.exe 拒绝被控制台进程直接
                    # 作为子进程托管(直接 Popen 会闪退),start 让 Explorer 接管
                    subprocess.Popen(
                        ['cmd', '/c', 'start', '', 'osk.exe'],
                        close_fds=True,
                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
                    )
                    return {'ok': True}
                except Exception as e:
                    return {'ok': False, 'error': f'启动屏幕键盘失败: {e}'}
            elif action == 'hide':
                try:
                    subprocess.run(
                        ['taskkill', '/IM', 'osk.exe', '/F'],
                        capture_output=True,
                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
                    )
                    return {'ok': True}
                except Exception as e:
                    return {'ok': False, 'error': f'关闭屏幕键盘失败: {e}'}
            return {'ok': False, 'error': 'action 必须是 show 或 hide'}

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
                    # 空串 = 清除 shell 侧存储(解绑)
                    if os.path.exists(token_path):
                        os.remove(token_path)
                    # 解绑后回到绑定/配置页,同步切回窗口模式
                    # (与"首次启动未绑定用窗口模式"的行为保持一致)
                    self.switch_to_config_requested.emit()
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

    # ===== 屏幕键盘 =====

    @staticmethod
    def _osk_running():
        """osk.exe 是否已在运行(重复启动会闪退,先查再拉)。"""
        try:
            out = subprocess.run(
                ['tasklist', '/FI', 'IMAGENAME eq osk.exe', '/NH'],
                capture_output=True, text=True,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
                timeout=3,
            )
            return 'osk.exe' in (out.stdout or '').lower()
        except Exception:
            return False

    # ===== 网络诊断 =====

    # 单次探测超时(秒)
    PROBE_TIMEOUT = 5.0

    @staticmethod
    def _net_probe(url, verify_ssl=True, use_system_proxy=True, timeout=5.0):
        """单次 HTTP GET 探测。

        ok=True 表示请求到达了服务器(TCP/TLS/HTTP 任一层有响应,
        含 4xx/5xx 响应——收到 HTTP 错误码本身就证明网络层是通的);
        ok=False 时 error_type/error 说明具体失败环节。
        """
        ctx = ssl.create_default_context()
        if not verify_ssl:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        handlers = []
        if not use_system_proxy:
            # 禁用系统代理直连(排查本机残留代理干扰)
            handlers.append(urllib.request.ProxyHandler({}))
        if url.startswith('https://'):
            handlers.append(urllib.request.HTTPSHandler(context=ctx))
        opener = urllib.request.build_opener(*handlers) if handlers else urllib.request.build_opener()
        t0 = time.time()
        try:
            req = urllib.request.Request(
                url, headers={'User-Agent': 'CanteenTerminal-Diag/1.0'})
            with opener.open(req, timeout=timeout) as resp:
                return {'ok': True, 'status': resp.status,
                        'elapsed_ms': int((time.time() - t0) * 1000)}
        except urllib.error.HTTPError as e:
            # 收到 HTTP 错误响应 = 网络与 TLS 层均已打通
            return {'ok': True, 'status': e.code,
                    'elapsed_ms': int((time.time() - t0) * 1000)}
        except Exception as e:
            # URLError 的 reason 里才是真正的失败原因(DNS/SSL/超时/拒绝)
            reason = getattr(e, 'reason', None) or e
            return {'ok': False, 'error_type': type(reason).__name__,
                    'error': str(reason),
                    'elapsed_ms': int((time.time() - t0) * 1000)}

    def _net_diagnose(self, url):
        """网络诊断主逻辑(前端绑定失败时自动调用)。

        探测步骤(逐步收窄):
          1. DNS 解析(socket.getaddrinfo,失败即结论:域名无法解析)
          2. 标准探测(系统代理 + 严格证书校验,与终端 WebView 行为最接近)
          3. 直连探测(禁用系统代理,排查本机残留代理干扰)
          4. 宽松探测(忽略证书校验,仅 https;成功即结论:证书问题)

        返回 steps 明细 + diagnosis 结论(中文 message 直接展示给运维)。
        """
        if not url or not url.startswith(('http://', 'https://')):
            return {'ok': False, 'error': '地址必须以 http:// 或 https:// 开头'}
        parsed = urllib.parse.urlparse(url)
        host = parsed.hostname or ''
        is_https = parsed.scheme == 'https'
        steps = {}

        # 1. DNS 解析
        dns = None
        if host:
            try:
                infos = socket.getaddrinfo(host, None)
                dns = {'ok': True, 'ip': infos[0][4][0]}
            except Exception as e:
                dns = {'ok': False, 'error': str(e)}
        steps['dns'] = dns
        if dns and not dns.get('ok'):
            return {'ok': True, 'url': url, 'steps': steps, 'diagnosis': {
                'type': 'dns_fail',
                'message': (
                    f'域名解析失败:本机无法解析 {host}(DNS 未配置/hosts 错误/网络未连接)。'
                    f'原始错误:{dns.get("error", "?")}'
                ),
            }}

        # 2. 标准探测(系统代理 + 严格证书)
        strict = self._net_probe(url, verify_ssl=True, use_system_proxy=True,
                                 timeout=self.PROBE_TIMEOUT)
        steps['strict'] = strict
        print(f'[Diag] net_diagnose {url} strict: {strict}')
        if strict['ok']:
            return {'ok': True, 'url': url, 'steps': steps, 'diagnosis': {
                'type': 'net_ok',
                'message': (
                    f'系统网络层连接正常(服务器有响应,状态码 {strict.get("status")}),'
                    '但终端内置浏览器连接失败——常见原因:①证书链不完整(浏览器会自动补齐中间证书,'
                    'WebView 不会),请在服务器补全证书链;②本机系统代理设置。'
                    '可将日志 %LOCALAPPDATA%\\CanteenTerminal\\terminal.log 发给开发排查'
                ),
            }}

        # 3. 直连探测(禁用系统代理)
        direct = self._net_probe(url, verify_ssl=True, use_system_proxy=False,
                                 timeout=self.PROBE_TIMEOUT)
        steps['direct'] = direct
        print(f'[Diag] net_diagnose {url} direct: {direct}')
        if direct['ok']:
            return {'ok': True, 'url': url, 'steps': steps, 'diagnosis': {
                'type': 'proxy_issue',
                'message': (
                    '本机系统代理干扰:走代理连接失败,直连成功。'
                    '请在 Windows 设置 → 网络和 Internet → 代理 中关闭代理,'
                    '或在代理服务器上放行该地址'
                ),
            }}

        # 4. 宽松探测(忽略证书,仅 https)
        if is_https:
            relaxed = self._net_probe(url, verify_ssl=False, use_system_proxy=True,
                                      timeout=self.PROBE_TIMEOUT)
            steps['relaxed'] = relaxed
            print(f'[Diag] net_diagnose {url} relaxed: {relaxed}')
            if relaxed['ok']:
                return {'ok': True, 'url': url, 'steps': steps, 'diagnosis': {
                    'type': 'cert_fail',
                    'message': (
                    'HTTPS 证书校验失败:证书为自签名/证书链不完整/域名与证书不匹配。'
                    '浏览器可能因曾手动信任而能访问,但终端 WebView 会严格拒绝。'
                    '解决方案:①在反向代理上部署完整证书链(推荐);'
                    '②临时改填 http:// 地址(内网部署);'
                    '③改用非标准 https 端口(如 :8443,需服务器同步开放)'
                ),
                }}

            # TLS 握手被服务端拒绝(忽略证书也失败,说明卡在握手而非校验):
            # 最典型场景是云服务器(阿里云/腾讯云大陆节点)对未备案域名 443 端口的阻断,
            # 浏览器走代理能访问、直连的终端不行,后端日志完全无记录。
            err_type = str(strict.get('error_type') or '')
            if 'SSL' in err_type or 'CERTIFICATE' in err_type:
                return {'ok': True, 'url': url, 'steps': steps, 'diagnosis': {
                    'type': 'tls_rejected',
                    'message': (
                        'TLS 握手被服务器拒绝:https 连接在证书交换阶段就被远端切断'
                        '(与证书内容无关,忽略证书校验也一样失败)。'
                        '最常见原因:云服务器(阿里云/腾讯云等大陆节点)对未备案域名的 '
                        '443 端口阻断。解决方案:①域名完成 ICP 备案(正解);'
                        '②临时改填 http:// 地址(80 端口通常不受影响);'
                        '③改用非标准 https 端口(如 :8443,需服务器同步开放)。'
                        f'原始错误:{strict.get("error", "?")}'
                    ),
                }}

        # 全部失败:网络层不通
        err_desc = strict.get('error') or '?'
        return {'ok': True, 'url': url, 'steps': steps, 'diagnosis': {
            'type': 'unreachable',
            'message': (
                f'无法建立连接:请求未到达服务器(后端日志无记录属正常)。'
                f'请检查地址拼写、端口是否开放(http 默认 80,https 默认 443)、'
                f'防火墙/安全策略是否拦截。原始错误:{err_desc}'
            ),
        }}
