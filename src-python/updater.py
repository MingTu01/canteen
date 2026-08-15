# -*- coding: utf-8 -*-
"""在线更新模块。

功能:
  1. 启动时后台检测 GitHub Releases 是否有新版本(可叠加加速器前缀)。
  2. 有新版时弹窗提示(下载更新 / 取消 / 忽略此版本)。
  3. 点击"下载更新"自动下载安装包,下载完成后退出本程序并静默运行新安装包。
  4. 新安装包通过 Inno Setup 升级安装(自动替换旧版本文件,保留 config.json)。

配置项(见 config.json):
  update_check_url : 远程版本检测地址(留空则用默认 GitHub Releases + 加速器)
  ignored_version  : 用户"忽略此版本"后记录的最新版本号

说明:
  GitHub Releases 资产命名约定为 CanteenTerminal-Setup-<版本号>.exe。
  检测通过读取 release 的 tag(如 v1.0.4)或资产文件名中的版本号完成。
"""
import json
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import urllib.error

# 默认 GitHub 仓库与发行版信息
DEFAULT_REPO = 'MingTu01/canteen'
DEFAULT_RELEASE_API = f'https://api.github.com/repos/{DEFAULT_REPO}/releases/latest'

# 加速器前缀列表(按序尝试,提高国内网络可达性)
# 加速器格式:在 GitHub 原始地址前拼接前缀
ACCELERATOR_PREFIXES = [
    'https://gh-proxy.com/',
    'https://mirror.ghproxy.com/',
    'https://ghfast.top/',
    '',  # 最后一个为空 = 直连
]

# 下载地址域名白名单:仅允许 https 且 host 精确命中(或为其子域名)。
# 覆盖 _accelerate 实际产生的加速域名 + GitHub 原始/重定向域名,
# 防止 release 元数据被篡改后重定向到任意主机下发恶意安装包。
ALLOWED_DOWNLOAD_HOSTS = frozenset({
    'github.com',
    'objects.githubusercontent.com',
    'gh-proxy.com',
    'mirror.ghproxy.com',
    'ghfast.top',
    'gh-proxy.net',
    'github.moeyy.xyz',
})

# 安装包文件名模式(用于从资产名提取版本号)
SETUP_NAME_RE = re.compile(r'CanteenTerminal-Setup-([\d.]+)\.exe', re.IGNORECASE)

# 当前终端版本兜底常量(与仓库根 VERSIONS.json 的 terminal.version 保持一致,以文件为准)
CURRENT_VERSION = '1.0.28'

USER_AGENT = 'CanteenTerminal-Updater/1.0'


def resolve_current_version():
    """解析当前终端版本号。

    优先读取 EXE 同目录(及 _internal 子目录,PyInstaller onedir datas 落点)
    下 VERSIONS.json 的 terminal.version 字段(去掉 v 前缀);
    文件缺失/解析失败时回退内置常量 CURRENT_VERSION。
    """
    try:
        from config import get_exe_dir
        exe_dir = get_exe_dir()
    except Exception:
        exe_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(exe_dir, 'VERSIONS.json'),
        os.path.join(exe_dir, '_internal', 'VERSIONS.json'),
    ]
    if not getattr(sys, 'frozen', False):
        # 开发模式(未打包):读仓库根目录的 VERSIONS.json
        candidates.append(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'VERSIONS.json'))
    for path in candidates:
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            ver = str((data.get('terminal') or {}).get('version', '')).strip()
            if ver:
                return ver.lstrip('vV')
        except Exception:
            continue
    return CURRENT_VERSION


def get_current_version():
    """返回当前终端版本号(优先 VERSIONS.json,见 resolve_current_version)。"""
    return resolve_current_version()


def _version_key(version_text):
    """将版本号字符串转为可比较的元组(ex: '1.0.4' -> (1,0,4))。"""
    parts = re.findall(r'\d+', version_text)
    return tuple(int(p) for p in parts) or (0,)


def _extract_version(text):
    """从 release tag 或资产名中提取版本号字符串,失败返回空串。"""
    m = SETUP_NAME_RE.search(text)
    if m:
        return m.group(1)
    m = re.search(r'v?(\d+\.\d+\.\d+[.\w]*)', text, re.IGNORECASE)
    if m:
        return m.group(1)
    return ''


