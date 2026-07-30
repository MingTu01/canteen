"""
config.json 配置文件读写。

此文件与终端 EXE 放在同一目录下,修改后重启应用生效。
支持 // 行注释(解析时自动去除)。
管理员密码验证由后端 /api/admin/login 接口完成,无需本地配置。
"""
import json
import os
import sys
import re


def get_exe_dir():
    """获取 EXE 同目录路径(用于读写 config.json)。

    PyInstaller 打包后,sys.executable 是 EXE 路径;
    开发模式下,用 __file__ 所在目录。
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
  //   域名部署:   "https://canteen.xxx.com"
  //   注意:不要带末尾斜杠 /,不要带 /api 后缀(程序会自动拼接)
  //
  // 管理员密码验证由后端 /api/admin/login 接口完成,
  // 无需在此文件配置密码,使用系统现有的管理员账号即可。
  "server_url": "",

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
  "idle_timeout": 30
}
"""


def ensure_config_json():
    """确保 config.json 存在于 EXE 同目录。首次运行时自动生成默认配置。"""
    cfg_path = os.path.join(get_exe_dir(), 'config.json')
    if not os.path.exists(cfg_path):
        try:
            with open(cfg_path, 'w', encoding='utf-8') as f:
                f.write(DEFAULT_CONFIG_JSON)
            print(f'[Bootstrap] 默认 config.json 已生成: {cfg_path}')
        except Exception as e:
            print(f'[Bootstrap] config.json 生成失败: {e}')


def read_config():
    """读取 EXE 同目录的 config.json,返回 server_url。

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
    cfg_path = os.path.join(get_exe_dir(), 'config.json')
    # 默认值
    result = {
        'server_url': '',
        'window_mode': DEFAULT_WINDOW_MODE,
        'card_interval': DEFAULT_CARD_INTERVAL,
        'idle_timeout': DEFAULT_IDLE_TIMEOUT,
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
    except Exception as e:
        print(f'[Config] 读取配置失败: {e}')
    return result


def write_config(updates):
    """更新 config.json 中的部分字段,保留其他字段和注释。

    Args:
        updates: dict,要更新的字段(key 必须是 server_url/window_mode/card_interval/idle_timeout)
    """
    cfg_path = os.path.join(get_exe_dir(), 'config.json')
    # 读取现有配置(已含默认值)
    current = read_full_config()
    # 合并更新
    for key in ('server_url', 'window_mode', 'card_interval', 'idle_timeout'):
        if key in updates:
            current[key] = updates[key]
    # 写回(不带注释,但 JSON 格式化)
    try:
        with open(cfg_path, 'w', encoding='utf-8') as f:
            json.dump(current, f, ensure_ascii=False, indent=2)
        print(f'[Config] 配置已更新: {updates}')
        return True
    except Exception as e:
        print(f'[Config] 写入配置失败: {e}')
        return False
