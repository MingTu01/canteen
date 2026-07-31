# 05 · Python Shell 需求文档（src-python）

> 版本：V0.0.1 ｜ 更新日期：2026-07-30
> 代码路径：[src-python/](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python)
> 终端前端：详见 [04-X86终端.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/04-X86终端.md)

---

## 1. 项目定位

X86 终端的桌面壳程序，使用 **Python + PyQt5 + QWebEngineView** 加载 Vue 前端，替代 Tauri/Electron 方案，**兼容 Win7/Win10/Win11 32/64 位**（包括 Win7 32 位这一无法安装 Edge WebView2 的老旧系统）。

### 关键职责

- **加载 Vue 前端**：QWebEngineView 加载本地 HTTP 服务器托管的 Vue dist
- **读卡器集成**：ctypes 调 `OUR_IDR.dll` + `IDUSB.DLL`（CH372/CH375 芯片），后台线程读取 IC 卡卡号
- **窗口管理**：全屏无边框（运行模式）/ 1280×800 窗口（配置模式），动态切换无需重启
- **本地 HTTP 服务器**：双重职责（托管静态文件 + 处理 `/__api__/*` 端点）
- **单实例限制**：Windows 命名 Mutex，防止多开导致 origin 变化和数据丢失
- **配置管理**：`config.json` 支持 `//` 行注释，由 Python 端解析后去除

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
│  │  (127.0.0.1:1287)   │    │  - 处理 /__api__/* 请求      │ │
│  │  - 静态文件 (Vue)    │    │  - emit 信号 (跨线程安全)    │ │
│  │  - /__api__/* 端点   │    │  - 配置读写                  │ │
│  └─────────────────────┘    └─────────────────────────────┘ │
│                                       ↑                      │
│  ┌───────────────────────────────────┴──────────────────┐   │
│  │  CardReader (QObject, 后台线程)                       │   │
│  │  - ctypes 调 OUR_IDR.dll                              │   │
│  │  - idr_read() 轮询读卡                                │   │
│  │  - 防抖 2 秒（可配置）                                │   │
│  │  - pyqtSignal card_read(str) → 推送给前端             │   │
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
| 主入口 | [main.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/main.py) | QApplication 启动、窗口创建、信号连接、Mutex 单实例、QtWebEngine 配置 |
| 配置 | [config.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/config.py) | `config.json` 读写、`//` 注释剥离、默认配置生成 |
| HTTP 服务器 | [server.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/server.py) | 托管 Vue 静态文件、处理 `/__api__/*` 端点、SPA 路由回退、目录穿越防护 |
| Shell 桥接 | [bridge.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/bridge.py) | API 调用分发、pyqtSignal 跨线程通信、配置更新通知 |
| 读卡器 | [card_reader.py](file:///d:/文档/enterprise-canteen/enterprise-canteen/src-python/card_reader.py) | DLL 加载、后台线程轮询、防抖、卡号解析 |

---

## 4. 单实例限制（Windows Mutex）

### 4.1 必要性

防止多开 EXE 导致：
1. **本地 HTTP 端口被占用**：第二个实例 fallback 到其他端口（如 1288），origin 从 `http://127.0.0.1:1287` 变为 `http://127.0.0.1:1288`，localStorage/IndexedDB 按 origin 隔离，导致**绑定配置和缓存数据全部"丢失"**
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

- **间隔**：默认 2 秒（可配置，`card_interval`）
- **规则**：同一卡号在 `_card_interval` 秒内不重复触发
- **动态生效**：`set_interval(seconds)` 由配置页修改后通过 `config_updated` 信号立即调用，无需重启读卡器
- **最小值**：0.5 秒（`max(0.5, float(card_interval))`）

### 5.6 后台线程流程（`_read_loop`）

```
1. idr_beep(38) 蜂鸣 76ms 确认读卡器连接
2. pcdgetdevicenumber 读取设备号确认通信正常
3. 循环：
   - idr_read(card_buf) 读取卡号
   - ret == 0：解析卡号，防抖检查，蜂鸣提示，emit card_read 信号
   - ret == 8：卡不在感应区（正常），继续轮询
   - ret == 22/23/24：设备异常，sleep 2s 避免日志刷屏
   - 其他错误：sleep 0.5s
   - 正常轮询间隔：100ms
4. stop() 时 _running = False，线程退出
```

### 5.7 DLL 加载顺序

1. **PyInstaller 临时目录**（`sys._MEIPASS`，打包时内嵌）
2. **EXE 同目录**（外部放置，便于升级 DLL 而不重打 EXE）
3. **开发模式**：`src-python/` 目录

加载 `OUR_IDR.dll` 前先加载 `IDUSB.DLL`（依赖库），并把 DLL 目录加入 `os.add_dll_directory` 和 `PATH` 环境变量。

### 5.8 卡号推送到前端

```python
def push_card_to_frontend(page, card_no):
    safe_card = card_no.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
    js = f"window.__onCardRead && window.__onCardRead('{safe_card}');"
    page.runJavaScript(js)
```

前端 `useCardReader.ts` 注册 `window.__onCardRead` 全局函数，Python 通过 `page.runJavaScript` 推送卡号。

---

## 6. 本地 HTTP 服务器（server.py）

### 6.1 端口策略

**固定端口优先**，保证 origin（`http://127.0.0.1:port`）稳定：

| 优先级 | 端口 | 说明 |
|--------|------|------|
| 1 | **1287** | 终端默认端口 |
| 2 | 1288 | fallback |
| 3 | 1289 | fallback |
| 4 | 1290 | fallback |
| 5 | 1291 | fallback |

全部占用则抛 `RuntimeError`。固定端口是必须的——端口变化会导致 localStorage/IndexedDB origin 隔离，绑定配置和缓存数据全部"丢失"。

### 6.2 双重职责

1. **静态文件托管**：serve Vue dist 目录，SPA 路由回退到 `index.html`
2. **API 端点**：`/__api__/*` 转发给 ShellBridge 处理

### 6.3 静态文件安全

- **目录穿越防护**：`os.path.normpath` 后检查是否以 `web_dir` 开头，否则返回 403
- **MIME 类型**：根据扩展名设置 `Content-Type`（html/js/css/json/png/jpg/gif/svg/ico/woff/woff2/ttf）
- **SPA 回退**：文件不存在时返回 `index.html`（Vue Router 接管路由）

### 6.4 Vue dist 查找顺序

1. **PyInstaller 临时目录**（`_MEIPASS/web`，打包时内嵌）
2. **EXE 同目录的 `web/` 目录**（外部放置，便于前端热更新）
3. **开发模式**：`../terminal/dist`

---

## 7. ShellBridge（bridge.py）

### 7.1 信号（跨线程安全）

| 信号 | 触发场景 | 槽函数（main.py） |
|------|----------|-------------------|
| `switch_to_config_requested` | 前端请求切换到配置模式 | `window.switch_to_config_mode` |
| `switch_to_fullscreen_requested` | 前端请求切换到全屏模式 | `window.switch_to_fullscreen_mode` |
| `quit_requested` | 前端请求退出应用 | `QApplication.quit` |
| `config_updated(dict)` | 配置已更新 | `on_config_updated`（动态应用运行时参数） |
| `eval_js_requested(str)` | 临时诊断（前端执行 JS） | `on_eval_js` |

**关键**：Qt 窗口操作必须在主线程执行。HTTP 服务器在后台线程，通过 `pyqtSignal` 跨线程安全调用主线程槽函数。

### 7.2 API 端点

| 方法 | 路径 | 请求体 | 返回 | 说明 |
|------|------|--------|------|------|
| GET | `/__api__/server_url` | - | `{ok, server_url}` | 获取预设服务器地址 |
| GET | `/__api__/config` | - | `{ok, config}` | 获取完整配置 |
| POST | `/__api__/set_config` | `{window_mode?, card_interval?, idle_timeout?, server_url?}` | `{ok, updated}` | 更新配置（字段白名单 + 类型校验） |
| POST | `/__api__/switch_to_config` | - | `{ok}` | 切换到窗口模式 |
| POST | `/__api__/switch_to_fullscreen` | - | `{ok}` | 切换到全屏模式 |
| POST | `/__api__/quit` | - | `{ok}` | 退出应用 |
| POST | `/__api__/restart_card_reader` | - | `{ok, running}` | 重启读卡器 |
| POST | `/__api__/eval_js` | `{js}` | `{ok}` | 临时诊断（执行前端 JS） |

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
```

**`--no-sandbox` 是必须的**：渲染器进程的文件系统沙箱会阻止 IndexedDB 写入元数据（报 `indexed_db_backing_store.cc SET_UP_METADATA` 错误），导致菜品图片缓存、头像缓存全部失效。

### 8.2 持久化目录

```python
data_dir = os.path.join(get_exe_dir(), 'data')
profile.setPersistentStoragePath(data_dir)
profile.setPersistentCookiesPolicy(QWebEngineProfile.AllowPersistentCookies)
profile.setHttpCacheType(QWebEngineProfile.NoCache)  # 禁用 HTTP 缓存
```

- **持久化路径**：`EXE 同目录/data/`，存储 localStorage（终端绑定状态）和 IndexedDB（菜品/菜单/图片缓存）
- **禁用 HTTP 缓存**：每次启动从本地服务器重新加载前端资源（避免重新打包后加载旧 `index.html` 引用不存在的旧哈希 JS 文件）
- **锁文件清理**：启动时清理残留的 `-journal`/`-wal`/`-shm`/`LOCK` 文件，避免上次崩溃导致的 `database is locked` 死锁

### 8.3 中文路径问题（PyInstaller onedir）

QtWebEngine 内部使用 ANSI API 读取路径，无法处理含中文的路径（如 `D:\文档\...`），报 `resources not found` 和 `Couldn't mmap icu data file`。

**解决方案**（必须在 `from PyQt5.QtWebEngineWidgets import *` 之前执行）：

1. 用 `GetShortPathName` 将长路径转换为 Windows 短路径名（8.3 格式），消除中文/空格
2. 设置环境变量：
   - `QTWEBENGINEPROCESS_PATH`：`QtWebEngineProcess.exe` 路径
   - `QTWEBENGINE_RESOURCES_PATH`：`resources/` 目录（含 `icudtl.dat` + `.pak`）
   - `QTWEBENGINE_LOCALES_PATH`：`translations/qtwebengine_locales/` 目录
3. 写 `qt.conf` 文件：
   - `bin/qt.conf`：`Prefix = ..`（因为 `QtWebEngineProcess.exe` 在 `bin/` 下，而 `resources/` 在 `bin/` 的父目录 `Qt5/` 下）
   - `EXE 同目录/qt.conf`：完整路径配置

### 8.4 `__pythonShell` 标记注入

```python
marker_script = QWebEngineScript()
marker_script.setSourceCode('window.__pythonShell = true;')
marker_script.setInjectionPoint(QWebEngineScript.DocumentCreation)
marker_script.setWorldId(QWebEngineScript.MainWorld)
marker_script.setName('python-shell-marker')
profile.scripts().insert(marker_script)
```

**必须在 `DocumentCreation` 阶段注入**（早于 Vue `onMounted`），否则 `detectShell()` 返回 `'browser'`，导致：
- Settings 运行设置卡不显示
- `switchToConfigMode`/`quitApp` 走 browser 分支失效

`loadFinished` 信号触发时 Vue 早已挂载，太晚了。

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

`FullscreenWebPage.javaScriptConsoleMessage` 重写，将前端 `console.log/warn/error/info` 打印到 Python stdout，方便调试。

---

## 10. config.json 配置

### 10.1 字段说明

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `server_url` | string | `""` | 预设后端服务器地址，绑定页面自动填入；留空则要求手动输入。**不要带末尾斜杠 `/`，不要带 `/api` 后缀** |
| `window_mode` | string | `"fullscreen"` | `"fullscreen"`（全屏无边框）或 `"windowed"`（1280×800 窗口） |
| `card_interval` | float | `2.0` | 读卡防抖间隔（秒），推荐 1.0~3.0 |
| `idle_timeout` | int | `30` | 无操作自动返回待机页时间（秒），0=永不 |

> **注意**：管理员密码验证由后端 `/api/admin/login` 接口完成（BCrypt），**config.json 中无 `admin_password_hash` 字段**。

### 10.2 `//` 行注释支持

`config.json` 支持 `//` 行注释，由 `config.py.strip_json_comments` 解析时去除：

- 只去除**不在字符串内**的 `//` 注释
- 通过状态机遍历字符（`in_string` + `escape` 标志），跳过字符串内的 `//`
- 首次运行时自动生成带完整注释的默认配置（`DEFAULT_CONFIG_JSON`）

### 10.3 配置读写

| 函数 | 说明 |
|------|------|
| `ensure_config_json()` | 确保文件存在，首次运行生成默认配置（含注释） |
| `read_config()` | 读取 `server_url`（向后兼容） |
| `read_full_config()` | 读取完整配置，缺失字段用默认值填充，类型校验 |
| `write_config(updates)` | 部分更新，字段白名单 + 类型校验 |

**注意**：`write_config` 会丢失注释（`json.dump` 不保留注释），但功能正常。如需保留注释，需手动编辑 `config.json`。

### 10.4 默认配置示例

```json
{
  // 预设后端服务器地址
  // 留空("")则要求操作员在绑定页面手动输入
  // 填写示例: "http://192.168.1.100:8080"
  // 注意:不要带末尾斜杠 /,不要带 /api 后缀
  "server_url": "",

  // 窗口模式: "fullscreen" / "windowed"
  // 在配置页"进入运行模式"时动态生效(无需重启)
  "window_mode": "fullscreen",

  // 读卡防抖间隔(秒),推荐 1.0 ~ 3.0
  "card_interval": 2.0,

  // 无操作自动返回待机页时间(秒),0=永不
  "idle_timeout": 30
}
```

---

## 11. PyInstaller 打包

### 11.1 打包命令

```bash
# 使用 32 位 Python（兼容 Win7 32 位）
C:\Python310-32\python.exe -m PyInstaller canteen-terminal.spec --clean --noconfirm
```

### 11.2 打包模式：`--onedir`（绿色目录版）

**必须用 `--onedir`，不能用 `--onefile`**：

| 模式 | 问题 |
|------|------|
| `--onefile`（单文件） | QtWebEngineProcess 子进程与主进程争抢持久化目录的 QuotaManager SQLite 锁，报 `database is locked (errno 33)`，IndexedDB 无法写入（菜品图片缓存失效） |
| `--onedir`（目录版） | 所有文件在 EXE 同目录，QtWebEngineProcess 能正确访问持久化目录，无锁冲突。后续可用 Inno Setup / NSIS 打包为安装包 |

### 11.3 打包内容

| 内容 | 来源 | 目标位置 |
|------|------|----------|
| Python 代码 | `main.py` + `config.py` + `server.py` + `bridge.py` + `card_reader.py` | EXE 主程序 |
| Vue 前端 | `../terminal/dist` | `web/` |
| 读卡器 DLL | `OUR_IDR.dll` + `IDUSB.DLL` | EXE 同目录 |
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
├── _internal/                    # 依赖（PyQt5、Python 运行时等）
│   └── PyQt5/Qt5/
│       ├── bin/QtWebEngineProcess.exe
│       ├── resources/            # icudtl.dat + .pak
│       └── translations/qtwebengine_locales/
├── web/                          # Vue 前端 dist
├── OUR_IDR.dll                   # 读卡器 SDK
├── IDUSB.DLL                     # 读卡器依赖
├── config.json                   # 配置（首次运行自动生成）
└── data/                         # 持久化目录（localStorage + IndexedDB）
```

整个 `canteen-terminal/` 文件夹即为可部署的绿色版，拷贝到目标机器即可运行。

---

## 12. 启动流程

```
1. 单实例检查（Windows Mutex）
   └─ 已有实例运行 → 弹框提示 → 退出

2. 确保配置文件存在（ensure_config_json）
   └─ 首次运行生成带注释的默认 config.json

3. 读取完整配置（read_full_config）
   └─ window_mode / card_interval / idle_timeout / server_url

4. 查找 Vue 前端 dist 目录（find_web_dist）
   └─ 未找到 → 提示 → 退出

5. 创建 QApplication

6. 配置 QtWebEngine 持久化目录（data/）
   └─ 清理残留锁文件（-journal/-wal/-shm/LOCK）
   └─ 禁用 HTTP 缓存（NoCache）

7. 注入 __pythonShell 标记（QWebEngineScript, DocumentCreation）

8. 创建读卡器（CardReader，传入 card_interval）

9. 创建 ShellBridge

10. 启动 HTTP 服务器（serve Vue dist + API 端点）
    └─ 固定端口 1287 优先，fallback 到 1288~1291

11. 创建主窗口（根据 window_mode 决定全屏/窗口）

12. 连接 bridge 信号到窗口操作
    └─ switch_to_config_requested → window.switch_to_config_mode
    └─ switch_to_fullscreen_requested → window.switch_to_fullscreen_mode
    └─ quit_requested → QApplication.quit
    └─ config_updated → 动态应用运行时参数

13. 连接读卡器信号 → 推送卡号到前端
    └─ card_reader.card_read → push_card_to_frontend

14. 延迟 2 秒启动读卡器（确保前端已就绪）
    └─ QTimer.singleShot(2000, card_reader.start)

15. 进入 Qt 事件循环（app.exec_）

16. 退出时清理
    └─ card_reader.stop()
    └─ server.shutdown()
```

---

## 13. 安全边界

| 边界 | 策略 |
|------|------|
| 单实例 | Windows 命名 Mutex（`Global\CanteenTerminal_SingleInstance_v1`），防止多开导致数据丢失 |
| HTTP 服务器 | 仅绑定 `127.0.0.1`，不对外暴露；固定端口 1287 保证 origin 稳定 |
| 静态文件 | 目录穿越防护（`normpath` + 前缀检查） |
| 配置文件 | `//` 注释剥离时跳过字符串内容；字段白名单 + 类型校验；写回时不含注释 |
| 管理员密码 | **不存储在本地**，由后端 `/api/admin/login` 验证（BCrypt） |
| 读卡器 DLL | 优先从 PyInstaller 临时目录加载（打包时内嵌），避免外部替换 |
| Chromium 沙箱 | `--no-sandbox`（必须，否则 IndexedDB 写入失败） |
| HTTP 缓存 | 禁用（`NoCache`），避免重新打包后加载旧前端资源 |
| 持久化目录 | `EXE 同目录/data/`，随程序一起备份/迁移 |
| 卡号推送 | `runJavaScript` 前转义特殊字符（`\\`、`'`、`"`），防止注入 |

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
1. 检查 `OUR_IDR.dll` + `IDUSB.DLL` 是否在 EXE 同目录
2. 检查设备管理器中 USB 设备是否识别
3. 运行 `读写器驱动安装32or64bit.exe` 安装驱动
4. 查看 Python 控制台 `[CardReader]` 日志

### 14.6 窗口切换后消失

**症状**：`setWindowFlags` 后窗口不可见

**原因**：手动 `hide()` 后 `showNormal()` 未恢复

**解决**：不要手动 `hide()`，`setWindowFlags` 会自动重建原生窗口并保持可见

---

## 15. 已确认决策

1. **桌面壳方案**：采用 Python + PyQt5 + QWebEngineView（替代 Tauri/Electron，兼容 Win7/Win7 32 位）
2. **打包模式**：`--onedir`（绿色目录版），不能用 `--onefile`（IndexedDB 锁冲突）
3. **Python 版本**：32 位 Python 3.10（`C:\Python310-32\python.exe`），兼容 Win7 32 位
4. **HTTP 端口**：固定 1287 优先，fallback 到 1288~1291，保证 origin 稳定
5. **管理员密码**：不存储本地，由后端 `/api/admin/login` 验证（BCrypt）
6. **配置文件**：支持 `//` 行注释，由 Python 端解析后去除
7. **Chromium 沙箱**：必须 `--no-sandbox`（否则 IndexedDB 写入失败）
8. **单实例**：Windows 命名 Mutex，防止多开导致 origin 变化和数据丢失
9. **窗口切换**：动态生效（`setWindowFlags` + `showNormal`/`showFullScreen`），无需重启
10. **`__pythonShell` 注入**：`DocumentCreation` 阶段（早于 Vue `onMounted`）