def _build_check_url(config_update_check_url):
    """根据配置构造远程版本检测地址。

    若用户在 config.json 显式配置了 update_check_url 则直接使用(可含加速器);
    否则使用默认 Releases API,并依次尝试各加速器前缀。
    """
    if config_update_check_url:
        return [config_update_check_url]
    return [p + DEFAULT_RELEASE_API for p in ACCELERATOR_PREFIXES]


def _http_get_text(url, timeout=10):
    """GET 请求并返回文本内容。失败抛异常。"""
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT, 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode('utf-8', errors='replace')


def fetch_latest_release(config_update_check_url=''):
    """获取远端最新版本信息。

    Returns:
        dict 或 None:
        {
          'version': '1.0.5',      # 远端最新版本号
          'tag': 'v1.0.5',         # release tag
          'asset_name': 'CanteenTerminal-Setup-1.0.5.exe',
          'asset_url': 'https://...',   # 已叠加可用加速器的下载地址
          'asset_size': 68786560,
          'notes': '更新说明...',
        }
        网络失败或未找到安装包资产时返回 None。
    """
    for url in _build_check_url(config_update_check_url):
        try:
            text = _http_get_text(url)
            data = json.loads(text)
            if not isinstance(data, dict):
                continue
            tag = data.get('tag_name', '') or ''
            # 从资产中找安装包
            asset = None
            for a in (data.get('assets') or []):
                name = a.get('name', '')
                if SETUP_NAME_RE.search(name):
                    asset = a
                    break
            if not asset:
                # 无安装包资产,回退用 tag 判断版本(仍可提示更新但不下载)
                version = _extract_version(tag) or tag.strip('v')
                if not version:
                    continue
                return {
                    'version': version,
                    'tag': tag,
                    'asset_name': '',
                    'asset_url': '',
                    'asset_size': 0,
                    'notes': data.get('body', '') or '',
                }
            # 优先用资产名中的版本号(更准确)
            version = _extract_version(asset.get('name', '')) or _extract_version(tag) or tag.strip('v')
            asset_url = asset.get('browser_download_url', '')
            return {
                'version': version,
                'tag': tag,
                'asset_name': asset.get('name', ''),
                'asset_url': asset_url,
                'asset_size': asset.get('size', 0),
                # 版本清单(GitHub release 资产)若提供 sha256 字段则下载后强制校验
                'asset_sha256': (asset.get('sha256') or asset.get('digest') or '').strip(),
                'notes': data.get('body', '') or '',
            }
        except Exception as e:
            print(f'[Updater] 检测失败({url}): {e}')
            continue
    return None


def check_for_update(config_update_check_url='', ignored_version=''):
    """检查是否有需要提示的新版本。

    规则:
      - 远端版本号大于当前版本才提示;
      - 若远端版本号等于用户已忽略的版本号,则不提示(直到出现更新的版本)。

    Returns:
        与 fetch_latest_release 相同的 dict,或 None(无新版本)。
    """
    release = fetch_latest_release(config_update_check_url)
    if not release:
        return None
    remote_ver = release['version']
    if _version_key(remote_ver) <= _version_key(get_current_version()):
        return None
    if ignored_version and _version_key(remote_ver) == _version_key(ignored_version):
        return None
    return release


def _accelerate(url):
    """为下载地址叠加加速器前缀(返回可尝试的多个地址)。"""
    if not url:
        return []
    candidates = []
    for prefix in ACCELERATOR_PREFIXES:
        candidates.append(prefix + url)
    # 去重(直连为空前缀时与 url 相同)
    seen = set()
    uniq = []
    for c in candidates:
        if c not in seen:
            seen.add(c)
            uniq.append(c)
    return uniq


