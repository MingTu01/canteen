#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
企业智慧食堂系统 - 微信公众号配置脚本
==============================================================

功能:
  1. 交互式收集微信公众号配置(AppID / AppSecret / 订阅消息模板ID / H5 URL)
  2. 实时校验 AppID/AppSecret 是否正确(调用微信 API 获取 access_token)
  3. 将配置写入项目根目录的 .env 文件(保留已有内容,仅更新微信相关行)
  4. 可选发送测试订阅消息,验证模板ID是否正确
  5. 可选自动重启后端容器以应用新配置

使用方式:
  cd 项目根目录
  python scripts/wechat_setup.py

注意:
  - 本脚本仅修改 .env 文件,不会泄露或上传任何信息到第三方
  - AppSecret 是敏感信息,请确保 .env 已在 .gitignore 中
  - 员工需在 H5 端使用微信登录并绑定后,才能收到订阅消息推送
  - 一次性订阅消息:员工需在 H5「我的 → 微信提醒订阅」中授权,
    每次授权仅可接收一条消息,同一场景多次授权不累积

前置条件:
  - 已注册微信公众号(认证服务号,订阅号不支持网页授权和订阅消息)
  - 微信开发者平台(developers.weixin.qq.com)获取 AppID 和 AppSecret
  - 微信开发者平台「API IP白名单」添加服务器公网IP
  - 公众号后台(mp.weixin.qq.com)「功能设置 → 网页授权域名」填写 H5 域名(需 443 端口)
  - 公众号后台「功能设置 → 业务域名」填写 H5 域名(订阅授权回跳必需)

重要变更:
  - 2025-12-01 起,开发接口管理迁移至微信开发者平台(developers.weixin.qq.com)
  - 2026-08 起,多模板体系下线:每个公众号仅保留一个固定的「一次性订阅消息」
    模板(开发者平台 → 服务号 → 一次性订阅消息 处查看模板ID),
    WECHAT_TEMPLATE_NOTIFY/ORDER 两项配置填同一模板ID
  - 发送接口 message/template/subscribe(接口英文名 templateSubscribe)
    必填 scene(字符串) + title(≤15字),data 仅支持单个 content 字段(≤200字)
  - 网页授权域名/业务域名/JS接口安全域名 均要求 80/443 端口,不支持带端口
  - 服务器配置(消息推送)URL 也要求 80/443 端口
