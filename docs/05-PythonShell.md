# 05 · Python Shell 需求文档（src-python）

> 版本：V0.0.3 ｜ 更新日期：2026-08-15
> 代码路径：[src-python/](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python)
> 终端前端：详见 [04-X86终端.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/04-X86终端.md)

---

## 1. 项目定位

X86 终端的桌面壳程序，使用 **Python + PyQt5 + QWebEngineView** 加载 Vue 前端，替代 Tauri/Electron 方案，**兼容 Win7/Win10/Win11 32/64 位**（包括 Win7 32 位这一无法安装 Edge WebView2 的老旧系统）。

### 关键职责

- **加载 Vue 前端**：QWebEngineView 加载本地 HTTP 服务器托管的 Vue dist
- **读卡器集成**：ctypes 调 `OUR_IDR.dll` + `IDUSB.DLL`（CH372/CH375 芯片），后台线程读取 IC 卡卡号
- **窗口管理**：全屏无边框（运行模式）/ 1280×800 窗口（配置模式），动态切换无需重启
- **本地 HTTP 服务器**：双重职责（托管静态文件 + 处理 `/__api__/*` 端点），固定端口 15118，含 Origin/Referer 来源校验
- **单实例限制**：Windows 命名 Mutex，防止多开导致持久化目录锁冲突和数据丢失
- **配置管理**：`config.json`（`%APPDATA%\CanteenTerminal\`）支持 `//` 行注释，由 Python 端解析后去除
- **终端 token 加密存储**：dpapi.py 调 Windows DPAPI 加密 token 写 `token.bin`（`/__api__/token_save`、`/__api__/token_load`）
- **在线更新**：updater.py 后台检测 GitHub Releases（可叠加加速器），下载域名白名单 + sha256 强制校验，静默升级保留用户配置
- **守护进程**：watchdog.exe 随主程序安装并注册开机自启，每 15 秒巡检主进程，崩溃自动拉起；正常退出标记 `exit.flag` 防误拉起
- **崩溃自愈**：渲染进程崩溃自动重载；读卡器线程 30 秒心跳监控；网络连通性 60 秒诊断

### 不依赖 Python Shell 的能力（前端自行实现）

- 业务 API 调用（直接走后端 HTTP）
- IndexedDB 缓存（QWebEngineView 原生支持）
- SSE 长连接（QWebEngineView 原生 EventSource）
- 摄像头扫码（`navigator.mediaDevices.getUserMedia`）

---

## 2. 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Python 3.10（32 位，兼容 Win7 32 位） |
| GUI 框架 | PyQt5 |
| Web 渲染 | QWebEngineView（基于 Chromium 83，无需系统 WebView2） |
| 读卡器 SDK | OUR_IDR.dll + IDUSB.DLL（广州荣士电子，CH372/CH375 芯片） |
| HTTP 服务器 | Python 标准库 `http.server` + `socketserver` |
| 打包 | PyInstaller `--onedir`（绿色目录版） |
| 调用约定 | ctypes `WinDLL`（`__stdcall`） |

---