def _is_allowed_download_url(url):
    """校验下载地址是否在域名白名单内(必须 https,host 为白名单域名或其子域名)。

    防止 release 元数据/配置被篡改后从任意主机下载并执行安装包。
    """
    try:
        parsed = urllib.parse.urlparse(url)
    except Exception:
        return False
    if parsed.scheme != 'https':
        return False
    host = (parsed.hostname or '').lower()
    if not host:
        return False
    for allowed in ALLOWED_DOWNLOAD_HOSTS:
        # 精确命中或子域名(如 v5.github.com / release-assets 情况由白名单本身覆盖)
        if host == allowed or host.endswith('.' + allowed):
            return True
    return False


def _sha256_of_file(path):
    """计算文件的 sha256 十六进制摘要(小写)。"""
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def download_installer(url, dest_path, progress_cb=None, expected_sha256=''):
    """下载安装包到 dest_path。依次尝试各加速器。

    安全策略:
      1. 域名白名单:每个候选地址(含 _accelerate 叠加的加速器)必须
         https 且 host 在 ALLOWED_DOWNLOAD_HOSTS 内,否则拒绝该地址;
      2. sha256 校验:版本清单提供 sha256 时下载后强制比对,不匹配拒绝
         (抛异常,不执行安装);未提供则记录 warning 后继续(兼容现有发布流程)。

    Args:
        url: 原始下载地址(GitHub browser_download_url)
        dest_path: 保存路径
        progress_cb: 可选回调 progress_cb(downloaded_bytes, total_bytes)
        expected_sha256: 版本清单提供的 sha256(空串表示未提供)

    Returns:
        dest_path 成功保存的路径。
    """
    last_err = None
    for candidate in _accelerate(url):
        if not _is_allowed_download_url(candidate):
            print(f'[Updater] 拒绝非白名单下载地址: {candidate}')
            last_err = RuntimeError(f'下载地址不在域名白名单内: {candidate}')
            continue
        try:
            _download(candidate, dest_path, progress_cb)
        except Exception as e:
            last_err = e
            print(f'[Updater] 下载失败({candidate}): {e}')
            continue
        if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
            # sha256 强制校验:清单提供则必须匹配,否则拒绝执行
            # (不匹配直接抛出且不重试其他地址——同一资产的校验值与下载地址无关)
            if expected_sha256:
                actual = _sha256_of_file(dest_path)
                if actual.lower() != expected_sha256.lower():
                    # 文件被篡改或下载损坏,删除残留文件后整体失败
                    try:
                        os.remove(dest_path)
                    except Exception:
                        pass
                    raise RuntimeError(
                        f'sha256 校验失败:期望 {expected_sha256.lower()},实际 {actual}'
                    )
                print('[Updater] sha256 校验通过')
            else:
                print('[Updater] 警告:版本清单未提供 sha256,跳过完整性校验')
            return dest_path
    if last_err:
        raise last_err
    raise RuntimeError('下载失败:未找到可用下载地址')


def _download(url, dest_path, progress_cb=None):
    """单地址下载实现。"""
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    tmp = dest_path + '.part'
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, 'wb') as f:
        total = int(resp.headers.get('Content-Length') or 0)
        downloaded = 0
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            if progress_cb:
                progress_cb(downloaded, total)
    os.replace(tmp, dest_path)


def run_installer(installer_path):
    """静默运行新版本安装包(升级安装,保留配置)。

    Inno Setup 静默参数:
      /VERYSILENT 全程静默(无进度窗口)
      /SUPPRESSMSGBOXES 抑制所有消息框,使用默认应答
      /NORESTART 不自动重启

    升级安装时 Inno 会静默运行旧版卸载程序以替换旧文件。为避免旧版卸载程序
    清理 %APPDATA%\\CanteenTerminal\\config.json 等用户配置,安装包的 [Code]
    已在 InitializeSetup 阶段先行备份所有用户配置、安装完成后(ssPostInstall)
    再恢复(见 installer.iss 的 BackupUserConfig / RestoreUserConfig)。
    """
    if not os.path.exists(installer_path):
        raise FileNotFoundError(f'安装包不存在: {installer_path}')
    # 直接启动安装包(通过 ShellExecute 提升权限,由 Inno 请求 UAC)
    # 这里使用 subprocess 启动,交由系统 UAC 提权
    subprocess.Popen(
        [installer_path, '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'],
        shell=False,
    )