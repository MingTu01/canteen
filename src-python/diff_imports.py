# -*- coding: utf-8 -*-
"""对比 3.8.10 与 3.8.9 的 _ctypes.pyd / python38.dll 导入表差异。

Win7 报「DLL load failed while importing _ctypes: 参数错误」(ERROR_INVALID_PARAMETER)。
典型原因:DLL 导入了 Win7 apiset 架构不认识的 api-ms-win-* API 集,
loader 在 apiset 解析阶段直接返回"参数错误"而非"找不到模块"。
"""
import pefile

DIST = r'd:\文档\enterprise-canteen\enterprise-canteen\src-python\dist\canteen-terminal'
PY389 = r'C:\Python389-32'


def imports_map(path):
    pe = pefile.PE(path, fast_load=True)
    pe.parse_data_directories(
        directories=[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_IMPORT']])
    result = {}
    for entry in getattr(pe, 'DIRECTORY_ENTRY_IMPORT', []):
        dll = entry.dll.decode().lower()
        result[dll] = sorted(imp.name.decode() for imp in entry.imports if imp.name)
    pe.close()
    return result


def diff(name, old, new):
    a, b = imports_map(old), imports_map(new)
    print(f'===== {name} =====')
    print(f'  3.8.10 导入的 DLL: {sorted(a)}')
    print(f'  3.8.9  导入的 DLL: {sorted(b)}')
    for dll in sorted(set(a) | set(b)):
        fa, fb = set(a.get(dll, [])), set(b.get(dll, []))
        only_old = fa - fb
        only_new = fb - fa
        if only_old:
            print(f'  [仅 3.8.10 导入] {dll}: {sorted(only_old)}')
        if only_new:
            print(f'  [仅 3.8.9 导入]  {dll}: {sorted(only_new)}')
    print()


diff('_ctypes.pyd', f'{DIST}\\_ctypes.pyd', f'{PY389}\\DLLs\\_ctypes.pyd')
diff('python38.dll', f'{DIST}\\python38.dll', f'{PY389}\\python38.dll')
