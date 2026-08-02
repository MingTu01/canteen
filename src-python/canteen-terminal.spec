# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller 打包配置 - 企业智慧食堂终端(绿色目录版)

打包命令:
  C:\Python310-32\python.exe -m PyInstaller canteen-terminal.spec --clean --noconfirm

产物:
  dist/canteen-terminal/canteen-terminal.exe  (绿色目录,内含所有依赖)
  整个 canteen-terminal/ 文件夹即为可部署的绿色版

说明:
  采用 --onedir 模式(目录版)而非 --onefile(单文件)。
  原因:QtWebEngine 在 onefile 模式下,QtWebEngineProcess 子进程
  会与主进程争抢持久化目录的 QuotaManager SQLite 锁,导致
  "database is locked (errno 33)" 错误,IndexedDB 无法写入
  (菜品图片缓存失效)。onedir 模式下所有文件在 EXE 同目录,
  QtWebEngineProcess 能正确访问持久化目录,无锁冲突。

  正式部署用 Inno Setup 将此目录打包为安装包(installer.iss)。
  安装包会自动安装 CH375 读卡器驱动 + VC++ 运行时 + 终端程序。
"""
import os
import PyQt5

block_cipher = None

# 源代码目录
SRC_DIR = os.path.abspath('.')

# PyQt5 Qt5 目录(用于定位 QtWebEngine 资源)
PYQT5_DIR = os.path.dirname(PyQt5.__file__)
QT5_DIR = os.path.join(PYQT5_DIR, 'Qt5')

a = Analysis(
    ['main.py'],
    pathex=[SRC_DIR],
    binaries=[
        # (源路径, 目标目录)
        # 把 OUR_IDR.dll 和 IDUSB.DLL 打包进目录(读卡器 SDK 依赖)
        (os.path.join(SRC_DIR, 'OUR_IDR.dll'), '.'),
        (os.path.join(SRC_DIR, 'IDUSB.DLL'), '.'),
    ],
    datas=[
        # Vue 前端 dist 目录(打包为 web 目录)
        # 开发时指向 ../terminal/dist
        # 打包前请确保已构建前端:cd terminal && npm run build
        ('../terminal/dist', 'web'),
        # QtWebEngine 资源文件(icudtl.dat + .pak 文件)
        # PyInstaller 的 PyQt5 hook 在 onedir 模式下不会自动收集这些文件,
        # 导致 QtWebEngine 启动时报 "resources not found" 和 "Couldn't mmap icu data file"
        (os.path.join(QT5_DIR, 'resources', '.'), os.path.join('PyQt5', 'Qt5', 'resources')),
        # QtWebEngine 翻译文件(qtwebengine_locales 目录)
        (os.path.join(QT5_DIR, 'translations', 'qtwebengine_locales', '.'),
         os.path.join('PyQt5', 'Qt5', 'translations', 'qtwebengine_locales')),
        # CH375 读卡器驱动文件(安装时由 Inno Setup 调用 pnputil 安装)
        # 将 drivers/ 目录原样打包到 _internal/drivers/
        # 文件清单:CH375WDM.INF / CH375WDM.CAT / CH375W64.SYS / CH375DLL.DLL / CH375DLL64.DLL
        ('drivers', 'drivers'),
    ],
    hiddenimports=[
        # PyQt5 模块
        'PyQt5.QtCore',
        'PyQt5.QtGui',
        'PyQt5.QtWidgets',
        'PyQt5.QtWebEngineWidgets',
        'PyQt5.QtNetwork',
        # 本地模块
        'config',
        'server',
        'bridge',
        'card_reader',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # 排除不需要的大模块,减小体积
        # 注意:email/xml 不能排除,http.server 依赖它们
        'tkinter',
        'unittest',
        'pydoc',
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='canteen-terminal',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[
        # 这些文件不压缩,避免启动慢或损坏
        'QtWebEngineProcess.exe',
        'OUR_IDR.dll',
        'IDUSB.DLL',
    ],
    runtime_tmpdir=None,
    console=False,  # 正式部署:不显示控制台窗口(日志仍写入 %LOCALAPPDATA%\CanteenTerminal\terminal.log)
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon='icon.ico',  # 程序图标(食堂主题)
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[
        'QtWebEngineProcess.exe',
        'OUR_IDR.dll',
        'IDUSB.DLL',
    ],
    name='canteen-terminal',
)