"""

import json
import os
import re
import sys
import urllib.request

# ============================================================
# 配置常量
# ============================================================

# 脚本所在目录的上一级即为项目根目录
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
ENV_FILE = os.path.join(PROJECT_DIR, ".env")

# 微信 API 端点
WECHAT_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token"
# 一次性订阅消息发送接口(2026-08 新版:必填 scene + title,data 仅单个 content 字段)
WECHAT_SEND_TEMPLATE_URL = "https://api.weixin.qq.com/cgi-bin/message/template/subscribe"

# 需要管理的 .env 变量清单(变量名 → 中文说明)
WECHAT_VARS = {
    "WECHAT_APP_ID": "公众号 AppID",
    "WECHAT_APP_SECRET": "公众号 AppSecret",
    "WECHAT_TEMPLATE_NOTIFY": "一次性订阅消息模板ID(通知)",
    "WECHAT_TEMPLATE_ORDER": "一次性订阅消息模板ID(订单)",
    "WECHAT_H5_BASE_URL": "H5 访问基础URL",
    "WECHAT_TOKEN": "服务器配置Token(回调签名)",
    "WECHAT_H5_BANNER_URL": "图文卡片封面图URL",
    "WECHAT_CANTEEN_NAME": "食堂名称(回复文案)",
}


# ============================================================
# 工具函数
# ============================================================

def print_color(text, color=""):
    """带颜色输出(Windows Terminal / Linux 均支持 ANSI)。"""
    colors = {
        "red": "\033[31m",
        "green": "\033[32m",
        "yellow": "\033[33m",
        "blue": "\033[34m",
        "cyan": "\033[36m",
        "bold": "\033[1m",
        "reset": "\033[0m",
    }
    # Windows 旧版 cmd 不支持 ANSI,启用 VT100 处理
    if sys.platform == "win32":
        try:
            import ctypes
            kernel32 = ctypes.windll.kernel32
            kernel32.SetConsoleMode(kernel32.GetStdHandle(-11), 7)
        except Exception:
            pass
    prefix = colors.get(color, "")
    suffix = colors["reset"] if prefix else ""
    print(f"{prefix}{text}{suffix}")


def info(msg):
    print_color(f"[信息] {msg}", "green")


def warn(msg):
    print_color(f"[警告] {msg}", "yellow")


def error(msg):
    print_color(f"[错误] {msg}", "red")


def title(msg):
    print_color(f"\n{'=' * 60}", "cyan")
    print_color(msg, "cyan")
    print_color(f"{'=' * 60}", "cyan")


def prompt(msg, default="", required=False, validator=None):
    """
    交互式输入。
    - required=True 时不能为空
    - validator 为函数时,返回 (is_valid, error_msg)
    """
    while True:
        suffix = f" [默认: {default}]" if default else ""
        user_input = input(f"{msg}{suffix}: ").strip()
        if not user_input:
            user_input = default
        if required and not user_input:
            warn("该项为必填,请重新输入")
            continue
        if validator and user_input:
            is_valid, err = validator(user_input)
            if not is_valid:
                warn(err)
                continue
        return user_input


def confirm(msg, default_yes=True):
    """确认提示,返回 True/False。"""
    hint = "Y/n" if default_yes else "y/N"
    while True:
        user_input = input(f"{msg} [{hint}]: ").strip().lower()
        if not user_input:
            return default_yes
        if user_input in ("y", "yes"):
            return True
        if user_input in ("n", "no"):
            return False
        warn("请输入 y 或 n")


# ============================================================
# 微信 API 调用
# ============================================================

def http_get_json(url, timeout=10):
    """发起 GET 请求,返回 JSON 字典。"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "CanteenSetup/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        try:
            err_body = e.read().decode("utf-8")
            return json.loads(err_body)
        except Exception:
            return {"errcode": -1, "errmsg": f"HTTP {e.code}: {e.reason}"}
    except Exception as e:
        return {"errcode": -2, "errmsg": str(e)}


