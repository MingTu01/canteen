"""
config.json 配置文件读写。

配置文件存放在用户专属目录 %APPDATA%\\CanteenTerminal\\config.json,
而非 EXE 同目录(安装版 EXE 在 Program Files 下只读)。
支持 // 行注释(解析时自动去除)。
管理员密码验证由后端 /api/admin/login 接口完成,无需本地配置。
"""
import json
import os
import shutil
import sys
import re


def get_exe_dir():
    """获取 EXE 同目录路径(用于查找 web 资源、DLL 等只读文件)。

    注意:config.json 不再存放在此目录(安装版 EXE 在 Program Files 下只读),
    改用 get_appdata_dir()。
    """
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def get_meipass():
    """获取 PyInstaller 临时解压目录(内嵌资源所在)。

    开发模式下返回 exe_dir。
    """
    if getattr(sys, 'frozen', False):
        return sys._MEIPASS
    return get_exe_dir()


def get_appdata_dir():
    """获取用户配置目录(%APPDATA%\\CanteenTerminal),用于存放 config.json。

    安装版 EXE 位于 Program Files(对普通用户只读),
    config.json 必须放在用户有写权限的目录,这是 Windows 程序的标准做法。
    """
    appdata = os.environ.get('APPDATA')
    if not appdata:
        appdata = os.path.expanduser('~\\AppData\\Roaming')
    return os.path.join(appdata, 'CanteenTerminal')


def get_local_appdata_dir():
    """获取用户本地数据目录(%LOCALAPPDATA%\\CanteenTerminal)。

    用于存放 QtWebEngine 持久化数据(LocalStorage/IndexedDB/Cookies/
    Network State),需要可读写且不漫游。
    """
    appdata = os.environ.get('LOCALAPPDATA')
    if not appdata:
        appdata = os.path.expanduser('~\\AppData\\Local')
    return os.path.join(appdata, 'CanteenTerminal')


def strip_json_comments(content):
    """去除 JSON 文本中的 // 行注释,使 config.json 支持注释。"""
    lines = content.splitlines()
    cleaned = []
    for line in lines:
        # 只去除不在字符串内的 // 注释(简单处理:找第一个 //)
        idx = _find_comment_start(line)
        if idx >= 0:
            line = line[:idx]
        cleaned.append(line)
    return '\n'.join(cleaned)


def _find_comment_start(line):
    """在行中查找 // 注释的起始位置(跳过字符串内的 //)"""
    in_string = False
    escape = False
    i = 0
    while i < len(line):
        ch = line[i]
        if escape:
            escape = False
        elif ch == '\\':
            escape = True
        elif ch == '"':
            in_string = not in_string
        elif not in_string and ch == '/' and i + 1 < len(line) and line[i + 1] == '/':
            return i
        i += 1
    return -1


# 默认 config.json 内容(首次运行自动生成,含注释说明)
DEFAULT_CONFIG_JSON = """{
  // ============================================================
  // 企业智慧食堂终端 - 配置文件
  // ============================================================
  // 此文件与终端 EXE 放在同一目录下,修改后重启应用生效
  // 支持 // 行注释(解析时自动去除)
  // ------------------------------------------------------------

  // 预设后端服务器地址
  // 用途:终端绑定页面会自动填入此地址,方便批量部署时无需手动输入
  // 留空("")则要求操作员在绑定页面手动输入服务器地址
  //
  // 填写示例:
  //   局域网部署: "http://192.168.1.100:8080"
  //   域名部署:   "https://canteen.908521.xyz"
  //   注意:不要带末尾斜杠 /,不要带 /api 后缀(程序会自动拼接)
  //
  // 管理员密码验证由后端 /api/admin/login 接口完成,
  // 无需在此文件配置密码,使用系统现有的管理员账号即可。
  "server_url": "https://canteen.908521.xyz",

  // ============================================================
  // 终端运行参数
  // ============================================================

  // 窗口模式:
  //   "fullscreen" - 全屏无边框(默认,适合终端设备)
  //   "windowed"   - 窗口模式(1280x800,可调整大小,适合调试)
  // 在配置页"进入运行模式"时动态生效(无需重启)
  "window_mode": "fullscreen",

  // 读卡防抖间隔(秒)
  // 同一张卡在此间隔内不重复触发,避免一次刷卡多次响应
  // 推荐值:1.0 ~ 3.0 秒
  "card_interval": 2.0,

  // 无操作自动返回待机页时间(秒)
  // 用户在选菜/取餐页面无任何操作超过此时间后,自动返回待机页
  // 推荐值:30 ~ 300 秒(0 表示永不自动返回)
  "idle_timeout": 30,

  // ============================================================
  // 在线更新设置
  // ============================================================

  // 在线更新检查地址(基于 GitHub Releases,可叠加加速器前缀)
  // 留空("")则使用默认加速器检测 GitHub 最新版本
  // 示例:
  //   直连:   "https://api.github.com/repos/MingTu01/canteen/releases/latest"
  //   加速器: "https://gh-proxy.com/https://api.github.com/repos/MingTu01/canteen/releases/latest"
  "update_check_url": "",

  // 用户选择"忽略此版本"后记录的最新版本号
  // 程序启动检测时,若远端版本等于该值则不再弹窗(直到出现更新的版本)
  "ignored_version": ""
}
"""


