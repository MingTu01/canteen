#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通过 GitHub 加速器推送 main 分支(直连/本地代理不可用时的兜底)。

用法: python push_via_proxy.py
凭据来源: git credential fill(Windows 凭据管理器中保存的 GitHub PAT)
"""
import subprocess
import sys
import urllib.parse

REPO = 'MingTu01/canteen'
ACCELERATORS = [
    'gh-proxy.com',
    'ghp.keleyaa.com',
    'g.blfrp.cn',
    'gh.llkk.cc',
    'ghpxy.hwinzniej.top',
]


def get_credentials():
    """从 git 凭据助手读取 github.com 的用户名/令牌。"""
    inp = 'protocol=https\nhost=github.com\n\n'
    r = subprocess.run(['git', 'credential', 'fill'], input=inp,
                       capture_output=True, text=True, timeout=15)
    user = pwd = ''
    for line in r.stdout.splitlines():
        if line.startswith('username='):
            user = line.split('=', 1)[1]
        elif line.startswith('password='):
            pwd = line.split('=', 1)[1]
    if not pwd:
        print('[错误] 未取到 GitHub 凭据,请先 git push 一次并让凭据管理器保存')
        sys.exit(1)
    return user or 'oauth2', pwd


def push_via(host, user, pwd):
    token = urllib.parse.quote(user) + ':' + urllib.parse.quote(pwd)
    url = f'https://{token}@{host}/https://github.com/{REPO}.git'
    cmd = ['git', '-c', 'http.proxy=', '-c', 'https.proxy=',
           'push', url, 'main:main']
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    out = (r.stdout + r.stderr).strip()
    # 隐藏令牌再输出
    safe = out.replace(pwd, '***')
    print(safe)
    return r.returncode == 0


def main():
    user, pwd = get_credentials()
    print(f'[信息] 凭据用户: {user}')
    for host in ACCELERATORS:
        print(f'\n[尝试] 通过 {host} 推送 ...')
        if push_via(host, user, pwd):
            print(f'\n[成功] 已通过 {host} 推送 main 分支')
            return
        print(f'[失败] {host} 不可用,换下一个')
    print('\n[错误] 所有加速器均推送失败')
    sys.exit(1)


if __name__ == '__main__':
    main()