## 3. 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                      canteen-terminal.exe                     │
│                                                               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  TerminalWindow (QWidget)                              │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │  QWebEngineView (Vue 前端)                        │  │  │
│  │  │  ↑ runJavaScript(window.__onCardRead(cardNo))     │  │  │
│  │  │  ↓ fetch /__api__/xxx (前端调用 Python)           │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
│                              ↑↓                              │
│  ┌─────────────────────┐    ┌─────────────────────────────┐ │
│  │  本地 HTTP 服务器    │←──│  ShellBridge (QObject)       │ │
│  │  (127.0.0.1:15118)  │    │  - 处理 /__api__/* 请求      │ │
│  │  - 静态文件 (Vue)    │    │  - emit 信号 (跨线程安全)    │ │
│  │  - /__api__/* 端点   │    │  - 配置读写                  │ │
│  └─────────────────────┘    └─────────────────────────────┘ │
│                                       ↑                      │
│  ┌───────────────────────────────────┴──────────────────┐   │
│  │  CardReader (QObject, 后台线程)                       │   │
│  │  - 主:DLL 直读;次:读卡助手 HID 检测(8765)            │   │
│  │  - idr_read() 轮询读卡                                │   │
│  │  - 防抖 2 秒（可配置）                                │   │
│  │  - pyqtSignal card_read(str) → 推送给前端             │   │
│  │  - pyqtSignal status(str) → 调试日志（详见 §5.7）     │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
                          ↓ HTTPS
                  ┌────────────────┐
                  │  远程后端 API   │
                  │  (Spring Boot)  │
                  └────────────────┘
```

### 模块职责

| 模块 | 文件 | 职责 |
|------|------|------|
| 主入口 | [main.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/main.py) | QApplication 启动、窗口创建、信号连接、Mutex 单实例、QtWebEngine 配置、日志重定向、渲染进程崩溃恢复、守护定时器 |
| 配置 | [config.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/config.py) | `config.json`（%APPDATA%）读写、`//` 注释剥离、默认配置生成、旧位置迁移、set_config 取值校验 |
| HTTP 服务器 | [server.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/server.py) | 托管 Vue 静态文件、处理 `/__api__/*` 端点、SPA 路由回退、目录穿越防护（commonpath）、Origin/Referer 来源校验、GET/POST 方法约束 |
| Shell 桥接 | [bridge.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/bridge.py) | API 调用分发、pyqtSignal 跨线程通信、配置更新通知、exit.flag 退出标记 |
| 读卡器 | [card_reader.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/card_reader.py) | DLL 加载（主模式）、读卡助手 HID 回退（次级）、后台线程轮询、防抖、卡号解析、状态上报（status_info） |
| 在线更新 | [updater.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/updater.py) | 版本检测（GitHub Releases + 加速器）、下载域名白名单、sha256 校验、下载与静默安装 |
| 守护进程 | [watchdog.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/watchdog.py) | 独立 EXE，巡检主进程存活，崩溃拉起，识别 exit.flag 正常退出 |
| token 加密 | [dpapi.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/dpapi.py) | Windows DPAPI 加解密（token.bin） |

---

## 4. 单实例限制（Windows Mutex）

### 4.1 必要性

防止多开 EXE 导致：
1. **本地 HTTP 端口被占用**：端口固定为 15118 且不做 fallback，第二个实例绑定失败直接报错；若端口漂移，origin 从 `http://127.0.0.1:15118` 变为其他值，localStorage/IndexedDB 按 origin 隔离，导致**绑定配置和缓存数据全部"丢失"**
2. **QtWebEngine 持久化目录锁冲突**：`data/` 目录的 SQLite/LevelDB 文件被多实例争抢，报 `database is locked`，IndexedDB 写入失败
3. **读卡器 USB 设备争抢**：多个实例同时调用 DLL 读取同一 USB 设备

### 4.2 实现（main.py）

```python
MUTEX_NAME = 'Global\\CanteenTerminal_SingleInstance_v1'
mutex_handle = kernel32.CreateMutexW(None, False, MUTEX_NAME)
if kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
    user32.MessageBoxW(0, '企业智慧食堂终端已在运行，请勿重复打开...', '提示', 0x40)
    sys.exit(0)
_single_instance_mutex = mutex_handle  # 防止 GC 回收
```

- **命名 Mutex**：`Global\CanteenTerminal_SingleInstance_v1`，跨会话全局唯一
- **Mutex 生命周期**：保存到全局变量 `_single_instance_mutex`，防止被 GC 回收导致 Mutex 释放
- **错误码 183**（`ERROR_ALREADY_EXISTS`）：已有实例运行，弹框提示后退出

---

## 5. 读卡器集成（CardReader）

**主次级模式**（card_reader.py）：X86 终端为 32 位 Python 打包，`start()` 优先直接加载 32 位 OUR_IDR.dll 独占读卡器硬件（效率最高、最稳定，无需读卡助手、无需保持前台）；仅当 DLL 加载失败（驱动未装 / DLL 缺失 / 设备未连接）时回退到读卡助手 HID 键盘注入模式——读卡助手独占 DLL 并模拟键盘输入到前台窗口，卡号由前端 keydown 监听捕获，本模块仅轮询 `127.0.0.1:8765/status` 检测读卡助手在线状态（每 15 秒）。

### 5.1 SDK 来源

- **厂商**：广州荣士电子科技有限公司
- **DLL**：`OUR_IDR.dll`（主 SDK）+ `IDUSB.DLL`（依赖库）
- **支持芯片**：CH372/CH375/CH376（VID_4348&PID_5537 / VID_1A86&PID_5537 / VID_1A86&PID_5576 等）
- **打包位置**：通过 PyInstaller `binaries` 打包到 EXE 同目录

### 5.2 函数原型（`__stdcall` 调用约定）

| 函数 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `idr_read(serial*)` | `unsigned char*`（5 字节） | `unsigned char` | 循环读取卡号，第 1 字节厂商码 + 后 4 字节大端序卡序列号 |
| `idr_read_once(serial*)` | `unsigned char*`（5 字节） | `unsigned char` | 读一次（需拿开卡再放回才能再读） |
| `idr_beep(xms)` | `unsigned long` | `unsigned char` | 蜂鸣，`xms` 单位 2 毫秒 |
| `pcdgetdevicenumber(devnum*)` | `unsigned char*`（4 字节） | `unsigned char` | 读取 4 字节设备号 |

### 5.3 返回码

| 码 | 含义 |
|----|------|
| 0 | 操作成功 |
| 8 | 卡不在感应区（正常状态） |
| 21 | 没有动态库 |
| 22 | 动态库或驱动程序异常 |
| 23 | 驱动程序错误或读卡器尚未安装 |
| 24 | 操作超时 |
| 28 | USB 传输 CRC 校验错 |

### 5.4 卡号解析（`parse_card_number`）

```
原始数据（5 字节）：[厂商码, byte1, byte2, byte3, byte4]
                              └────── 大端序卡序列号 ──────┘

转换：后 4 字节大端序 → 十进制字符串

示例：
  03 00 83 AC 41 → 8629313
  69 00 DE F1 B2 → 14610866
```

与员工录入格式一致（管理后台录入员工时填写的卡号即为十进制形式）。

### 5.5 防抖机制

- **间隔**：`config.json` 默认 `2.0` 秒（`config.py` 的 `DEFAULT_CARD_INTERVAL = 2.0`）；`card_reader.py` 的 `CardReader.__init__` 形参默认 `1.5` 秒。**运行时由 `config.json` 覆盖**（`main.py` 启动时用 `cfg['card_interval']` 传入 `CardReader`）。
- **规则**：同一卡号在 `_card_interval` 秒内不重复触发
- **动态生效**：`set_interval(seconds)` 由配置页修改后通过 `config_updated` 信号立即调用，无需重启读卡器
- **最小值**：0.5 秒（`max(0.5, float(card_interval))`）

### 5.6 后台线程流程（`_read_loop`）

```
1. idr_beep(38) 蜂鸣 76ms 确认读卡器连接
2. pcdgetdevicenumber 读取设备号确认通信正常
3. 循环：
   - idr_read(card_buf) 读取卡号
   - ret == 0：解析卡号，防抖检查，蜂鸣提示，emit card_read 信号；error_count 重置为 0
   - ret == 8：卡不在感应区（正常），error_count 重置为 0，继续轮询
   - ret == 22/23/24：设备异常，sleep 2s 避免日志刷屏
   - 其他错误：sleep 0.5s
   - 异常错误日志限流：error_count > 3 后不再打印（仅前 3 次才打印 `读卡异常` 日志）
   - 正常轮询间隔：100ms
4. stop() 时 _running = False，线程退出（emit status "读卡器线程退出"）
```

### 5.7 CardReader 信号与重启

**信号**（均跨线程安全，由 Qt 自动转发到主线程）：

| 信号 | 参数 | 触发场景 |
|------|------|----------|
| `card_read` | `str` | 读到卡号（已防抖、十进制格式） |
| `status` | `str` | 状态/调试信息（DLL 加载、蜂鸣、设备号、错误等） |

> **注意**：§3 架构图与 §5.9（卡号推送到前端）仅提及 `card_read`，实际 `CardReader` 还会 emit `status` 信号。`main.py` 中 `card_reader.status.connect(lambda msg: print(f'[CardReader] {msg}'))` 将状态信息打印到 stdout，便于调试。

**`restart()` 实现**（前端设置页可调用，对应 `/__api__/restart_card_reader`）：

```
stop()          # _running = False, join(timeout=2), _dll = None
sleep(0.5)      # 间隔 0.5 秒，确保 USB 设备释放
start()         # 重新加载 DLL + 启动后台线程（DLL 仍失败则再次落回 HID 模式）
return self._running
```

**`status_info()`**（对应 `/__api__/device_status` 端点，前端设置页设备状态卡展示）：按当前模式返回 `running / dll_loaded / connected / driver_ok / mode('DLL'|'HID') / interval / last_ret / last_ret_desc / helper_online / helper_port`。DLL 模式 `dll_loaded=True`（已加载 DLL 即视为驱动正常）；HID 模式实时检测读卡助手在线状态作为 `connected`。

### 5.8 DLL 加载顺序

1. **PyInstaller 临时目录**（`sys._MEIPASS`，打包时内嵌）
2. **EXE 同目录**（外部放置，便于升级 DLL 而不重打 EXE）
3. **开发模式**：`src-python/` 目录

加载 `OUR_IDR.dll` 前先加载 `IDUSB.DLL`（依赖库），并把 DLL 目录加入 `os.add_dll_directory` 和 `PATH` 环境变量。

### 5.9 卡号推送到前端

```python
def push_card_to_frontend(page, card_no):
    safe_card = card_no.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
    js = f"window.__onCardRead && window.__onCardRead('{safe_card}');"
    page.runJavaScript(js)
```

前端 `useCardReader.ts` 注册 `window.__onCardRead` 全局函数，Python 通过 `page.runJavaScript` 推送卡号。

### 5.10 次级模式：读卡助手 HID 回退（`_start_helper_mode`）

- DLL 加载失败时进入 HID 模式：`_mode = 'HID'`，卡号由前端 keydown 直接捕获（读卡助手模拟键盘注入），Python 侧不接收卡号
- `_helper_monitor_loop` 后台线程每 15 秒请求 `http://127.0.0.1:8765/status` 检测读卡助手在线状态，掉线/恢复通过 `status` 信号通知
- 双方均不可用（DLL 失败且读卡助手离线）时输出状态提示「请检查驱动或启动读卡助手」

---

## 6. 本地 HTTP 服务器（server.py）

### 6.1 端口策略

**固定端口 15118，无 fallback**，保证 origin（`http://127.0.0.1:15118`）绝对稳定：

- 端口被占（异常残留实例）时直接抛 `RuntimeError`，提示通过任务管理器结束 `canteen-terminal.exe` 后重试，**不换端口**（换端口会导致 localStorage/IndexedDB 缓存全丢）
- 选址依据：1287 等低位端口可能落入 Windows 动态端口保留段（Docker Desktop/WSL2/Hyper-V 会动态保留大段端口，普通应用绑定报 WinError 10013），15118 属 49152+ 高位冷门端口，彻底规避
- 单实例 Mutex 已保证不会有两个终端进程同时运行
- 服务器使用 `ThreadingTCPServer` 支持并发请求；`allow_reuse_address = True` 作为**类属性**在实例化前设置（异常退出后 TIME_WAIT 期间也能立即重新绑定）

### 6.2 双重职责

1. **静态文件托管**：serve Vue dist 目录，SPA 路由回退到 `index.html`
2. **API 端点**：`/__api__/*` 转发给 ShellBridge 处理

### 6.3 静态文件安全

- **目录穿越防护**：`os.path.normpath` 后用 `os.path.commonpath` 检查路径边界（不在 `web_dir` 内返回 403；Windows 跨盘符时 `commonpath` 抛 `ValueError`，同样返回 403）。已修复早期 `startswith` 前缀检查不校验路径分隔符边界的缺陷。
- **MIME 类型**：根据扩展名设置 `Content-Type`（html/js/css/json/png/jpg/jpeg/gif/svg/ico/woff/woff2/ttf）
- **SPA 回退**：文件不存在时返回 `index.html`（Vue Router 接管路由）

### 6.4 Vue dist 查找顺序

1. **PyInstaller 临时目录**（`_MEIPASS/web`，打包时内嵌）
2. **EXE 同目录的 `web/` 目录**（外部放置，便于前端热更新）
3. **开发模式**：`../terminal/dist`

### 6.5 API 请求来源校验（`_check_origin`）

以请求头 `Host` 构造唯一允许的 origin（`http://{host}`，如 `http://127.0.0.1:15118`）：

1. `Origin` 存在时必须**精确等于** `http://{host}`，否则 403
2. `Referer` 存在时必须等于 `http://{host}` 或以 `http://{host}/` 开头，否则 403
3. **状态变更方法**（不在 `READ_ONLY_METHODS`，如 set_config/quit/restart_card_reader/token_save）要求 Origin/Referer **至少提供其一**，两者都缺失直接 403（防跨站裸 POST/表单提交）
4. **GET 只读方法**（`READ_ONLY_METHODS = {server_url, config, token_load}`）两者都缺失时放行（curl 等本地调试可用），但只要提供了就必须精确匹配
5. 响应**不设置** `Access-Control-Allow-Origin`，仅同源页面可访问（防止任意网站跨域 CSRF 本地 API）
6. **方法约束**：`do_GET` 仅放行只读端点，访问其余端点返回 405（提示使用 POST）；`do_POST` 处理状态变更端点

---

## 7. ShellBridge（bridge.py）

### 7.1 信号（跨线程安全）

| 信号 | 触发场景 | 槽函数（main.py） |
|------|----------|-------------------|
| `switch_to_config_requested` | 前端请求切换到配置模式 | `window.switch_to_config_mode` |
| `switch_to_fullscreen_requested` | 前端请求切换到全屏模式 | `window.switch_to_fullscreen_mode` |
| `quit_requested` | 前端请求退出应用（quit 端点先写 exit.flag） | `QApplication.quit` |
| `config_updated(dict)` | 配置已更新 | `on_config_updated`（动态应用运行时参数） |

**关键**：Qt 窗口操作必须在主线程执行。HTTP 服务器在后台线程，通过 `pyqtSignal` 跨线程安全调用主线程槽函数。

> 历史遗留的 `eval_js_requested` 信号与 `/__api__/eval_js` 调试后门**已移除**（原存在 RCE 风险）。`bridge.py` 模块 docstring 的端点列表已与实际实现同步。

### 7.2 API 端点

| 方法 | 路径 | 请求体 | 返回 | 说明 |
|------|------|--------|------|------|
| GET | `/__api__/server_url` | - | `{ok, server_url}` | 获取预设服务器地址（只读） |
| GET | `/__api__/config` | - | `{ok, config}` | 获取完整配置（只读） |
| GET | `/__api__/token_load` | - | `{ok, token}` | 读取 DPAPI 加密存储的终端 token（只读；文件不存在/解密失败返回 token=null） |
| POST | `/__api__/set_config` | `{window_mode?, card_interval?, idle_timeout?, server_url?, update_check_url?}` | `{ok, updated}` | 更新配置（字段白名单在 `bridge.handle_api`，类型 + 取值校验见 §10.3） |
| POST | `/__api__/token_save` | `{token}` | `{ok}` | DPAPI 加密保存终端 token 到 `%APPDATA%\CanteenTerminal\token.bin`（空串即清除） |
| POST | `/__api__/switch_to_config` | - | `{ok}` | 切换到窗口模式 |
| POST | `/__api__/switch_to_fullscreen` | - | `{ok}` | 切换到全屏模式 |
| POST | `/__api__/quit` | - | `{ok}` | 写入正常退出标记 exit.flag 后退出应用（watchdog 检测到标记不再拉起） |
| POST | `/__api__/restart_card_reader` | - | `{ok, running}` | 重启读卡器 |
| POST | `/__api__/device_status` | - | `{ok, card_reader}` | 读卡器状态（`status_info()`，DLL/HID 模式、连接状态、最近返回码） |

> **安全现状**（详见 §6.5/§13）：
> - **方法已强制**：`do_GET` 仅放行只读端点（server_url/config/token_load），其余返回 405；状态变更端点仅接受 POST。
> - **来源已校验**：状态变更端点要求 Origin/Referer 至少其一且与 Host 精确匹配，否则 403。
> - **`eval_js` 已移除**：原可在终端执行任意 JS 的调试后门已删除。

### 7.3 配置更新流程

```
前端 POST /__api__/set_config {window_mode: "windowed"}
       ↓
ShellBridge.handle_api('set_config', body)
       ↓
write_config(updates)  # 写入 config.json
       ↓
emit config_updated(updates)  # 通知 main.py
       ↓
main.py.on_config_updated:
  - card_interval 变更 → card_reader.set_interval()
  - window_mode == "fullscreen" → window.switch_to_fullscreen_mode()
  - window_mode == "windowed" → window.switch_to_config_mode()
```

**即"进入运行模式"时立即生效**，无需重启 EXE。

---

## 8. QtWebEngine 配置

### 8.1 Chromium Flags

```
--no-sandbox                    # 必须禁用沙箱，否则 IndexedDB 写入失败
--disable-gpu-sandbox           # 禁用 GPU 沙箱（虚拟机/终端驱动问题）
--disable-software-rasterizer   # 禁用软件光栅化
--enable-media-stream           # 允许 getUserMedia（摄像头扫码）
--use-fake-ui-for-media-stream  # 自动应答媒体权限提示（配合 main.py 的 featurePermissionRequested 自动授予摄像头权限）
```

**`--no-sandbox` 是必须的**：渲染器进程的文件系统沙箱会阻止 IndexedDB 写入元数据（报 `indexed_db_backing_store.cc SET_UP_METADATA` 错误），导致菜品图片缓存、头像缓存全部失效。

### 8.2 持久化目录

```python
data_dir = os.path.join(get_local_appdata_dir(), 'data')   # %LOCALAPPDATA%\CanteenTerminal\data
profile.setPersistentStoragePath(data_dir)
profile.setPersistentCookiesPolicy(QWebEngineProfile.AllowPersistentCookies)
profile.setHttpCacheType(QWebEngineProfile.NoCache)  # 禁用 HTTP 缓存
```

- **持久化路径**：`%LOCALAPPDATA%\CanteenTerminal\data`（安装版 EXE 在 Program Files 下只读，QtWebEngine 的 Network Service 子进程无法写入会导致 NetworkError），存储 localStorage（终端绑定状态）和 IndexedDB（菜品/菜单/员工/头像缓存）
- **页面背景深色**：`page.setBackgroundColor(QColor('#0e1115'))`，与前端 `.app-root` 同色，消除页面加载前的白闪
- **禁用 HTTP 缓存**：每次启动从本地服务器重新加载前端资源（避免重新打包后加载旧 `index.html` 引用不存在的旧哈希 JS 文件）
- **锁文件清理**：启动时清理残留的 `-journal`/`-wal`/`-shm`/`LOCK` 文件，避免上次崩溃导致的 `database is locked` 死锁
- **日志文件**：`%LOCALAPPDATA%\CanteenTerminal\terminal.log`（TeeLogger 同时写 stdout 与文件，每次启动覆盖；全局异常钩子 `_excepthook` 将未捕获异常写入日志）

### 8.3 中文路径问题（PyInstaller onedir）

QtWebEngine 内部使用 ANSI API 读取路径，无法处理含中文的路径（如 `D:\文档\...`），报 `resources not found` 和 `Couldn't mmap icu data file`。

**解决方案**（必须在导入 `PyQt5.QtWebEngineWidgets` 之前执行）：

1. **仅在打包模式下生效**：`main.py` 用 `if getattr(sys, 'frozen', False) and sys.platform == 'win32':` 包裹短路径处理逻辑；开发模式下（`sys.frozen` 不存在）直接跳过，避免影响调试。
2. 用 `GetShortPathName` 将长路径转换为 Windows 短路径名（8.3 格式），消除中文/空格
3. 设置环境变量：
   - `QTWEBENGINEPROCESS_PATH`：`QtWebEngineProcess.exe` 路径
   - `QTWEBENGINE_RESOURCES_PATH`：`resources/` 目录（含 `icudtl.dat` + `.pak`）
   - `QTWEBENGINE_LOCALES_PATH`：`translations/qtwebengine_locales/` 目录
4. 写 `qt.conf` 文件：
   - `bin/qt.conf`：`Prefix = ..`（因为 `QtWebEngineProcess.exe` 在 `bin/` 下，而 `resources/` 在 `bin/` 的父目录 `Qt5/` 下）
   - `EXE 同目录/qt.conf`：完整路径配置

> **注意**：`main.py` 使用显式导入 `from PyQt5.QtWebEngineWidgets import QWebEngineView, QWebEnginePage, QWebEngineProfile, QWebEngineScript`（而非 `import *`），上述环境变量/`qt.conf` 设置仍需在该 import 之前完成。

### 8.4 `__pythonShell` 标记注入

```python
marker_script = QWebEngineScript()
marker_script.setSourceCode('window.__pythonShell = true;')
marker_script.setInjectionPoint(QWebEngineScript.DocumentCreation)
marker_script.setWorldId(QWebEngineScript.MainWorld)
marker_script.setName('python-shell-marker')
marker_script.setRunsOnSubFrames(False)  # 仅主框架注入，子框架不重复
profile.scripts().insert(marker_script)
```

**必须在 `DocumentCreation` 阶段注入**（早于 Vue `onMounted`），否则 `detectShell()` 返回 `'browser'`，导致：
- Settings 运行设置卡不显示
- `switchToConfigMode`/`quitApp` 走 browser 分支失效

`loadFinished` 信号触发时 Vue 早已挂载，太晚了。

> 早期的冗余 `loadFinished` 注入已移除；`loadFinished` 现仅用于打印加载完成日志（`[WebEngine] 加载完成: ok=...`）。

---

## 9. 窗口管理（TerminalWindow）

### 9.1 两种模式

| 模式 | 启动方式 | 标题栏 | 尺寸 | 用途 |
|------|----------|--------|------|------|
| 全屏无边框 | `Qt.FramelessWindowHint` + `showFullScreen()` | 无 | 全屏 | 运行模式（默认） |
| 窗口模式 | `Qt.Window` + `showNormal()` | 有 | 1280×800，可调整 | 配置模式 / 调试 |

### 9.2 动态切换（幂等）

```python
def switch_to_config_mode(self):
    if not self._is_fullscreen:  # 幂等：已是窗口模式则跳过
        return
    self._is_fullscreen = False
    self.setWindowFlags(Qt.Window)  # 恢复标题栏
    self.resize(1280, 800)
    self.move(100, 100)
    self.showNormal()
    self.activateWindow()
    self.raise_()
```

**关键**：`setWindowFlags` 对可见窗口会自动销毁并重建原生窗口，重建后保持可见。**不要手动 `hide()`**，否则 `showNormal()` 可能无法恢复。

### 9.3 键盘退出快捷键

| 快捷键 | 动作 |
|--------|------|
| `Alt+F4` | 退出应用 |
| `Ctrl+Shift+Q` | 退出应用 |

### 9.4 JavaScript 控制台日志

`FullscreenWebPage.javaScriptConsoleMessage` 重写，将前端 `console.log/warn/error/info` 打印到 Python stdout（含来源文件与行号），方便调试。

### 9.5 渲染进程崩溃自动恢复

`page.renderProcessTerminated` 触发时打印崩溃类型（Normal/Abnormal/Crashed/Killed）与退出码，3 秒后 `setUrl` 重新加载页面（重建渲染进程，localStorage/IndexedDB 持久化数据不受影响）；恢复失败则 5 秒后重试。保证终端 7×24 运行下 QtWebEngineProcess.exe 异常退出不致白屏。

---

## 10. config.json 配置

### 10.1 字段说明

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `server_url` | string | `"https://canteen.908521.xyz"` | 预设后端服务器地址，绑定页面自动填入；留空则要求手动输入。**不要带末尾斜杠 `/`，不要带 `/api` 后缀** |
| `window_mode` | string | `"fullscreen"` | `"fullscreen"`（全屏无边框）或 `"windowed"`（1280×800 窗口） |
| `card_interval` | float | `2.0` | 读卡防抖间隔（秒），推荐 1.0~3.0 |
| `idle_timeout` | int | `30` | 无操作自动返回待机页时间（秒），0=永不。**注意：超时逻辑实际由前端实现**，Python 端仅在 `config.py` 中存取该值并通过 `/__api__/config` 返回给前端，自身不参与计时。 |
| `update_check_url` | string | `""` | 在线更新检测地址；留空则依次尝试默认 GitHub Releases API + 各加速器前缀（gh-proxy.com / ghp.keleyaa.com / g.blfrp.cn / gh.llkk.cc / ghpxy.hwinzniej.top / 直连） |
| `ignored_version` | string | `""` | 用户「忽略此版本」记录的版本号；远端版本等于该值时不再弹窗（直到出现更新的版本） |

> **注意**：管理员密码验证由后端 `/api/admin/login` 接口完成（BCrypt），**config.json 中无 `admin_password_hash` 字段**。

### 10.2 `//` 行注释支持

`config.json` 支持 `//` 行注释，由 `config.py.strip_json_comments` 解析时去除：

- 只去除**不在字符串内**的 `//` 注释
- 通过状态机遍历字符（`in_string` + `escape` 标志），跳过字符串内的 `//`
- 首次运行时自动生成带完整注释的默认配置（`DEFAULT_CONFIG_JSON`）

### 10.3 配置读写

| 函数 | 说明 |
|------|------|
| `ensure_config_json()` | 确保文件存在于 `%APPDATA%\CanteenTerminal\`，首次运行生成默认配置（含注释）；`migrate_legacy_config()` 会把旧位置（EXE 同目录）的 config.json 迁移一次 |
| `read_config()` | 读取 `server_url`（向后兼容） |
| `read_full_config()` | 读取完整配置，缺失字段用默认值填充，类型校验 |
| `write_config(updates)` | 部分更新，字段白名单为 `server_url`/`window_mode`/`card_interval`/`idle_timeout`/`update_check_url`/`ignored_version` |
| `validate_config_value(key, value)` | set_config 写入前的**逐项取值校验**（由 `bridge.handle_api` 的 `set_config` 分支调用）：URL 类必须 `http(s)://` 开头（允许留空）；`window_mode` 枚举仅 fullscreen/windowed；`card_interval`/`idle_timeout` 数值 0~5000（排除 bool）；非法返回中文错误信息并拒绝写入 |

**注意**：`write_config` 会丢失注释（`json.dump` 不保留注释），但功能正常。如需保留注释，需手动编辑 `config.json`。

### 10.4 默认配置示例

```jsonc
{
  // 预设后端服务器地址(绑定页面自动填入;留空("")则要求手动输入)
  // 注意:不要带末尾斜杠 /,不要带 /api 后缀
  "server_url": "https://canteen.908521.xyz",

  // 窗口模式: "fullscreen" / "windowed"
  // 在配置页"进入运行模式"时动态生效(无需重启)
  "window_mode": "fullscreen",

  // 读卡防抖间隔(秒),推荐 1.0 ~ 3.0
  "card_interval": 2.0,

  // 无操作自动返回待机页时间(秒),0=永不
  "idle_timeout": 30,

  // 在线更新检测地址(留空则用默认 GitHub Releases + 加速器)
  "update_check_url": "",

  // 用户选择"忽略此版本"后记录的版本号
  "ignored_version": ""
}
```

---

## 11. PyInstaller 打包

### 11.1 打包命令

```bash
# 使用 32 位 Python（兼容 Win7 32 位）
C:\Python310-32\python.exe -m PyInstaller canteen-terminal.spec --clean --noconfirm
```

**`canteen-terminal.spec` 关键配置**：

- `console=False`：正式部署不显示控制台窗口；日志通过 TeeLogger 写入 `%LOCALAPPDATA%\CanteenTerminal\terminal.log`（调试时查看该文件）。
- `icon='terminal_icon.ico'`：餐碗+蒸汽主题程序图标（读卡助手仍用 `icon.ico` 区分）。
- `excludes=['tkinter', 'unittest', 'pydoc']`：排除不需要的大模块，减小体积（注意 `email`/`xml` 不能排除，`http.server` 依赖它们）。
- `upx=True` + `upx_exclude`：详见 §11.4。
- **双 EXE 构建**：spec 同时构建 `canteen-terminal.exe`（主程序）与 `watchdog.exe`（守护进程，仅依赖标准库、排除 PyQt5），共享同一 onedir 目录。
- **datas 额外打包**：`../VERSIONS.json`（当前版本号来源，见 §15）与 `drivers/`（CH375 读卡器驱动，安装时由 Inno Setup 调用 pnputil 安装）。

### 11.2 打包模式：`--onedir`（绿色目录版）

**必须用 `--onedir`，不能用 `--onefile`**：

| 模式 | 问题 |
|------|------|
| `--onefile`（单文件） | QtWebEngineProcess 子进程与主进程争抢持久化目录的 QuotaManager SQLite 锁，报 `database is locked (errno 33)`，IndexedDB 无法写入（菜品图片缓存失效） |
| `--onedir`（目录版） | 所有文件在 EXE 同目录，QtWebEngineProcess 能正确访问持久化目录，无锁冲突。后续可用 Inno Setup / NSIS 打包为安装包 |

### 11.3 打包内容

| 内容 | 来源 | 目标位置 |
|------|------|----------|
| Python 代码 | `main.py` + `config.py` + `server.py` + `bridge.py` + `card_reader.py` + `updater.py` + `dpapi.py` | EXE 主程序 |
| 守护进程 | `watchdog.py` | `watchdog.exe`（与主程序同目录） |
| Vue 前端 | `../terminal/dist` | `web/` |
| 读卡器 DLL | `OUR_IDR.dll` + `IDUSB.DLL` | `_internal/`（PyInstaller binaries 落点） |
| 版本清单 | `../VERSIONS.json` | EXE 同目录（`_internal/VERSIONS.json`） |
| CH375 驱动 | `drivers/` | `_internal/drivers/` |
| QtWebEngine 资源 | `PyQt5/Qt5/resources/` | `PyQt5/Qt5/resources/` |
| QtWebEngine 翻译 | `PyQt5/Qt5/translations/qtwebengine_locales/` | `PyQt5/Qt5/translations/qtwebengine_locales/` |

### 11.4 UPX 压缩排除

以下文件**不压缩**（避免启动慢或损坏）：
- `QtWebEngineProcess.exe`
- `OUR_IDR.dll`
- `IDUSB.DLL`

### 11.5 产物结构

```
dist/canteen-terminal/
├── canteen-terminal.exe          # 主程序
├── watchdog.exe                  # 守护进程（安装后注册开机自启）
├── _internal/                    # 依赖（PyQt5、Python 运行时、OUR_IDR.dll/IDUSB.DLL、
│   │                              #   VERSIONS.json、drivers/ 驱动等）
│   └── PyQt5/Qt5/
│       ├── bin/QtWebEngineProcess.exe
│       ├── resources/            # icudtl.dat + .pak
│       └── translations/qtwebengine_locales/
└── web/                          # Vue 前端 dist
```

> **用户数据不在安装目录**：`config.json` 与 `token.bin`、`exit.flag` 位于 `%APPDATA%\CanteenTerminal\`，QtWebEngine 持久化数据位于 `%LOCALAPPDATA%\CanteenTerminal\data\`，日志（terminal.log/watchdog.log）位于 `%LOCALAPPDATA%\CanteenTerminal\`。安装版 EXE 目录（Program Files）只读，升级/重装不影响用户配置（installer.iss 的 BackupUserConfig/RestoreUserConfig 亦在升级安装前后备份恢复）。

---

## 12. 启动流程

> **重要**：日志重定向（TeeLogger + 全局异常钩子）、QtWebEngine 短路径/环境变量设置（main.py 模块级代码，仅 `sys.frozen` 下执行）和 Mutex 单实例检查（main.py 模块级代码）均先于 `main()` 函数体执行，且都早于 `from PyQt5.QtWebEngineWidgets import ...`。

```
1. 日志重定向（模块级，win32 下执行）
   └─ TeeLogger 同时写 stdout 与 %LOCALAPPDATA%\CanteenTerminal\terminal.log（每次启动覆盖）
   └─ sys.excepthook 将未捕获异常写入日志

2. QtWebEngine 短路径 + 环境变量（main.py 模块级，仅 sys.frozen 下执行）
   └─ GetShortPathName 转换 EXE 目录为 8.3 短路径（消除中文）
   └─ 设置 QTWEBENGINEPROCESS_PATH / QTWEBENGINE_RESOURCES_PATH / QTWEBENGINE_LOCALES_PATH
   └─ 设置 QTWEBENGINE_CHROMIUM_FLAGS（--no-sandbox --disable-gpu-sandbox --disable-software-rasterizer
      --enable-media-stream --use-fake-ui-for-media-stream）
   └─ 写 bin/qt.conf（Prefix=..）和 EXE 同目录/qt.conf

3. 单实例检查（Windows Mutex，main.py 模块级）
   └─ 已有实例运行 → 弹框提示 → 退出

4. 显式导入 PyQt5.QtWebEngineWidgets（必须在前两步之后）

5. 进入 main()：
   └─ stdout/stderr reconfigure(line_buffering=True) 行缓冲，确保 print 立即输出
   └─ 环境诊断打印（EXE 路径 / 配置目录 / 数据目录 / Python 版本 / 是否管理员）
   └─ ensure_config_json（%APPDATA%，首启生成默认配置并迁移旧位置）
   └─ read_full_config（window_mode / card_interval / idle_timeout / server_url / update_check_url / ignored_version）
   └─ find_web_dist（未找到 → 提示 → 退出）

6. 创建 QApplication

7. 配置 QtWebEngine 持久化目录（%LOCALAPPDATA%\CanteenTerminal\data）
   └─ 清理残留锁文件（-journal/-wal/-shm/LOCK）
   └─ 禁用 HTTP 缓存（NoCache）
   └─ 注入 __pythonShell 标记（QWebEngineScript, DocumentCreation, setRunsOnSubFrames(False)）

8. 创建读卡器（CardReader，传入 card_interval；连接 status 信号 → 打印日志）

9. 创建 ShellBridge（注入 card_reader 与 read_config）

10. 启动 HTTP 服务器（serve Vue dist + API 端点）
    └─ 固定端口 15118，无 fallback，绑定失败直接 RuntimeError
    └─ ThreadingTCPServer（allow_reuse_address 类属性）在 daemon 线程运行 serve_forever

11. 创建主窗口（根据 window_mode 决定全屏/窗口；页面背景深色 #0e1115）
    └─ renderProcessTerminated → 3 秒后自动重载（渲染进程崩溃恢复）

12. 连接 bridge 信号到窗口操作
    └─ switch_to_config_requested → window.switch_to_config_mode
    └─ switch_to_fullscreen_requested → window.switch_to_fullscreen_mode
    └─ quit_requested → QApplication.quit（quit 端点先写 exit.flag）
    └─ config_updated → 动态应用运行时参数

13. 连接读卡器信号 → 推送卡号到前端
    └─ card_reader.card_read → push_card_to_frontend

14. 启动在线更新检测（start_update_check，后台线程，检测到新版弹窗）

15. 延迟 2 秒启动读卡器（确保前端已就绪）
    └─ QTimer.singleShot(2000, card_reader.start)

16. 启动守护定时器
    └─ 读卡器心跳（每 30 秒，线程异常退出自动重启）
    └─ 网络连通性（每 60 秒，后台线程 ping /api/system/info，仅诊断日志）

17. 进入 Qt 事件循环（app.exec_）

18. 退出时清理
    └─ card_reader.stop()
    └─ server.shutdown()
```

---

## 13. 安全边界

| 边界 | 策略 |
|------|------|
| 单实例 | Windows 命名 Mutex（`Global\CanteenTerminal_SingleInstance_v1`），防止多开导致数据丢失 |
| HTTP 服务器 | 仅绑定 `127.0.0.1`，不对外暴露；固定端口 15118 保证 origin 稳定；ThreadingTCPServer 运行在 daemon 线程（allow_reuse_address 类属性） |
| 本地 API 来源校验 | Origin/Referer 须与 Host 精确匹配；状态变更端点缺 Origin/Referer 直接 403；GET 仅放行只读端点（其余 405）；响应不设 ACAO（详见 §6.5） |
| 静态文件 | 目录穿越防护（`normpath` + `commonpath` 边界检查，跨盘符 ValueError 亦拒绝） |
| 配置文件 | `//` 注释剥离时跳过字符串内容；字段白名单在 `bridge.handle_api`；类型 + 取值校验（URL 格式/数值范围/枚举）在 `config.validate_config_value` 逐项调用；写回时不含注释 |
| 管理员密码 | **不存储在本地**，由后端 `/api/admin/login` 验证（BCrypt） |
| 读卡器 DLL | 优先从 PyInstaller 临时目录加载（打包时内嵌），避免外部替换 |
| Chromium 沙箱 | `--no-sandbox`（必须，否则 IndexedDB 写入失败） |
| HTTP 缓存 | 禁用（`NoCache`），避免重新打包后加载旧前端资源 |
| 持久化目录 | `%LOCALAPPDATA%\CanteenTerminal\data`（config.json/token.bin/exit.flag 在 `%APPDATA%\CanteenTerminal`），与安装目录分离，升级不丢 |
| 卡号推送 | `runJavaScript` 前转义特殊字符（`\\`、`'`、`"`），防止注入 |
| 终端 token | DPAPI 加密（绑定当前 Windows 用户）存 `token.bin`，localStorage 仅兜底 |
| 在线更新 | 下载域名白名单（https + host 精确/子域名）；版本清单提供 sha256 时强制校验，不匹配删除文件拒绝执行 |
| eval_js | 调试后门已移除 |

### 13.1 安全加固记录（已修复）

以下早期风险点均已修复：

- **CORS `ACAO:*` 已移除**：JSON API 响应不再设置 `Access-Control-Allow-Origin`，仅同源页面可访问。
- **本地 API 来源校验**：`_check_origin` 以 Host 构造唯一允许 origin，Origin 精确匹配 + Referer 同源前缀；状态变更端点缺 Origin/Referer 直接 403（防跨站裸 POST）。
- **`eval_js` RCE 后门已移除**：端点与 `eval_js_requested` 信号均已删除。
- **HTTP 方法已强制**：GET 仅放行只读端点（server_url/config/token_load），其余 405。
- **目录穿越边界缺陷已修复**：改用 `os.path.commonpath` 检查路径边界。
- **`allow_reuse_address` 生效**：改为 `ReusableServer` 类属性，在套接字绑定前设置。

---

## 14. 故障排查

### 14.1 IndexedDB 写入失败

**症状**：菜品图片/头像缓存不生效，控制台报 `indexed_db_backing_store.cc SET_UP_METADATA`

**原因**：Chromium 沙箱阻止渲染器进程写入文件系统

**解决**：确保 `QTWEBENGINE_CHROMIUM_FLAGS` 包含 `--no-sandbox`（已在 main.py 中设置）

### 14.2 `database is locked`

**症状**：QtWebEngine 持久化目录的 SQLite/LevelDB 文件锁冲突

**原因**：
- 多开 EXE（已由 Mutex 解决）
- 上次崩溃/异常退出残留锁文件

**解决**：启动时自动清理 `data/` 下的 `-journal`/`-wal`/`-shm`/`LOCK` 文件（已在 main.py 中实现）

### 14.3 `resources not found` / `Couldn't mmap icu data file`

**症状**：QtWebEngine 启动失败，network service 崩溃

**原因**：中文路径导致 QtWebEngine 的 ANSI API 截断为 `??`

**解决**：
1. 用 `GetShortPathName` 转换为短路径
2. 设置 `QTWEBENGINE_RESOURCES_PATH` 等环境变量
3. 写 `qt.conf` 文件（`Prefix = ..`）

### 14.4 前端 `__pythonShell` 未定义

**症状**：Settings 运行设置卡不显示，`switchToConfigMode` 走 browser 分支失效

**原因**：`__pythonShell` 注入时机太晚（Vue 已挂载）

**解决**：用 `QWebEngineScript` 在 `DocumentCreation` 阶段注入（而非 `loadFinished` 信号）

### 14.5 读卡器不工作

**症状**：刷卡无反应

**排查**：
1. 检查 `OUR_IDR.dll` + `IDUSB.DLL` 是否随程序打包（onedir 模式位于 `_internal/`）
2. 检查设备管理器中 USB 设备是否识别
3. 安装 CH375 驱动（安装包内置，`drivers/` 目录）
4. DLL 加载失败会自动回退读卡助手 HID 模式——需读卡助手在线（127.0.0.1:8765），卡号改由前端 keydown 捕获
5. 查看日志 `%LOCALAPPDATA%\CanteenTerminal\terminal.log` 的 `[CardReader]` 输出

### 14.6 窗口切换后消失

**症状**：`setWindowFlags` 后窗口不可见

**原因**：手动 `hide()` 后 `showNormal()` 未恢复

**解决**：不要手动 `hide()`，`setWindowFlags` 会自动重建原生窗口并保持可见

### 14.7 端口释放后仍报 `Address already in use`

**症状**：上次异常退出后，重启时 15118 端口无法立即绑定

**原因（历史）**：早期版本在 `TCPServer` 实例创建之后才设置 `allow_reuse_address`，该设置是 no-op（无效）。

**解决（已修复）**：现定义为 `ReusableServer(socketserver.ThreadingTCPServer)` 的**类属性** `allow_reuse_address = True`，在套接字绑定前生效，TIME_WAIT 期间也能立即重新绑定。若端口仍被占（残留进程），启动直接报 RuntimeError（不换端口），按提示结束 `canteen-terminal.exe` 后重试。

---

## 15. 在线更新（updater.py）

- **版本检测**：启动时后台线程调 `check_for_update`——读 GitHub Releases latest（`update_check_url` 配置优先，否则依次尝试加速器前缀 gh-proxy.com / ghp.keleyaa.com / g.blfrp.cn / gh.llkk.cc / ghpxy.hwinzniej.top / 直连）；远端版本号大于当前版本且不等于 `ignored_version` 时弹窗（下载更新 / 取消 / 忽略此版本）
- **当前版本号**：`resolve_current_version()` 优先读随 EXE 发布的 `VERSIONS.json` 的 `terminal.version` 字段（EXE 同目录 / `_internal/`，开发模式读仓库根），文件缺失才回退内置常量 `CURRENT_VERSION`（当前 1.0.28）
- **下载安全**：候选地址（原始 + 加速器叠加）逐个校验 `_is_allowed_download_url`——必须 https 且 host 在白名单 `ALLOWED_DOWNLOAD_HOSTS`（github.com / objects.githubusercontent.com / gh-proxy.com / ghp.keleyaa.com / g.blfrp.cn / gh.llkk.cc / ghpxy.hwinzniej.top，含子域名），否则拒绝；版本清单（release 资产 sha256/digest 字段）提供 sha256 时下载后**强制校验**，不匹配删除文件抛异常拒绝安装（未提供时记录 warning 继续下载）
- **静默安装**：下载到 `%LOCALAPPDATA%\CanteenTerminal\updates\`，`run_installer` 以 `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART` 启动安装包并退出本程序；installer.iss 在升级安装前 `BackupUserConfig`、安装后 `RestoreUserConfig`，保留 `%APPDATA%` 用户配置

## 16. 守护进程（watchdog.py → watchdog.exe）

- **部署**：随主程序一并打包（共享 onedir 目录），installer.iss 注册开机自启（`{commonstartup}` 快捷方式指向 watchdog.exe，由它拉起主程序，而非直接自启主程序）
- **巡检**：每 15 秒 `tasklist` 检查 `canteen-terminal.exe` 是否存活（`CREATE_NO_WINDOW` 静默调用，避免每 15 秒在全屏画面上闪烁控制台窗口）；开机首次检查发现未运行**立即拉起**（不等待冷却）
- **崩溃拉起**：主进程退出后冷却 10 秒重启；每小时最多重启 10 次，超过则停止（防无限崩溃循环）
- **正常退出标记**：`/__api__/quit` 处理时写入 `%APPDATA%\CanteenTerminal\exit.flag`（bridge.py）；watchdog 每轮巡检先检查该标记，存在则删除标记并自行退出（用户主动退出维护/配置，不再拉起）
- **日志**：`%LOCALAPPDATA%\CanteenTerminal\watchdog.log`

---

## 17. 已确认决策

1. **桌面壳方案**：采用 Python + PyQt5 + QWebEngineView（替代 Tauri/Electron，兼容 Win7/Win7 32 位）
2. **打包模式**：`--onedir`（绿色目录版），不能用 `--onefile`（IndexedDB 锁冲突）
3. **Python 版本**：32 位 Python 3.10（`C:\Python310-32\python.exe`），兼容 Win7 32 位
4. **HTTP 端口**：固定 15118、无 fallback（被占直接报错），保证 origin 稳定（1287 落入 Windows 动态端口保留段，已弃用）
5. **管理员密码**：不存储本地，由后端 `/api/admin/login` 验证（BCrypt）
6. **配置文件**：支持 `//` 行注释，由 Python 端解析后去除
7. **Chromium 沙箱**：必须 `--no-sandbox`（否则 IndexedDB 写入失败）
8. **单实例**：Windows 命名 Mutex，防止多开导致 origin 变化和数据丢失
9. **窗口切换**：动态生效（`setWindowFlags` + `showNormal`/`showFullScreen`），无需重启
10. **`__pythonShell` 注入**：`DocumentCreation` 阶段（早于 Vue `onMounted`）
11. **eval_js**：调试后门已移除（RCE 风险）
12. **升级保留配置**：Inno Setup 升级安装前备份、安装后恢复用户配置（`%APPDATA%`/`%LOCALAPPDATA%`）
13. **watchdog**：开机自启指向 watchdog.exe 而非主程序，由守护进程拉起并监控主进程；正常退出写 exit.flag，watchdog 检测到即不再拉起
14. **读卡器**：主模式 DLL 直读（32 位 Python），DLL 失败回退读卡助手 HID 键盘注入（次级）