def get_config_path():
    """返回 config.json 的实际路径(%APPDATA%\\CanteenTerminal\\config.json)。"""
    return os.path.join(get_appdata_dir(), 'config.json')


def migrate_legacy_config():
    """从旧位置(EXE 同目录)迁移 config.json 到 %APPDATA%。

    升级到新版后,旧 config.json 仍在 Program Files 下(只读),
    迁移到用户目录以保证可读写且不丢失已配置的 server_url 等参数。
    仅在目标不存在时迁移一次,避免覆盖。
    """
    new_path = get_config_path()
    old_path = os.path.join(get_exe_dir(), 'config.json')
    if not os.path.exists(new_path) and os.path.exists(old_path):
        try:
            os.makedirs(get_appdata_dir(), exist_ok=True)
            shutil.copy2(old_path, new_path)
            print(f'[Config] 已迁移旧配置: {old_path} -> {new_path}')
        except Exception as e:
            print(f'[Config] 迁移旧配置失败(将使用默认配置): {e}')


def ensure_config_json():
    """确保 config.json 存在于 %APPDATA%。首次运行时自动生成默认配置。"""
    migrate_legacy_config()
    cfg_dir = get_appdata_dir()
    cfg_path = get_config_path()
    if not os.path.exists(cfg_path):
        try:
            os.makedirs(cfg_dir, exist_ok=True)
            with open(cfg_path, 'w', encoding='utf-8') as f:
                f.write(DEFAULT_CONFIG_JSON)
            print(f'[Bootstrap] 默认 config.json 已生成: {cfg_path}')
        except Exception as e:
            print(f'[Bootstrap] config.json 生成失败: {e}')


def read_config():
    """读取 config.json,返回 server_url。

    如果文件不存在或解析失败,返回空字符串。
    """
    return read_full_config().get('server_url', '')


# 默认运行参数(与 DEFAULT_CONFIG_JSON 保持一致)
DEFAULT_WINDOW_MODE = 'fullscreen'
DEFAULT_CARD_INTERVAL = 2.0
DEFAULT_IDLE_TIMEOUT = 30


def read_full_config():
    """读取完整配置,返回 dict。

    缺失的字段用默认值填充,保证调用方能直接取到所有字段。
    """
    ensure_config_json()
    cfg_path = get_config_path()
    # 默认值(与 DEFAULT_CONFIG_JSON 中的预设地址保持一致)
    result = {
        'server_url': 'https://canteen.908521.xyz',
        'window_mode': DEFAULT_WINDOW_MODE,
        'card_interval': DEFAULT_CARD_INTERVAL,
        'idle_timeout': DEFAULT_IDLE_TIMEOUT,
        'update_check_url': '',
        'ignored_version': '',
    }
    try:
        with open(cfg_path, 'r', encoding='utf-8') as f:
            content = f.read()
        cleaned = strip_json_comments(content)
        data = json.loads(cleaned)
        if isinstance(data, dict):
            # 只覆盖存在且类型正确的字段
            if isinstance(data.get('server_url'), str):
                result['server_url'] = data['server_url']
            if isinstance(data.get('window_mode'), str) and data['window_mode'] in ('fullscreen', 'windowed'):
                result['window_mode'] = data['window_mode']
            if isinstance(data.get('card_interval'), (int, float)) and data['card_interval'] > 0:
                result['card_interval'] = float(data['card_interval'])
            if isinstance(data.get('idle_timeout'), (int, float)) and data['idle_timeout'] >= 0:
                result['idle_timeout'] = int(data['idle_timeout'])
            if isinstance(data.get('update_check_url'), str):
                result['update_check_url'] = data['update_check_url']
            if isinstance(data.get('ignored_version'), str):
                result['ignored_version'] = data['ignored_version']
    except Exception as e:
        print(f'[Config] 读取配置失败: {e}')
    return result


def write_config(updates):
    """更新 config.json 中的部分字段,保留其他字段和注释。

    Args:
        updates: dict,要更新的字段(key 必须是受支持的配置字段)
    """
    cfg_path = get_config_path()
    # 读取现有配置(已含默认值)
    current = read_full_config()
    # 合并更新
    for key in ('server_url', 'window_mode', 'card_interval', 'idle_timeout', 'update_check_url', 'ignored_version'):
        if key in updates:
            current[key] = updates[key]
    # 写回(不带注释,但 JSON 格式化)
    try:
        os.makedirs(get_appdata_dir(), exist_ok=True)
        with open(cfg_path, 'w', encoding='utf-8') as f:
            json.dump(current, f, ensure_ascii=False, indent=2)
        print(f'[Config] 配置已更新: {updates}')
        return True
    except Exception as e:
        print(f'[Config] 写入配置失败: {e}')
        return False
