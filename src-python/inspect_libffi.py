# -*- coding: utf-8 -*-
"""libffi-7.dll 完整导入/导出分析。

Win7 上 _ctypes.pyd 加载失败(参数错误):
- 若 libffi 导入了 Win7 不认识的 api-ms-win-* API 集 → loader 报 ERROR_INVALID_PARAMETER
- 若 libffi 未导出 _ctypes.pyd(3.8.10)需要的 ffi_prep_closure → 加载失败
"""
import pefile

LIBFFI = r'd:\文档\enterprise-canteen\enterprise-canteen\src-python\dist\canteen-terminal\libffi-7.dll'

pe = pefile.PE(LIBFFI, fast_load=True)
pe.parse_data_directories(directories=[
    pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_IMPORT'],
    pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXPORT'],
])

print('=== libffi-7.dll 导入的 DLL 及函数 ===')
for entry in getattr(pe, 'DIRECTORY_ENTRY_IMPORT', []):
    dll = entry.dll.decode()
    funcs = [imp.name.decode() for imp in entry.imports if imp.name]
    print(f'{dll}:')
    for f in funcs:
        print(f'  {f}')

print()
print('=== libffi-7.dll 导出的函数 ===')
exp = getattr(pe, 'DIRECTORY_ENTRY_EXPORT', None)
if exp:
    symbols = [s.name.decode() for s in exp.symbols if s.name]
    print(f'共 {len(symbols)} 个:')
    for s in sorted(symbols):
        print(f'  {s}')
    print()
    for need in ('ffi_prep_closure', 'ffi_prep_closure_loc'):
        print(f'{need}: {"已导出" if need in symbols else "!!未导出!!"}')
pe.close()
