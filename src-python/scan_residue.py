# -*- coding: utf-8 -*-
"""扫描本机 CanteenTerminal 残留(安装目录/配置缓存/卸载程序/注册表)。"""
import os
import glob
import winreg

KEY = 'CanteenTerminal'

def p(path):
    return os.path.normpath(path)

def scan_dir(path, label):
    if os.path.isdir(path):
        print(f'[DIR] {label}: {p(path)}')
        for root, dirs, files in os.walk(path):
            for f in files:
                full = os.path.join(root, f)
                print(f'      FILE {p(full)}')
    elif os.path.exists(path):
        print(f'[FILE] {label}: {p(path)}')

def scan_matches(base, pattern, label):
    for m in glob.glob(os.path.join(base, pattern)):
        print(f'[GLOB] {label}: {p(m)}')

def scan_registry():
    roots = [winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER]
    base_keys = [
        r'Software\Microsoft\Windows\CurrentVersion\Uninstall',
        r'Software\Microsoft\Windows\CurrentVersion\App Paths',
        r'Software\Microsoft\Windows\CurrentVersion\Run',
        r'Software',
    ]
    print('=== 注册表 ===')
    for root in roots:
        for base in base_keys:
            try:
                with winreg.OpenKey(root, base) as bk:
                    for i in range(winreg.QueryInfoKey(bk)[0]):
                        try:
                            sub = winreg.EnumKey(bk, i)
                        except OSError:
                            break
                        if KEY.lower() in sub.lower():
                            print(f'[REG] {p(root.__repr__())}\\{base}\\{sub}')
            except OSError:
                pass

def main():
    print('===== CanteenTerminal 残留扫描 =====')
    # 安装目录
    for pf in [os.environ.get('ProgramFiles',''), os.environ.get('ProgramFiles(x86)','')]:
        if pf and os.path.isdir(os.path.join(pf, KEY)):
            scan_dir(os.path.join(pf, KEY), '安装目录')
        scan_matches(pf, KEY + '*', '安装目录glob')
    # 所有用户 AppData(配置 + 缓存)
    users = r'C:\Users'
    for uname in os.listdir(users):
        up = os.path.join(users, uname)
        if not os.path.isdir(up):
            continue
        scan_dir(os.path.join(up, 'AppData', 'Roaming', KEY), f'Roaming[{uname}]')
        scan_dir(os.path.join(up, 'AppData', 'Local', KEY), f'Local[{uname}]')
    # 当前用户 AppData
    scan_dir(os.path.join(os.environ.get('APPDATA',''), KEY), '当前用户Roaming')
    scan_dir(os.path.join(os.environ.get('LOCALAPPDATA',''), KEY), '当前用户Local')
    # 开始菜单 / 桌面快捷方式
    scan_matches(os.environ.get('APPDATA', r'C:\Users') + r'\Microsoft\Windows\Start Menu\Programs', '*Canteen*', '开始菜单')
    # 卸载程序残留(unins000)
    for m in glob.glob(r'C:\Program Files*\CanteenTerminal\**\unins*', recursive=True):
        print(f'[UNINS] {p(m)}')
    scan_registry()
    print('===== 扫描结束 =====')

if __name__ == '__main__':
    main()