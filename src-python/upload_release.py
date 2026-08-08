"""
上传终端安装包到 GitHub Releases。
用法: python upload_release.py
"""
import os
import sys
import json
import urllib.request
import urllib.error

TOKEN = os.environ.get('GITHUB_TOKEN', '')
REPO = 'MingTu01/canteen'
VERSION = '1.0.5'
TAG = f'v{VERSION}'
EXE_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    'output',
    f'CanteenTerminal-Setup-{VERSION}.exe'
)
RELEASE_BODY = """## X86 取餐终端 V1.0.5

### 新功能
- **摄像头无感后台扫码**:进入待机页自动启动 USB 摄像头,持续解码 QR 码和条形码,与读卡器并行工作,共用同一防抖间隔,无需用户点击
- **配置页设备状态检查**:新增读卡器和摄像头/扫码枪连接状态检测,可查看设备名称,异常时可一键重启读卡器

### Bug 修复
- 取餐待机页错误弹窗 5 秒自动消失,下一位刷卡自动关闭并处理新卡
- 取餐验证页无待取餐订单弹窗 5 秒自动消失返回待机页,下一位刷卡自动关闭并在当前页识别新员工
- 菜品图片缓存修复(避免相对路径 404 锁定错误状态)
- 菜单 IndexedDB 三级缓存(内存→IndexedDB→后端,秒开后台静默刷新)
- 不存在的卡号提示改为"卡号不存在"
"""


def api_request(url, method='GET', data=None, headers=None, content_type='application/json'):
    """发送 GitHub API 请求"""
    h = {
        'Authorization': f'token {TOKEN}',
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'CanteenTerminal-Uploader',
    }
    if headers:
        h.update(headers)
    if data is not None and isinstance(data, (dict, list)) and content_type == 'application/json':
        data = json.dumps(data).encode('utf-8')
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode('utf-8')
            return resp.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8')
        try:
            return e.code, json.loads(body)
        except json.JSONDecodeError:
            return e.code, {'error': body}


def check_existing_release():
    """检查是否已有同名 release"""
    url = f'https://api.github.com/repos/{REPO}/releases/tags/{TAG}'
    status, data = api_request(url)
    if status == 200:
        return data
    return None


def delete_release(release):
    """删除已有 release(连同 asset 一起删)"""
    # 删除 release
    rid = release.get('id')
    if rid:
        url = f'https://api.github.com/repos/{REPO}/releases/{rid}'
        api_request(url, method='DELETE')
    # 删除 tag
    url = f'https://api.github.com/repos/{REPO}/git/refs/tags/{TAG}'
    api_request(url, method='DELETE')


def create_release():
    """创建新 release"""
    url = f'https://api.github.com/repos/{REPO}/releases'
    payload = {
        'tag_name': TAG,
        'name': f'X86 取餐终端 V{VERSION}',
        'body': RELEASE_BODY,
        'draft': False,
        'prerelease': False,
    }
    status, data = api_request(url, method='POST', data=payload)
    if status != 201:
        print(f'创建 release 失败: {status} {data}')
        sys.exit(1)
    return data


def upload_asset(upload_url, exe_path):
    """上传 asset 到 release"""
    # upload_url 格式: https://uploads.github.com/repos/.../releases/{id}/assets{?name,label}
    base_url = upload_url.split('{')[0]
    filename = os.path.basename(exe_path)
    url = f'{base_url}?name={filename}'

    with open(exe_path, 'rb') as f:
        file_data = f.read()

    status, data = api_request(
        url,
        method='POST',
        data=file_data,
        headers={'Content-Type': 'application/octet-stream'},
        content_type=None,
    )
    if status != 201:
        print(f'上传 asset 失败: {status} {data}')
        sys.exit(1)
    return data


def main():
    if not os.path.exists(EXE_PATH):
        print(f'安装包不存在: {EXE_PATH}')
        sys.exit(1)

    size_mb = os.path.getsize(EXE_PATH) / 1024 / 1024
    print(f'安装包: {os.path.basename(EXE_PATH)} ({size_mb:.1f} MB)')

    # 检查是否已有 release,有则先删除
    existing = check_existing_release()
    if existing:
        print(f'发现已有 release {TAG},正在删除...')
        delete_release(existing)
        print('已删除旧 release')

    # 创建新 release
    print(f'正在创建 release {TAG}...')
    release = create_release()
    print(f'Release 创建成功: {release["html_url"]}')

    # 上传 asset
    print('正在上传安装包...')
    asset = upload_asset(release['upload_url'], EXE_PATH)
    print(f'上传成功: {asset["browser_download_url"]}')
    print(f'\nRelease 地址: {release["html_url"]}')
    print('在线更新现在可以检测到此版本!')


if __name__ == '__main__':
    main()
