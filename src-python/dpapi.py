# -*- coding: utf-8 -*-
"""Windows DPAPI 数据保护(ctypes 实现,无第三方依赖)。

基于 Crypt32 的 CryptProtectData / CryptUnprotectData,加密结果绑定当前
Windows 用户(同一用户可解密,跨用户/离线拷贝均不可解),用于终端 token
的本地加密存储(%APPDATA%\\CanteenTerminal\\token.bin),避免明文落盘。

非 Windows 平台不可用:protect/unprotect 均返回 None,调用方需自行降级
(前端降级 localStorage,后端 token_load 返回 null)。
"""
import sys

if sys.platform == 'win32':
    import ctypes
    import ctypes.wintypes as wintypes

    # 禁止弹出 DPAPI 交互式弹窗(服务/无人值守场景必须禁止)
    CRYPTPROTECT_UI_FORBIDDEN = 0x01

    class DATA_BLOB(ctypes.Structure):
        """DPAPI 输入/输出用的字节缓冲结构。"""
        _fields_ = [
            ('cbData', wintypes.DWORD),
            ('pbData', ctypes.POINTER(ctypes.c_byte)),
        ]

    def _wrap_blob(data):
        """把 bytes 包装成 DATA_BLOB(返回 blob 与底层缓冲,缓冲需保持引用防 GC)。"""
        buf = ctypes.create_string_buffer(data, max(len(data), 1))
        return DATA_BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_byte))), buf

    def _blob_to_bytes(blob):
        """读取 DATA_BLOB 指向的字节数据。"""
        return ctypes.string_at(blob.pbData, blob.cbData)

    def protect(data):
        """DPAPI 加密(bytes -> bytes),失败返回 None。"""
        if not isinstance(data, (bytes, bytearray)):
            return None
        try:
            blob_in, _buf = _wrap_blob(bytes(data))
            blob_out = DATA_BLOB()
            ok = ctypes.windll.crypt32.CryptProtectData(
                ctypes.byref(blob_in),    # pDataIn
                None,                     # szDataDescr
                None,                     # pOptionalEntropy
                None,                     # pvReserved
                None,                     # pPromptStruct
                CRYPTPROTECT_UI_FORBIDDEN,
                ctypes.byref(blob_out),   # pDataOut
            )
            if not ok:
                return None
            try:
                return _blob_to_bytes(blob_out)
            finally:
                ctypes.windll.kernel32.LocalFree(blob_out.pbData)
        except Exception:
            return None

    def unprotect(data):
        """DPAPI 解密(bytes -> bytes),失败(含跨用户/数据损坏)返回 None。"""
        if not isinstance(data, (bytes, bytearray)) or len(data) == 0:
            return None
        try:
            blob_in, _buf = _wrap_blob(bytes(data))
            blob_out = DATA_BLOB()
            ok = ctypes.windll.crypt32.CryptUnprotectData(
                ctypes.byref(blob_in),    # pDataIn
                None,                     # ppszDataDescr
                None,                     # pOptionalEntropy
                None,                     # pvReserved
                None,                     # pPromptStruct
                CRYPTPROTECT_UI_FORBIDDEN,
                ctypes.byref(blob_out),   # pDataOut
            )
            if not ok:
                return None
            try:
                return _blob_to_bytes(blob_out)
            finally:
                ctypes.windll.kernel32.LocalFree(blob_out.pbData)
        except Exception:
            return None

else:
    # 非 Windows 平台:DPAPI 不可用,返回 None 由调用方降级
    def protect(data):
        """非 Windows 平台不可用,返回 None。"""
        return None

    def unprotect(data):
        """非 Windows 平台不可用,返回 None。"""
        return None