def http_post_json(url, payload, timeout=10):
    """发起 POST 请求(JSON body),返回 JSON 字典。"""
    try:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=data,
            headers={"User-Agent": "CanteenSetup/1.0", "Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        try:
            err_body = e.read().decode("utf-8")
            return json.loads(err_body)
        except Exception:
            return {"errcode": -1, "errmsg": f"HTTP {e.code}: {e.reason}"}
    except Exception as e:
        return {"errcode": -2, "errmsg": str(e)}


def get_access_token(app_id, app_secret):
    """
    调用微信 API 获取 access_token。
    返回 (access_token, error_msg),成功时 error_msg 为 None。
    """
    url = f"{WECHAT_TOKEN_URL}?grant_type=client_credential&appid={app_id}&secret={app_secret}"
    result = http_get_json(url)
    errcode = result.get("errcode", 0)
    # 微信成功时 errcode 字段不存在或为 0
    if errcode and str(errcode) != "0":
        errmsg = result.get("errmsg", "未知错误")
        return None, f"errcode={errcode}, errmsg={errmsg}"
    token = result.get("access_token")
    if not token:
        return None, "响应中未包含 access_token 字段"
    return token, None


def send_template_message(access_token, openid, template_id, title, content, url=None, scene=1000):
    """
    发送一次性订阅消息(2026-08 新版:必填 scene + title,data 仅单个 content 字段)。
    返回 (msgid, error_msg),成功时 error_msg 为 None。
    注意:接收人需已在 H5 完成订阅授权,否则返回 43101。
    """
    api_url = f"{WECHAT_SEND_TEMPLATE_URL}?access_token={access_token}"
    payload = {
        "touser": openid,
        "template_id": template_id,
        "scene": str(scene),
        "title": title[:15],
        "data": {"content": {"value": content[:200]}},
    }
    if url:
        payload["url"] = url
    result = http_post_json(api_url, payload)
    errcode = result.get("errcode", 0)
    if errcode and str(errcode) != "0":
        errmsg = result.get("errmsg", "未知错误")
        return None, f"errcode={errcode}, errmsg={errmsg}"
    msgid = result.get("msgid")
    return msgid, None


# ============================================================
# .env 文件读写
# ============================================================

def read_env_file():
    """读取 .env 文件,返回 {变量名: 值} 字典。文件不存在返回空字典。"""
    env = {}
    if not os.path.exists(ENV_FILE):
        return env
    with open(ENV_FILE, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            # 跳过空行和注释行
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip()
            # 去除两侧引号
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                value = value[1:-1]
            env[key] = value
    return env


def write_env_file(updates):
    """
    更新 .env 文件:对 updates 中的每个变量,
    - 已存在则替换该行(包括被注释的行)
    - 不存在则在文件末尾追加
    保留其他行原样不动。
    """
    # 读取原始内容(保留注释和空行)
    if os.path.exists(ENV_FILE):
        with open(ENV_FILE, "r", encoding="utf-8") as f:
            lines = f.readlines()
    else:
        lines = []

    # 记录每个变量是否已处理
    handled = {k: False for k in updates}
    new_lines = []

    for line in lines:
        stripped = line.lstrip()
        # 处理被注释的变量行(如 # WECHAT_APP_ID=xxx)
        is_commented = stripped.startswith("#")
        content = stripped[1:].strip() if is_commented else stripped
        if "=" in content:
            key, _, _ = content.partition("=")
            key = key.strip()
            if key in updates:
                # 替换该行(取消注释,使用新值)
                value = updates[key]
                if value:
                    new_lines.append(f"{key}={value}\n")
                else:
                    # 值为空则保留注释形式(避免写入空值导致解析异常)
                    new_lines.append(f"# {key}=\n")
                handled[key] = True
                continue
        new_lines.append(line)

    # 追加未处理的变量
    unhandled = [k for k, v in handled.items() if not v]
    if unhandled:
        # 检查文件末尾是否有空行
        if new_lines and not new_lines[-1].endswith("\n"):
            new_lines.append("\n")
        if not (new_lines and new_lines[-1].strip() == ""):
            new_lines.append("\n")
        new_lines.append("# ---------- 微信公众号配置(由 wechat_setup.py 生成)----------\n")
        for key in unhandled:
            value = updates[key]
            if value:
                new_lines.append(f"{key}={value}\n")
            else:
                new_lines.append(f"# {key}=\n")

    # 原子写入:先写临时文件再替换
    tmp_file = ENV_FILE + ".tmp"
    with open(tmp_file, "w", encoding="utf-8") as f:
        f.writelines(new_lines)
    os.replace(tmp_file, ENV_FILE)


# ============================================================
# 输入校验器
# ============================================================

def validate_url(val):
    """校验 URL 格式(http/https 开头,末尾不带斜杠)。"""
    if not re.match(r"^https?://", val):
        return False, "URL 必须以 http:// 或 https:// 开头"
    if val.endswith("/"):
        return False, "URL 末尾不要带斜杠"
    return True, ""


def validate_http_url(val):
    """校验图片URL格式(http/https 开头即可,允许带路径)。"""
    if not re.match(r"^https?://", val):
        return False, "URL 必须以 http:// 或 https:// 开头"
    return True, ""


# ============================================================
# 主流程
# ============================================================

def show_intro():
    """显示欢迎与说明。"""
    title("企业智慧食堂 - 微信公众号配置向导")
    print("""
本脚本将引导你完成微信公众号配置,启用以下功能:
  1. H5 微信登录(员工在微信内打开 H5,一键登录)
  2. 通知/公告/活动发布时,推送一次性订阅消息给员工
  3. 员工下单成功时,推送订单订阅消息(含日期、餐次、金额)
  4. 关注公众号后自动回复图文卡片,引导员工进入H5订餐

所需材料(请提前准备好):
  - 公众号 AppID 和 AppSecret(微信开发者平台 → 服务号 → 开发信息)
  - 一次性订阅消息模板ID(微信开发者平台 → 服务号 → 一次性订阅消息,
    2026-08 起每个公众号仅此一个固定模板)
  - 服务器公网IP 已加入白名单(微信开发者平台 → API IP白名单)
  - H5 访问域名 已配置网页授权域名 + 业务域名(mp后台 → 功能设置)
  - 服务器配置 Token 和 EncodingAESKey(本脚本会自动生成,填到
    微信开发者平台 → 服务号 → 消息与事件推送)

注意:必须是「服务号」,订阅号不支持网页授权和订阅消息。
""")


def collect_config(current_env):
    """交互式收集配置,返回配置字典。"""
    title("第 1 步:输入公众号凭证")

    print("请输入公众号 AppID 和 AppSecret(用于登录和获取 access_token)\n")
    app_id = prompt(
        "AppID",
        default=current_env.get("WECHAT_APP_ID", ""),
        required=True,
    )
    app_secret = prompt(
        "AppSecret",
        default=current_env.get("WECHAT_APP_SECRET", ""),
        required=True,
    )

    # 校验凭证:调用微信 API 获取 access_token
    print("\n正在校验 AppID/AppSecret...")
    token, err = get_access_token(app_id, app_secret)
    if err:
        error(f"凭证校验失败: {err}")
        print("\n常见原因:")
        print("  - AppID 或 AppSecret 错误")
        print("  - 服务器公网IP 未加入公众号 IP 白名单")
        print("  - AppSecret 已过期(可在公众号后台重置)")
        if not confirm("是否仍要保存此配置?(不推荐)", default_yes=False):
            warn("已取消,配置未保存")
            sys.exit(0)
        token = None
    else:
        info("凭证校验成功!access_token 获取正常")

    title("第 2 步:输入订阅消息模板配置")

    print("""
请输入一次性订阅消息模板ID。

2026-08 起微信开发者平台已下线多模板体系,每个公众号仅保留一个固定的
「一次性订阅消息」模板,查看位置:
  微信开发者平台(developers.weixin.qq.com)→ 我的业务 → 服务号
  → 一次性订阅消息 → 模板ID

只需输入这一个模板ID,通知/订单两类推送共用(两项自动填同一值)。
员工需在 H5「我的 → 微信提醒订阅」中授权后才能收到消息。
如暂不启用推送,直接回车留空即可。
""")

    template_notify = prompt(
        "一次性订阅消息 模板ID",
        default=current_env.get("WECHAT_TEMPLATE_NOTIFY", ""),
        required=False,
    )
    # 新版仅一个固定模板:两项配置填同一值
    template_order = template_notify

    print("\n请输入 H5 访问基础URL(用于模板消息点击跳转)")
    print("示例: https://canteen.example.com (末尾不带斜杠)")
    h5_base_url = prompt(
        "H5 基础URL",
        default=current_env.get("WECHAT_H5_BASE_URL", ""),
        required=False,
        validator=validate_url,
    )

    # ===== 第 3 步:消息回调配置(关注后自动回复) =====
    title("第 3 步:配置关注后自动回复(消息回调)")

    import secrets as _secrets
    # 自动生成 Token 和 EncodingAESKey(用户也可自定义 Token)
    default_token = current_env.get("WECHAT_TOKEN", "") or _secrets.token_hex(16)
    aes_key = _secrets.token_urlsafe(32)[:43]

    print("""
配置后,员工关注公众号会自动收到图文卡片,点击直接进入H5订餐。
需在公众号后台「基本配置 → 服务器配置」填写以下内容:

  URL(服务器地址):     https://你的H5域名/api/wechat/callback
  Token(令牌):         与下方输入一致(已自动生成,可直接回车采用)
  EncodingAESKey:      {aes_key}(已自动生成,复制填入后台;明文模式不参与校验)
  消息加解密方式:       选「明文模式」

填写后点击「提交」,微信会GET回调地址校验签名,通过后即启用。
如暂不需要关注后自动回复,Token 留空跳过即可(不影响登录和模板消息)。
""".format(aes_key=aes_key))

    wechat_token = prompt(
        "服务器配置 Token(直接回车采用自动生成的值)",
        default=default_token,
        required=False,
    )
    print(f"\n  → 请将此 Token 填到公众号后台服务器配置: {wechat_token}")
    print(f"  → 请将此 EncodingAESKey 填到公众号后台: {aes_key}")

    print("\n图文卡片封面图URL(建议 900x500 像素,公网可访问的图片地址)")
    print("不配置则关注后回退纯文本回复(仍可引导订餐,只是没有图片卡片)")
    h5_banner_url = prompt(
        "封面图URL",
        default=current_env.get("WECHAT_H5_BANNER_URL", ""),
        required=False,
        validator=validate_http_url,
    )

    canteen_name = prompt(
        "食堂名称(关注回复文案显示,如 XX企业食堂)",
        default=current_env.get("WECHAT_CANTEEN_NAME", "企业食堂"),
        required=False,
    )

    return {
        "WECHAT_APP_ID": app_id,
        "WECHAT_APP_SECRET": app_secret,
        "WECHAT_TEMPLATE_NOTIFY": template_notify,
        "WECHAT_TEMPLATE_ORDER": template_order,
        "WECHAT_H5_BASE_URL": h5_base_url,
        "WECHAT_TOKEN": wechat_token,
        "WECHAT_H5_BANNER_URL": h5_banner_url,
        "WECHAT_CANTEEN_NAME": canteen_name,
    }


def test_template(config, token):
    """发送测试模板消息。"""
    title("第 4 步:发送测试模板消息(可选)")

    if not token:
        warn("access_token 不可用,跳过测试")
        return

    available = []
    if config.get("WECHAT_TEMPLATE_NOTIFY"):
        available.append(("通知提醒(scene=1000)", "WECHAT_TEMPLATE_NOTIFY", 1000))
    if config.get("WECHAT_TEMPLATE_ORDER"):
        available.append(("订单提醒(scene=1001)", "WECHAT_TEMPLATE_ORDER", 1001))

    if not available:
        warn("未配置模板ID,跳过测试")
        return

    print("\n注意:测试前,该微信号需先在 H5「我的 → 微信提醒订阅」中完成对应订阅授权,")
    print("      否则微信会返回 43101(用户未订阅)。")

    if not confirm("是否发送测试消息验证配置?(需要一个已绑定微信的 openid)", default_yes=False):
        return

    print("\n可测试的提醒类型:")
    for i, (label, _, _) in enumerate(available, 1):
        print(f"  {i}. {label}")
    print(f"  {len(available) + 1}. 跳过测试")

    choice = prompt("选择测试项", default=str(len(available) + 1))
    try:
        choice_idx = int(choice) - 1
    except ValueError:
        warn("选择无效,跳过测试")
        return
    if choice_idx < 0 or choice_idx >= len(available):
        info("已跳过测试")
        return

    label, var_key, scene = available[choice_idx]
    template_id = config[var_key]
    openid = prompt("接收测试消息的 openid(员工已绑定的微信 openid)", required=True)

    # 新版固定模板:标题(≤15字) + 单条正文(≤200字)
    test_title = "【测试】订阅消息"
    test_content = "如果你看到了这条消息,说明一次性订阅消息配置正确。"

    url = config.get("WECHAT_H5_BASE_URL", "")
    click_url = f"{url}/" if url else None

    print(f"\n正在发送测试消息到 {openid}...")
    msgid, err = send_template_message(token, openid, template_id, test_title, test_content, click_url, scene)
    if err:
        error(f"发送失败: {err}")
        print("\n常见错误码:")
        print("  40037: 模板ID不正确")
        print("  43101: 用户未订阅(先在 H5「微信提醒订阅」中授权)")
        print("  40003: openid 不正确(用户未绑定或 openid 不属于此公众号)")
    else:
        info(f"发送成功!msgid={msgid}")
        print("请在微信中查看是否收到测试消息。")


def save_and_apply(config):
    """保存配置到 .env 并提示重启。"""
    title("第 5 步:保存配置")

    print(f"将写入以下配置到 .env 文件:\n")
    for key, label in WECHAT_VARS.items():
        value = config.get(key, "")
        # AppSecret 脱敏显示
        if key == "WECHAT_APP_SECRET" and value:
            display = value[:4] + "*" * (len(value) - 8) + value[-4:] if len(value) > 8 else "****"
        else:
            display = value or "(空,不启用)"
        print(f"  {label:<25} {display}")

    print()
    if not confirm("确认保存?", default_yes=True):
        warn("已取消,配置未保存")
        return False

    try:
        write_env_file(config)
        info(f"配置已写入 {ENV_FILE}")
    except Exception as e:
        error(f"写入 .env 失败: {e}")
        return False

    # 提示重启
    print("\n配置已保存,但需要重启后端服务才能生效。")
    print("\n重启方式(根据你的部署方式选择):")
    print("  Docker Compose 部署:")
    print("    docker compose up -d backend")
    print("\n  或使用管理面板:")
    print("    canteen  # 选择「重启服务」")

    if confirm("\n是否立即重启后端容器?", default_yes=False):
        restart_backend()
    else:
        info("请稍后手动重启后端服务以应用新配置")

    return True


def restart_backend():
    """通过 docker compose 重启后端容器。"""
    import subprocess
    info("正在重启后端容器...")
    try:
        result = subprocess.run(
            ["docker", "compose", "up", "-d", "backend"],
            cwd=PROJECT_DIR,
            capture_output=True,
            text=True,
            timeout=120,
        )
        if result.returncode == 0:
            info("后端容器已重启,新配置已生效")
            print("\n提示:可使用以下命令查看启动日志确认无报错:")
            print("  docker compose logs -f backend --tail=50")
        else:
            error(f"重启失败(退出码 {result.returncode})")
            if result.stderr:
                print(result.stderr)
            print("\n请手动执行: docker compose up -d backend")
    except FileNotFoundError:
        error("未找到 docker 命令,请确认 Docker 已安装并在 PATH 中")
    except subprocess.TimeoutExpired:
        error("重启超时(120 秒),请手动检查容器状态")
    except Exception as e:
        error(f"重启异常: {e}")


def show_current_config(current_env):
    """显示当前已保存的微信配置。"""
    title("当前已保存的微信配置")
    has_any = False
    for key, label in WECHAT_VARS.items():
        value = current_env.get(key, "")
        if value:
            has_any = True
            if key == "WECHAT_APP_SECRET":
                display = value[:4] + "*" * (len(value) - 8) + value[-4:] if len(value) > 8 else "****"
            else:
                display = value
            print(f"  {label:<25} {display}")
        else:
            print(f"  {label:<25} (未配置)")
    if not has_any:
        print("\n  当前未配置任何微信相关参数")


def main():
    show_intro()

    # 读取当前 .env 配置
    current_env = read_env_file()
    show_current_config(current_env)

    # 检查 .env 文件是否存在
    if not os.path.exists(ENV_FILE):
        warn(f"未找到 .env 文件({ENV_FILE})")
        warn("如果是首次部署,请先运行 ./deploy.sh 完成基础部署")
        if not confirm("是否继续创建 .env 并配置微信?", default_yes=False):
            info("已退出")
            return

    # 收集配置
    config = collect_config(current_env)

    # 重新获取 token(可能在收集过程中过期)
    token, _ = get_access_token(config["WECHAT_APP_ID"], config["WECHAT_APP_SECRET"])

    # 测试模板消息
    test_template(config, token)

    # 保存并应用
    save_and_apply(config)

    title("配置完成")
    print("""
后续步骤:
  1. 重启后端服务(如未自动重启)
  2. 员工在微信内打开 H5 → 点击「微信登录」→ 输入手机号+密码完成绑定
  3. 绑定后即可收到通知/公告/活动推送,以及下单成功推送
  4. 在管理后台发布一条通知,验证推送是否正常

如需重新配置,再次运行本脚本即可:
  python scripts/wechat_setup.py
""")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n")
        warn("已取消,配置未保存")
        sys.exit(0)
    except Exception as e:
        error(f"脚本异常: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
