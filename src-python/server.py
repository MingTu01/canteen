"""
本地 HTTP 服务器。

双重功能:
1. serve Vue 前端的 dist 目录(静态文件)
2. 处理 /__api__/xxx 端点(前端 → Python 的 API 调用)

绑定到 127.0.0.1 的固定端口(15118),保证 origin 稳定。
"""
import http.server
import socketserver
import os
import threading
import json
import urllib.parse


def find_web_dist():
    """查找 Vue 前端 dist 目录。

    优先级:
    1. PyInstaller 临时目录(web 目录打包进 EXE)
    2. EXE 同目录的 web 目录
    3. 开发模式:terminal/dist
    """
    from config import get_meipass, get_exe_dir

    candidates = [
        os.path.join(get_meipass(), 'web'),
        os.path.join(get_exe_dir(), 'web'),
        os.path.join(get_exe_dir(), '..', 'terminal', 'dist'),
    ]

    for path in candidates:
        abs_path = os.path.abspath(path)
        if os.path.isdir(abs_path) and os.path.exists(os.path.join(abs_path, 'index.html')):
            return abs_path
    return None


class ApiAwareHandler(http.server.SimpleHTTPRequestHandler):
    """处理静态文件 + API 请求的 HTTP handler。"""

    # 由 start_server 注入
    web_dir = '.'
    bridge = None

    # 只读端点(允许 GET)
    READ_ONLY_METHODS = frozenset({'server_url', 'config', 'token_load'})

    def log_message(self, format, *args):
        pass  # 静默,不打印访问日志

    def _check_origin(self, method):
        """校验请求来源,防止本地 API 被恶意网页 CSRF。

        以请求头 Host 为准构造唯一允许的 origin(http://{host},
        如 http://127.0.0.1:15118),规则:
        1. Origin 存在时必须精确等于 http://{host}
        2. Referer 存在时必须等于 http://{host} 或以 http://{host}/ 开头
        3. 状态变更类方法(不在 READ_ONLY_METHODS,如 set_config/quit/
           restart_card_reader)要求 Origin/Referer 至少提供其一,
           两者都缺失直接拒绝(防跨站裸 POST/表单提交);
           GET 只读方法(server_url/config)两者都缺失时放行,
           但只要提供了就必须精确匹配
        """
        host = self.headers.get('Host', '')
        if not host:
            return False
        allowed_origin = 'http://' + host
        origin = self.headers.get('Origin', '')
        referer = self.headers.get('Referer', '')
        if origin and origin != allowed_origin:
            return False
        if referer and referer != allowed_origin and not referer.startswith(allowed_origin + '/'):
            return False
        if method not in self.READ_ONLY_METHODS and not origin and not referer:
            return False
        return True

    def do_GET(self):
        """处理 GET 请求:只读 API 端点或静态文件。"""
        parsed = urllib.parse.urlparse(self.path)

        # API 端点:GET 仅允许只读方法
        if parsed.path.startswith('/__api__/'):
            method = parsed.path[len('/__api__/'):]
            if method not in self.READ_ONLY_METHODS:
                self._send_json(405, {'ok': False, 'error': 'GET 不支持此端点,请使用 POST'})
                return
            if not self._check_origin(method):
                self._send_json(403, {'ok': False, 'error': '跨站请求被拒绝'})
                return
            self._handle_api(method)
            return

        # 静态文件:Vue SPA 路由回退到 index.html
        self._serve_static(parsed.path)

    def do_POST(self):
        """处理 POST 请求:API 端点(状态变更操作)。"""
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path.startswith('/__api__/'):
            method = parsed.path[len('/__api__/'):]
            if not self._check_origin(method):
                self._send_json(403, {'ok': False, 'error': '跨站请求被拒绝'})
                return
            self._handle_api(method)
        else:
            self._send_json(404, {'ok': False, 'error': 'Not Found'})

    def _handle_api(self, method):
        """调用 bridge 处理 API 请求。"""
        if not self.bridge:
            self._send_json(500, {'ok': False, 'error': 'Bridge 未初始化'})
            return

        # 读取请求体(POST 可能有 body)
        body = None
        content_length = int(self.headers.get('Content-Length', 0))
        if content_length > 0:
            try:
                raw = self.rfile.read(content_length)
                body = json.loads(raw)
            except Exception:
                body = None

        try:
            result = self.bridge.handle_api(method, body)
            self._send_json(200, result)
        except Exception as e:
            self._send_json(500, {'ok': False, 'error': str(e)})

    def _send_json(self, code, data):
        """发送 JSON 响应。"""
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        # 不设置 Access-Control-Allow-Origin,仅同源可访问(防止跨站 CSRF)
        self.end_headers()
        self.wfile.write(body)

    def _serve_static(self, path):
        """serve 静态文件,SPA 路由回退到 index.html。"""
        # 去掉开头的 /
        if path.startswith('/'):
            path = path[1:]

        # URL 解码
        path = urllib.parse.unquote(path)

        # 默认 index.html
        if path == '':
            path = 'index.html'

        file_path = os.path.join(self.web_dir, path)
        file_path = os.path.normpath(file_path)

        # 安全检查:防止目录穿越(使用 commonpath 检查路径边界)
        web_dir_abs = os.path.abspath(self.web_dir)
        try:
            if os.path.commonpath([file_path, web_dir_abs]) != web_dir_abs:
                self.send_error(403)
                return
        except ValueError:
            # Windows 跨盘符时 commonpath 会抛 ValueError
            self.send_error(403)
            return

        # 文件存在 → 直接 serve
        if os.path.isfile(file_path):
            super().do_GET()
            return

        # 文件不存在 → SPA 路由回退到 index.html
        index_path = os.path.join(self.web_dir, 'index.html')
        if os.path.isfile(index_path):
            self._serve_file(index_path)
        else:
            self.send_error(404)

    def _serve_file(self, file_path):
        """直接 serve 指定文件。"""
        try:
            with open(file_path, 'rb') as f:
                content = f.read()
            self.send_response(200)
            self._guess_header(file_path)
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self.send_error(500, str(e))

    def _guess_header(self, file_path):
        """根据扩展名设置 Content-Type。"""
        ext = os.path.splitext(file_path)[1].lower()
        types = {
            '.html': 'text/html; charset=utf-8',
            '.js': 'application/javascript; charset=utf-8',
            '.css': 'text/css; charset=utf-8',
            '.json': 'application/json; charset=utf-8',
            '.png': 'image/png',
            '.jpg': 'image/jpeg',
            '.jpeg': 'image/jpeg',
            '.gif': 'image/gif',
            '.svg': 'image/svg+xml',
            '.ico': 'image/x-icon',
            '.woff': 'font/woff',
            '.woff2': 'font/woff2',
            '.ttf': 'font/ttf',
        }
        self.send_header('Content-Type', types.get(ext, 'application/octet-stream'))

    def translate_path(self, path):
        """重写 SimpleHTTPRequestHandler 的路径转换,使用 web_dir。"""
        if path.startswith('/'):
            path = path[1:]
        return os.path.join(self.web_dir, urllib.parse.unquote(path))


