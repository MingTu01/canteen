"""bridge.py 单元测试。

覆盖:
1. eval_js 调试后门已剥离(返回未知方法错误)
2. 只读端点正常工作
3. set_config 写入前逐项取值校验(URL 格式/数值范围/枚举值)
4. token_save / token_load DPAPI 加密存储(仅 Windows)
"""
import os
import sys
from unittest.mock import MagicMock

import pytest
from bridge import ShellBridge


@pytest.fixture
def bridge():
    """创建 ShellBridge 实例(PyQt5 已被 conftest mock)。"""
    card_reader = MagicMock()
    get_server_url = MagicMock(return_value='http://192.168.1.100:8080')
    return ShellBridge(card_reader, get_server_url)


class TestEvalJsRemoved:
    """M1 修复:eval_js 调试后门已从生产代码剥离。"""

    def test_eval_js_is_unknown_method(self, bridge):
        """eval_js 应被视为未知方法(端点已删除)。"""
        result = bridge.handle_api('eval_js', {'js': 'alert(1)'})
        assert result['ok'] is False
        assert '未知' in result['error']

    def test_eval_js_rejected_even_with_debug_env(self, bridge, monkeypatch):
        """即使设置 CANTEEN_DEBUG=1,eval_js 也不可用。"""
        monkeypatch.setenv('CANTEEN_DEBUG', '1')
        result = bridge.handle_api('eval_js', {'js': 'alert(1)'})
        assert result['ok'] is False


class TestReadOnlyEndpoints:
    """只读端点不应受环境影响。"""

    def test_server_url_always_works(self, bridge):
        """server_url 端点正常工作。"""
        result = bridge.handle_api('server_url')
        assert result['ok'] is True
        assert result['server_url'] == 'http://192.168.1.100:8080'

    def test_unknown_method_returns_error(self, bridge):
        """未知方法应返回错误。"""
        result = bridge.handle_api('nonexistent')
        assert result['ok'] is False
        assert '未知' in result['error']


class TestSetConfigValidation:
    """X86 中3 残留修复:set_config 写入前逐项取值校验。"""

    @pytest.fixture(autouse=True)
    def mock_write(self, monkeypatch):
        """mock write_config,避免测试写入真实 config.json。"""
        self.written = None
        import bridge as bridge_mod

        def fake_write_config(updates):
            self.written = updates
            return True

        monkeypatch.setattr(bridge_mod, 'write_config', fake_write_config)

    def test_valid_server_url(self, bridge):
        result = bridge.handle_api('set_config', {'server_url': 'https://canteen.example.com'})
        assert result['ok'] is True
        assert self.written == {'server_url': 'https://canteen.example.com'}

    def test_invalid_server_url_rejected(self, bridge):
        """server_url 非 http(s) 开头应被拒绝且不写入。"""
        result = bridge.handle_api('set_config', {'server_url': 'file://C:/evil'})
        assert result['ok'] is False
        assert 'server_url' in result['error']
        assert self.written is None

    def test_empty_server_url_allowed(self, bridge):
        """server_url 允许留空(空串表示手动输入)。"""
        result = bridge.handle_api('set_config', {'server_url': ''})
        assert result['ok'] is True

    def test_update_check_url_must_be_http(self, bridge):
        result = bridge.handle_api('set_config', {'update_check_url': 'ftp://evil/x'})
        assert result['ok'] is False
        assert self.written is None

    def test_card_interval_out_of_range(self, bridge):
        """card_interval 超出 0~5000 应被拒绝。"""
        result = bridge.handle_api('set_config', {'card_interval': 99999})
        assert result['ok'] is False
        assert self.written is None

    def test_card_interval_negative_rejected(self, bridge):
        result = bridge.handle_api('set_config', {'card_interval': -1})
        assert result['ok'] is False
        assert self.written is None

    def test_card_interval_valid(self, bridge):
        """card_interval 合法小数(默认 2.0)应通过。"""
        result = bridge.handle_api('set_config', {'card_interval': 2.0})
        assert result['ok'] is True
        assert self.written == {'card_interval': 2.0}

    def test_idle_timeout_valid(self, bridge):
        result = bridge.handle_api('set_config', {'idle_timeout': 300})
        assert result['ok'] is True

    def test_window_mode_enum(self, bridge):
        """window_mode 只允许 fullscreen/windowed。"""
        assert bridge.handle_api('set_config', {'window_mode': 'windowed'})['ok'] is True
        self.written = None
        result = bridge.handle_api('set_config', {'window_mode': 'kiosk'})
        assert result['ok'] is False
        assert self.written is None


@pytest.mark.skipif(sys.platform != 'win32', reason='DPAPI 仅 Windows 可用')
class TestTokenStorage:
    """H1 残留修复:token DPAPI 加密存储(token_save/token_load)。"""

    @pytest.fixture(autouse=True)
    def mock_appdata(self, monkeypatch, tmp_path):
        """把 token.bin 指向临时目录,避免污染真实用户目录。"""
        import bridge as bridge_mod
        monkeypatch.setattr(bridge_mod, 'get_appdata_dir', lambda: str(tmp_path))
        self.token_path = os.path.join(str(tmp_path), 'token.bin')
        return tmp_path

    def test_save_and_load_roundtrip(self, bridge):
        token = 'terminal-jwt-token-abc123'
        result = bridge.handle_api('token_save', {'token': token})
        assert result['ok'] is True
        assert os.path.exists(self.token_path)
        # 落盘内容必须是密文(不含明文 token)
        with open(self.token_path, 'rb') as f:
            raw = f.read()
        assert token.encode('utf-8') not in raw
        # 读回应得到明文 token
        result = bridge.handle_api('token_load')
        assert result['ok'] is True
        assert result['token'] == token

    def test_load_missing_file_returns_null(self, bridge):
        """文件不存在时返回 token=None(不抛错)。"""
        result = bridge.handle_api('token_load')
        assert result['ok'] is True
        assert result['token'] is None

    def test_save_empty_clears(self, bridge):
        """空串保存 = 清除存储。"""
        bridge.handle_api('token_save', {'token': 'abc'})
        assert os.path.exists(self.token_path)
        result = bridge.handle_api('token_save', {'token': ''})
        assert result['ok'] is True
        assert not os.path.exists(self.token_path)
        assert bridge.handle_api('token_load')['token'] is None

    def test_save_corrupted_file_loads_null(self, bridge):
        """密文损坏时解密失败,返回 token=None(不抛 500)。"""
        with open(self.token_path, 'wb') as f:
            f.write(b'not-a-valid-dpapi-blob')
        result = bridge.handle_api('token_load')
        assert result['ok'] is True
        assert result['token'] is None

    def test_save_requires_token_field(self, bridge):
        result = bridge.handle_api('token_save', {'nope': 1})
        assert result['ok'] is False
