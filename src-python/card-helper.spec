# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller 打包配置 - 读卡助手(静默后台)

把卡号输入框变成"刷卡自动填卡号"的本地常驻程序:
  * 加载 32 位 OUR_IDR.dll 读卡
  * 把卡号模拟成键盘输入+回车(任意聚焦输入框都能刷卡)
  * 本地 127.0.0.1:8765 状态接口(供 admin-web 显示绿色/红色图标)
  * 开机自启(写入 HKCU Run)

打包命令(必须用 32 位 Python,OUR_IDR.dll 是 32 位):
  C:\Python310-32\python.exe -m PyInstaller card-helper.spec --clean --noconfirm

产物:
  dist/card-helper/card-helper.exe  (静默无窗口,把 DLL 内嵌在目录中)
  整个 dist/card-helper/ 即为绿色版,或由 Inno Setup 打成安装包。
"""
import os

SRC_DIR = os.path.abspath('.')

a = Analysis(
    ['card_helper.py'],
    pathex=[SRC_DIR],
    binaries=[
        # 读卡器 SDK 依赖(OUR_IDR.dll 依赖 IDUSB.DLL)
        (os.path.join(SRC_DIR, 'OUR_IDR.dll'), '.'),
        (os.path.join(SRC_DIR, 'IDUSB.DLL'), '.'),
    ],
    datas=[
        # 托盘图标
        (os.path.join(SRC_DIR, 'icon.png'), '.'),
    ],
    hiddenimports=['winreg', 'pystray', 'pystray._win32', 'PIL', 'PIL.Image'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # 精简体积
        'tkinter',
        'unittest',
        'pydoc',
        'http.cookies',
        'xmlrpc',
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=None,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=None)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='card-helper',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[
        'OUR_IDR.dll',
        'IDUSB.DLL',
    ],
    runtime_tmpdir=None,
    console=False,  # 静默:无控制台窗口
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon='icon.ico',
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[
        'OUR_IDR.dll',
        'IDUSB.DLL',
    ],
    name='card-helper',
)