def start_server(directory, bridge, port=0):
    """启动本地 HTTP 服务器。

    Args:
        directory: Vue dist 目录
        bridge: ShellBridge 实例
        port: 端口号,0 表示从候选端口中选首个可用

    Returns:
        (server, url): 服务器实例和访问 URL
    """
    # 创建 handler 类,注入 web_dir 和 bridge
    class Handler(ApiAwareHandler):
        pass
    Handler.web_dir = directory
    Handler.bridge = bridge

    # 使用 ThreadingTCPServer 支持并发请求
    # allow_reuse_address 必须作为类属性在实例化前设置
    class ReusableServer(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    # 固定端口 15118:保证 origin(http://127.0.0.1:15118)绝对稳定,
    # 否则 localStorage/IndexedDB 会因端口变化而丢失(绑定配置、菜品/头像缓存全部失效)。
    # 15118 选址依据:避开 Windows 动态端口保留段(常见 1024-50000 内随机段,
    # Docker Desktop/WSL2/Hyper-V 会动态保留大段端口),选择 49152+ 高位冷门端口;
    # 单实例 Mutex 已保证不会有两个终端进程,15118 不应该被占;
    # 若被占(异常残留),直接报错让用户处理,不换端口(换端口会导致缓存全丢)。
    if port != 0:
        # 调用方显式指定端口,直接用
        candidates = [port]
    else:
        candidates = [15118]

    server = None
    actual_port = None
    last_error = None
    for p in candidates:
        try:
            server = ReusableServer(('127.0.0.1', p), Handler)
            actual_port = p
            break
        except OSError as e:
            last_error = e
            continue

    if server is None:
        raise RuntimeError(
            f'无法绑定本地端口 {candidates}(可能已有终端实例在运行,'
            f'请通过任务管理器结束 canteen-terminal.exe 后重试):{last_error}'
        )

    url = f'http://127.0.0.1:{actual_port}'

    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    print(f'[Server] 本地 HTTP 服务器已启动: {url} -> {directory} (端口:{actual_port})')
    return server, url
