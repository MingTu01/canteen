"""Pytest 配置:mock PyQt5 模块 + 注入 src-python 到 sys.path。

PyQt5 在测试环境未安装,通过 sys.modules 注入 mock,
让 bridge.py 的 `from PyQt5.QtCore import QObject, pyqtSignal` 能正常导入。
"""
import sys
import os
from unittest.mock import MagicMock

# 1. 将 src-python 目录加入 sys.path,使 config/bridge/server 模块可导入
SRC_PYTHON_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SRC_PYTHON_DIR not in sys.path:
    sys.path.insert(0, SRC_PYTHON_DIR)

# 2. mock PyQt5(bridge.py 依赖 QObject / pyqtSignal)
if 'PyQt5.QtCore' not in sys.modules:
    class _MockQObject:
        """模拟 QObject 基类。"""
        def __init__(self, *args, **kwargs):
            pass

    def _mock_pyqtSignal(*args, **kwargs):
        """模拟 pyqtSignal,返回可调用的 mock(支持 .emit)。"""
        sig = MagicMock()
        return sig

    qt_core = MagicMock()
    qt_core.QObject = _MockQObject
    qt_core.pyqtSignal = _mock_pyqtSignal
    sys.modules['PyQt5'] = MagicMock()
    sys.modules['PyQt5.QtCore'] = qt_core
