# -*- coding: utf-8 -*-
"""对比 3.8.10 打包产物与 3.8.9 官方文件的差异,验证 Win7 兼容性理论。

背景:Win7 上报「DLL load failed while importing _ctypes: 参数错误」。
_ctypes.pyd 依赖 libffi-7.dll;怀疑 3.8.10 的 libffi-7.dll 构建工具链过新,
Win7 加载器拒绝(PE 特性不识别)。
"""
import hashlib
import os
import struct

DIST = r'd:\文档\enterprise-canteen\enterprise-canteen\src-python\dist\canteen-terminal'
PY389_DLLS = r'C:\Python389-32\DLLs'


def info(path, tag):
    data = open(path, 'rb').read()
    md5 = hashlib.md5(data).hexdigest()
    # 解析 PE 头:提取节表名与 MajorOperatingSystemVersion
    e_lfanew = struct.unpack_from('<I', data, 0x3C)[0]
    machine, nsec = struct.unpack_from('<HH', data, e_lfanew + 4)[0:2]
    opt = e_lfanew + 24
    magic = struct.unpack_from('<H', data, opt)[0]
    osver = struct.unpack_from('<HH', data, opt + 40)
    secs = []
    sec_off = opt + (240 if magic == 0x10B else 224)
    for i in range(nsec):
        name = data[sec_off + i * 40: sec_off + i * 40 + 8].rstrip(b'\x00').decode(errors='replace')
        secs.append(name)
    print(f'{tag}: {os.path.getsize(path):>7} B  md5={md5[:12]}  '
          f'machine={"i386" if machine == 0x14C else hex(machine)}  '
          f'OSver={osver[0]}.{osver[1]}  sections={secs}')
    return md5


for name in ('libffi-7.dll', '_ctypes.pyd', 'python38.dll'):
    print(f'--- {name} ---')
    d = os.path.join(DIST, name)
    s = os.path.join(PY389_DLLS, name)
    info(d, '3.8.10产物')
    info(s, '3.8.9官方')
    print()
