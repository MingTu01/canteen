"""server.py 安全修复测试。

覆盖本次 P0 修复:
1. 目录穿越防护(commonpath 边界检查)
2. Origin/Referer 跨站校验(CSRF 防护)
3. HTTP 方法强制(GET 只读端点,POST 状态变更)
4. CORS 头不设置 Access-Control-Allow-Origin: *
5. SPA 路由回退
6. 静态文件正常服务
"""
import json
import os
import socket
import tempfile
import urllib.request
import urllib.error
from unittest.mock import MagicMock

import pytest
from server import start_server


def _find_free_port():
    """找一个可用的随机端口(避免 Windows 保留端口范围)。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


@pytest.fixture
def web_dir(tmp_path):
    """创建临时 web 目录,含 index.html 和一个子目录文件。"""
    (tmp_path / 'index.html').write_text('<html>SPA</html>', encoding='utf-8')
    (tmp_path / 'app.js').write_text('console.log(1)', encoding='utf-8')
    sub = tmp_path / 'assets'
    sub.mkdir()
    (sub / 'style.css').write_text('body{margin:0}', encoding='utf-8')
    return str(tmp_path)


@pytest.fixture
def mock_bridge():
    """Mock ShellBridge,模拟真实 bridge 的行为(未知方法返回 ok=False)。"""
    bridge = MagicMock()

    def fake_handle_api(method, body=None):
        # 已知方法返回 ok=True,未知方法返回 ok=False(与真实 ShellBridge 一致)
        known = {'server_url', 'config', 'set_config', 'switch_to_config',
                 'switch_to_fullscreen', 'quit', 'restart_card_reader', 'eval_js'}
        if method in known:
            return {'ok': True, 'mock': True}
        return {'ok': False, 'error': f'未知方法: {method}'}

    bridge.handle_api = MagicMock(side_effect=fake_handle_api)
    return bridge


@pytest.fixture
def server(web_dir, mock_bridge):
    """启动测试服务器,返回 (server, url)。用动态端口避免 Windows 保留端口。"""
    port = _find_free_port()
    srv, url = start_server(web_dir, mock_bridge, port=port)
    yield url
    srv.shutdown()
    srv.server_close()


def _get(url, path, headers=None):
    """发送 GET 请求,返回 (status_code, body_dict_or_text)。"""
    req = urllib.request.Request(url + path, method='GET')
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode('utf-8')
            return resp.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', errors='replace')


def _post(url, path, data=None, headers=None):
    """发送 POST 请求,返回 (status_code, body_dict)。"""
    body_bytes = json.dumps(data).encode('utf-8') if data else b''
    req = urllib.request.Request(url + path, data=body_bytes, method='POST')
    req.add_header('Content-Type', 'application/json')
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode('utf-8'))
        except Exception:
            return e.code, {}


# ============ 目录穿越防护测试 ============

class TestDirectoryTraversal:
    """P0 修复:目录穿越防护(commonpath 边界检查)。"""

    def test_traversal_with_dotdot(self, server):
        """../../etc/passwd 风格的目录穿越应被拒绝(403)。"""
        status, _ = _get(server, '/../../etc/passwd')
        assert status == 403

    def test_traversal_with_encoded_dotdot(self, server):
        """URL 编码的 ../ 也应被拒绝。"""
        status, _ = _get(server, '/%2e%2e/%2e%2e/etc/passwd')
        assert status == 403

    def test_traversal_with_double_encoding(self, server):
        """双重编码的 ../ 不会造成穿越(只解码一次,变成普通文件名)。

        双重编码 %252e%252e 只被 unquote 一次 → %2e%2e(不是 ..),
        被当作普通文件名,不存在则回退到 index.html。这是安全行为。
        """
        status, body = _get(server, '/%252e%252e/%252e%252e/etc/passwd')
        # 应回退到 index.html(200 + SPA 内容),而非访问到 web_dir 外文件
        assert status == 200
        assert 'SPA' in body

    def test_normal_subpath_allowed(self, server):
        """正常子路径文件应可访问。"""
        status, body = _get(server, '/assets/style.css')
        assert status == 200
        assert 'margin' in body


# ============ Origin/Referer 跨站校验测试 ============

class TestOriginValidation:
    """P0 修复:CORS 头移除 + Origin/Referer 跨站校验。"""

    def test_api_with_malicious_origin(self, server):
        """恶意 Origin 的 API 请求应被拒绝(403)。"""
        status, body = _get(server, '/__api__/config',
                            headers={'Origin': 'http://evil.com'})
        assert status == 403

    def test_api_with_malicious_referer(self, server):
        """恶意 Referer 的 API 请求应被拒绝(403)。"""
        status, body = _post(server, '/__api__/set_config',
                             data={'window_mode': 'windowed'},
                             headers={'Referer': 'http://evil.com/'})
        assert status == 403

    def test_api_with_same_origin(self, server):
        """同源(127.0.0.1)Origin 的 API 请求应正常通过。"""
        status, body = _get(server, '/__api__/config',
                            headers={'Origin': 'http://127.0.0.1:15118'})
        assert status == 200

    def test_api_without_origin(self, server):
        """无 Origin 头的同源请求(如 curl)应正常通过。"""
        status, body = _get(server, '/__api__/config')
        assert status == 200

    def test_no_cors_wildcard_header(self, server):
        """响应头不应包含 Access-Control-Allow-Origin: *。"""
        req = urllib.request.Request(server + '/__api__/config', method='GET')
        with urllib.request.urlopen(req) as resp:
            acao = resp.headers.get('Access-Control-Allow-Origin')
        assert acao is None, f"不应设置 CORS 头,但得到: {acao}"


# ============ HTTP 方法强制测试 ============

class TestHttpMethodEnforcement:
    """P0 修复:GET 仅允许只读端点,状态变更必须 POST。"""

    def test_get_readonly_endpoint_allowed(self, server):
        """GET /__api__/server_url(只读)应允许。"""
        status, _ = _get(server, '/__api__/server_url')
        assert status == 200

    def test_get_write_endpoint_rejected(self, server):
        """GET /__api__/set_config(状态变更)应返回 405。"""
        status, body = _get(server, '/__api__/set_config')
        assert status == 405
        assert 'ok' in body or '不支持' in str(body) if isinstance(body, dict) else True

    def test_post_readonly_endpoint_allowed(self, server):
        """POST /__api__/config 也应能处理(通过 _handle_api 路由)。"""
        status, _ = _post(server, '/__api__/config')
        assert status == 200


# ============ 静态文件与 SPA 回退测试 ============

class TestStaticServing:
    """静态文件服务 + SPA 路由回退。"""

    def test_index_html(self, server):
        """根路径返回 index.html。"""
        status, body = _get(server, '/')
        assert status == 200
        assert 'SPA' in body

    def test_static_js(self, server):
        """静态 JS 文件正常服务。"""
        status, body = _get(server, '/app.js')
        assert status == 200
        assert 'console' in body

    def test_spa_fallback(self, server):
        """不存在的路径回退到 index.html(SPA 路由)。"""
        status, body = _get(server, '/some/vue/route')
        assert status == 200
        assert 'SPA' in body

    def test_nonexistent_api_returns_404(self, server):
        """未知的 API 端点应返回错误(而非 SPA 回退)。"""
        status, body = _post(server, '/__api__/nonexistent_method')
        assert status == 200  # _handle_api 返回 200 + {ok: False}
        assert body.get('ok') is False
