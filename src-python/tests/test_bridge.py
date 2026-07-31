"""bridge.py eval_js 环境变量门控测试。

覆盖本次 P0 修复:
- eval_js 端点在生产环境(无 CANTEEN_DEBUG)应被禁用
- eval_js 端点在调试环境(CANTEEN_DEBUG=1)且有 js 字段时正常工作
- eval_js 端点在调试环境但无 js 字段时返回错误
"""
import os
from unittest.mock import MagicMock

import pytest
from bridge import ShellBridge


@pytest.fixture
def bridge():
    """创建 ShellBridge 实例(PyQt5 已被 conftest mock)。"""
    card_reader = MagicMock()
    get_server_url = MagicMock(return_value='http://192.168.1.100:8080')
    return ShellBridge(card_reader, get_server_url)


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    """每个测试前清除 CANTEEN_DEBUG 环境变量。"""
    monkeypatch.delenv('CANTEEN_DEBUG', raising=False)


class TestEvalJsGating:
    """P0 修复:eval_js 端点环境变量门控。"""

    def test_eval_js_disabled_without_env(self, bridge):
        """无 CANTEEN_DEBUG 时,eval_js 应返回禁用错误。"""
        result = bridge.handle_api('eval_js', {'js': 'alert(1)'})
        assert result['ok'] is False
        assert '禁用' in result['error']

    def test_eval_js_disabled_with_empty_env(self, bridge, monkeypatch):
        """CANTEEN_DEBUG 为空字符串时,eval_js 应被禁用。"""
        monkeypatch.setenv('CANTEEN_DEBUG', '')
        result = bridge.handle_api('eval_js', {'js': 'alert(1)'})
        assert result['ok'] is False

    def test_eval_js_enabled_with_debug_flag(self, bridge, monkeypatch):
        """CANTEEN_DEBUG=1 且有 js 字段时,eval_js 应正常工作。"""
        monkeypatch.setenv('CANTEEN_DEBUG', '1')
        result = bridge.handle_api('eval_js', {'js': 'alert(1)'})
        assert result['ok'] is True

    def test_eval_js_missing_js_field(self, bridge, monkeypatch):
        """CANTEEN_DEBUG=1 但无 js 字段时,应返回错误。"""
        monkeypatch.setenv('CANTEEN_DEBUG', '1')
        result = bridge.handle_api('eval_js', {})
        assert result['ok'] is False
        assert 'js' in result['error']

    def test_eval_js_with_non_dict_body(self, bridge, monkeypatch):
        """CANTEEN_DEBUG=1 但 body 非 dict 时,应返回错误。"""
        monkeypatch.setenv('CANTEEN_DEBUG', '1')
        result = bridge.handle_api('eval_js', 'not a dict')
        assert result['ok'] is False


class TestReadOnlyEndpoints:
    """只读端点不应受 CANTEEN_DEBUG 影响。"""

    def test_server_url_always_works(self, bridge):
        """server_url 端点无需 CANTEEN_DEBUG。"""
        result = bridge.handle_api('server_url')
        assert result['ok'] is True
        assert result['server_url'] == 'http://192.168.1.100:8080'

    def test_unknown_method_returns_error(self, bridge):
        """未知方法应返回错误。"""
        result = bridge.handle_api('nonexistent')
        assert result['ok'] is False
        assert '未知' in result['error']
