# 企业智慧食堂预定餐系统

集团级企业智慧食堂预定餐系统，支持多门店数据隔离、多端适配（管理后台、H5 订餐端、X86 取餐终端），采用企业级架构标准。

## 当前版本

各模块版本号集中管理于 [VERSIONS.json](VERSIONS.json)，每次修改代码必须同步升版本。

| 模块 | 版本 | 版本文件 | 说明 |
|------|------|----------|------|
| 系统整体 | **0.7.55** | `VERSIONS.json` → `system` | 汇总版本，用于 deploy 分支发布 |
| 后端服务 | **0.0.76** | `backend/pom.xml`、`backend/src/main/resources/version.json` | Spring Boot API |
| 管理后台 | **0.0.56** | `admin-web/package.json` | Vue 3 管理端 |
| H5 订餐端 | **0.0.58** | `h5/package.json` | Vue 3 移动端 |
| X86 取餐终端 | **3.0.6** | `x86-v3/VERSIONS.json` → `terminal` | Windows EXE，独立发版 |

> X86 终端共三代：**V1（已淘汰）**、**V2（保留兼容）**、**V3（当前主用，3.0.6）**。详见下文「X86 取餐终端」。

## 快速部署（生产环境）

一行命令完成部署（自动安装 git、克隆 deploy 分支、配置权限、引导设置超管账号密码）：

```bash
# 多通道自动切换:jsdelivr CDN 优先(国内云服务器稳定),GitHub 代理与直连兜底
for u in "https://fastly.jsdelivr.net/gh/MingTu01/canteen@main/install.sh" "https://cdn.jsdelivr.net/gh/MingTu01/canteen@main/install.sh" "https://testingcf.jsdelivr.net/gh/MingTu01/canteen@main/install.sh" "https://gh-proxy.com/https://raw.githubusercontent.com/MingTu01/canteen/main/install.sh" "https://raw.githubusercontent.com/MingTu01/canteen/main/install.sh"; do
  curl -fsSL --connect-timeout 8 --max-time 60 "$u" -o /tmp/canteen-install.sh && [ -s /tmp/canteen-install.sh ] && break
done && sudo bash /tmp/canteen-install.sh
```

部署向导会引导设置超管账号与密码（至少 8 位）。完成后容器端口均绑定在 `127.0.0.1`：

| 服务 | 容器内 | 宿主机（仅本机可访问） |
|------|--------|------------------------|
| 管理后台 | 80 | `http://localhost:18080` |
| H5 订餐端 | 80 | `http://localhost:18081` |
| 后端 API | 8080 | `http://localhost:18082` |
| MySQL | 3306 | `127.0.0.1:13306` |
| Redis | 6379 | `127.0.0.1:16379` |

> 因安全策略（P0-1）所有端口只绑定 `127.0.0.1`，**外网访问必须通过 1Panel / Nginx 反向代理**转发到上述本机端口，不要直接暴露。

详细部署与更新流程见 [DEPLOY.md](DEPLOY.md)。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 25 + Spring Boot 3.5.x + MyBatis Plus + MySQL 8.0 + Redis 7 |
| 管理后台 | Vue 3 + TypeScript + Vite + Element Plus + Tailwind CSS 4 |
| H5 订餐端 | Vue 3 + TypeScript + Vite + Tailwind CSS 4 |
| X86 终端前端 | Vue 3 + TypeScript + Vite + Tailwind CSS 4 |
| 终端桌面壳 | Python 3.10（32 位）+ PyQt5 + QWebEngineView（兼容 Win7/Win10/Win11） |
| 认证 | JWT Token + HttpOnly Cookie + BCrypt 密码加密 |
| 数据库迁移 | Flyway（`V1__` ~ `V32__`）+ `SchemaMigrationRunner` 增量补丁 |
| 部署 | Docker Compose（后端/管理后台/H5）+ PyInstaller + Inno Setup EXE 安装包（X86 终端） |
| CI | GitHub Actions（`.github/workflows/deploy.yml`，推送 main 自动构建并发布 deploy 分支） |

## 项目结构

```
enterprise-canteen/
├── backend/                    # Spring Boot 后端源码
│   ├── src/main/java/com/example/canteen/
│   │   ├── controller/         # 控制器层
│   │   ├── service/            # 业务逻辑层
│   │   ├── mapper/             # 数据访问层（MyBatis-Plus）
│   │   ├── entity/             # 实体类
│   │   ├── dto/                # 数据传输对象
│   │   ├── security/           # JWT / 密码校验 / Cookie / 限流
│   │   ├── config/             # 配置类
│   │   └── migration/          # SchemaMigrationRunner 增量补丁
│   ├── src/main/resources/
│   │   ├── db/migration/       # Flyway 迁移脚本 V1~V32
│   │   ├── mapper/             # MyBatis XML
│   │   ├── application.yml     # 主配置
│   │   ├── application-dev.yml # H2 dev profile
│   │   ├── application-prod.yml
│   │   ├── schema-h2.sql       # H2 dev 建表脚本
│   │   └── version.json        # 版本信息 + 后端 changelog
│   ├── src/test/               # 单元测试
│   ├── Dockerfile              # 完整构建镜像（备用方案）
│   ├── Dockerfile.runtime      # 运行时基础镜像（卷映射模式用）
│   └── pom.xml
├── admin-web/                  # 管理后台前端 (Vue 3)
├── h5/                         # H5 订餐端前端 (Vue 3)
├── shared/                     # 前后端共用 TS 工具(date.ts / imageSign.ts / money.ts)
├── tools/                      # 调试工具(二维码验签测试页 / 网络扫描接收端)
├── x86-v2/                     # X86 终端 V2(本地维护,已 gitignore)
├── x86-v3/                     # X86 终端 V3(当前主用,本地维护,已 gitignore)
├── scripts/                    # 构建与运维脚本(见下)
├── docs/                       # 需求 / 审查 / 部署文档
├── deploy/                     # 构建产物输出目录(gitignore,卷映射给容器)
├── docker-compose.yml          # Docker 编排(卷映射模式,更新无需重建镜像)
├── .github/workflows/deploy.yml# CI:构建并发布 deploy 分支
├── install.sh                  # 一键安装脚本(GitHub 一行命令部署入口)
├── deploy.sh                   # 部署 CLI(交互式向导 + 子命令)
├── canteen.sh                  # 服务器管理面板(输入 canteen 打开)
├── pack_deploy_zip.py          # 将 deploy 分支打包为 ZIP(离线部署用)
├── push_via_proxy.py           # 带加速器推送用的辅助脚本
├── DEPLOY.md                   # 部署运维指南
├── VERSIONS.json               # 全部版本号与 changelog 集中管理
└── .env.example                # 环境变量模板
```

> X86 终端源码不在本仓库内（体积大：含 `node_modules`、PyInstaller 运行库与 EXE 安装包），以本地目录 `x86-v2/`、`x86-v3/` 维护，见 `.gitignore`。

## 分支架构

| 分支 | 用途 | 内容 | 使用方 |
|------|------|------|--------|
| `main` | 源代码 | backend / admin-web / h5 源码 + 脚本 + 文档 | 开发机 |
| `deploy` | 部署产物（orphan 分支，独立历史） | jar/dist + docker-compose.yml + 运行时脚本 + install.sh | 服务器 |

推送 `main` 后 CI 自动构建并发布到 `deploy` 分支，服务器 `git pull` 即可更新，**无需安装 Maven / Node.js**。

## 部署脚本

| 脚本 | 用途 |
|------|------|
| [install.sh](install.sh) | 一键安装入口：检查环境 → 安装 git → 克隆 deploy 分支 → 调用 `deploy.sh` |
| [deploy.sh](deploy.sh) | 部署 CLI：交互式向导（安装 Docker、配置国内源、生成 `.env`、启动服务、健康检查）+ 子命令 `status/logs/stop/restart/reset-admin/help` |
| [docker-compose.yml](docker-compose.yml) | 服务编排（MySQL/Redis/后端/管理后台/H5），端口仅绑定 `127.0.0.1` |
| [.github/workflows/deploy.yml](.github/workflows/deploy.yml) | CI：构建后端 jar / admin-web / h5 产物并推送到 `deploy` 分支 |
| [pack_deploy_zip.py](pack_deploy_zip.py) | 把 deploy 分支打包成离线 ZIP（国内服务器不便拉 GitHub 时用） |
| `backend/Dockerfile.runtime` | 后端运行时基础镜像（仅 JRE + curl，业务 jar 走卷映射） |

## 维护脚本

### canteen.sh —— 服务器管理面板

部署时自动安装为系统命令，服务器任意目录输入 `canteen` 即可打开交互式面板（V2）：

```
【升级】      1) 升级全部(含备份+自动回退)  2) 仅升级后端  3) 仅升级前端(admin-web+h5)
【备份与恢复】4) 手动备份(快照)  5) 恢复备份  6) 查看快照列表
【管理】      7) 服务状态(含资源监控)  8) 重置管理员密码  9) 查看日志  10) 重启服务  11) 停止服务
【系统】      12) 修复 canteen 系统命令  13) 版本详情与更新日志  14) 系统诊断
             15) 清理 Docker 镜像  16) 查看配置(.env 脱敏)  17) 数据库/项目自检自愈
             18) 后台定时自愈监控(每 5 分钟)  19) 重置数据库(清空业务数据,保留超管)
```

非交互子命令：

```bash
canteen status                 # 服务状态(含资源监控)
canteen upgrade [all|backend|frontend]
canteen backup "说明"          # 创建快照
canteen restore <快照ID>       # 恢复快照
canteen logs [服务]            # 查看日志
canteen diagnose               # 系统诊断
canteen selfheal [check|fix]   # 数据库/项目自检自愈
canteen heal-monitor [enable|disable|status|run|log]  # 后台定时自愈
canteen install | uninstall     # 安装/卸载 canteen 系统命令
```

### scripts/ 目录

| 脚本 | 用途 |
|------|------|
| `build.sh [backend\|admin-web\|h5]` | 在 Docker 容器内构建产物到 `deploy/`（宿主机无需 JDK/Node） |
| `upgrade.sh [all\|backend\|frontend]` | 安全升级（分支感知：deploy 免构建 / main 需构建，含快照 + 健康检查 + 自动回退） |
| `snapshot.sh` | 快照管理（创建 / 列出 / 恢复 / 清理，快照含数据库 + 产物 + 代码版本） |
| `backup.sh` | 数据库备份（mysqldump + AES-256 加密） |
| `restore.sh` | 数据库恢复 |
| `cron_backup.sh` | 定时备份入口（每天凌晨 2:00，保留 30 份） |
| `self_heal.py` / `cron_self_heal.sh` | 数据库与项目自检自愈（崩溃自动重建/拉取），支持 5 分钟定时巡检 |
| `clean-redeploy.sh` | 完全清理后重新部署 |
| `update.sh` | 快速更新（无备份，仅 main 分支开发用） |
| `publish.sh` | 手动构建并发布 deploy 分支（CI 不可用时用） |
| `init-db-user.sh` | 创建 MySQL 应用专用用户（仅 DML 权限） |
| `wechat_setup.py` | 微信公众号配置辅助（菜单/回调等） |
| `seed-dev.sql` | 开发测试数据（2 门店 / 5 部门 / 5 员工 / 13 菜品 / 6 菜单 / 3 通知 / 3 管理员） |
| `gen_admin_tutorial_ppt.py` | 生成管理后台操作教程 PPT |

## 服务与账号

| 端 | 本地地址 | 说明 |
|----|----------|------|
| 管理后台 | http://localhost:18080 | 管理员登录 |
| H5 订餐端 | http://localhost:18081 | 员工订餐 |
| 后端 API | http://localhost:18082 | `/api/system/health` 健康检查 |

- **生产环境**：超管账号与密码由部署向导设置（密码 ≥ 8 位）；密码在初始化成功后由 `deploy.sh` 从 `.env` 中清理。
- **开发/测试数据**（执行 `scripts/seed-dev.sql` 后）：`admin` / `store1` / `store2`，密码均为 `123456`。

```bash
# 导入开发测试数据(部署后手动执行)
docker cp scripts/seed-dev.sql canteen-mysql:/tmp/
docker exec canteen-mysql mysql -uroot -p<pwd> canteen -e "source /tmp/seed-dev.sql"
```

## 数据备份

- **自动备份**：每天凌晨 2:00（`scripts/cron_backup.sh`），保留 30 份
- **手动备份**：管理后台 → 备份恢复 → 立即备份；或 `canteen backup "说明"`
- **OS 级备份**：`./scripts/backup.sh`（与应用层备份互补，AES-256 加密）
- **恢复**：`./scripts/restore.sh backup/<文件名>.json.gz`，或管理后台备份恢复页
- **快照回退**：`canteen restore <快照ID>`（升级失败自动回退即用此机制）

## X86 取餐终端

终端使用 **Python 3.10（32 位）+ PyQt5 + QWebEngineView** 打包为独立 Windows EXE 安装包，内置 CH375/CardHelper 读卡器驱动安装，兼容 Win7 / Win10 / Win11。

### 版本说明

| 代次 | 目录 | 版本 | 状态 |
|------|------|------|------|
| V1 | （原仓库根 `terminal/` + `src-python/`） | 1.0.x | **已淘汰并清理**，不再维护 |
| V2 | `x86-v2/` | 2.0.15 | 保留，兼容旧终端升级 |
| V3 | `x86-v3/` | **3.0.6** | **当前主用**，新装机一律使用 |

- V2 / V3 目录各自是一个完整的 X86 工程（`terminal/` 前端 + `src-python/` 桌面壳 + `VERSIONS.json`），因含大量本地产物被 `.gitignore` 忽略，不随仓库分发。
- 两端共用同一套后端接口；V3 在前端交互与运行期性能上做了优化（见 `x86-v3/docs/`）。

### 打包

在 Windows 打包机上进入对应版本目录运行：

```bash
cd x86-v3/src-python

# 完整打包（构建前端 + PyInstaller + Inno Setup）
python build_installer.py

# 产物: output/CanteenTerminal-Setup-<版本>.exe
```

前置条件：Node.js 18+、Python 3.10 **32 位**（含 PyQt5 / PyQtWebEngine / pyinstaller）、Inno Setup 6+、CH375 驱动文件位于 `src-python/drivers/`。版本号由 `build_installer.py` 从同目录 `VERSIONS.json` 的 `terminal.version` 自动同步。

打包完成后将 EXE 上传到 GitHub Releases（安装包资产名固定为 `CanteenTerminal-Setup-<版本>.exe`），管理后台下载中心会自动跟随最新版本。

### 安装与配置

- 安装后进入全屏无边框模式；**连续点击窗口右上角 6 下（2 秒内）** → 输入管理员密码 → 进入配置模式（绑定/解绑/切换运行模式）、退出或取消。
- 安装目录下 `config.json`（支持 `//` 行注释）关键字段：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `server_url` | `https://canteen.908521.xyz` | 预设后端地址，留空则需手动输入；不带末尾 `/`、不带 `/api` |
| `window_mode` | `fullscreen` | `fullscreen` 或 `windowed`（1280×800） |
| `card_interval` | `2.5` | 读卡防抖间隔（秒），推荐 1.0~3.0 |
| `idle_timeout` | `120` | 无操作自动返回待机页秒数，0 = 永不 |
| `update_check_url` | - | 在线更新检查地址（GitHub Releases + 加速器） |

> 管理员密码验证由后端 `/api/admin/login` 完成（BCrypt），**`config.json` 中无密码字段**。

### 在线更新与卸载

- 启动时后台检测新版本，发现新版弹窗「下载更新 / 取消 / 忽略此版本」；下载域名白名单 + SHA256 校验后静默安装，升级保留 `config.json`。
- 卸载自动清理安装目录、`%APPDATA%\CanteenTerminal`、`%LOCALAPPDATA%\CanteenTerminal`，并提供可选「移除 CH375 驱动」复选框。

## 本机开发

```bash
# 后端（需 JDK 25 + Maven）
cd backend && mvn spring-boot:run        # dev profile 使用 H2,免 MySQL
cd backend && mvn test                   # 单元测试

# 管理后台 / H5 / 终端前端
cd admin-web && npm ci && npm run dev     # npm run build 会先跑 vue-tsc 类型检查
cd h5        && npm ci && npm run dev     # npm run type-check 单独类型检查
cd x86-v3/terminal && npm ci && npm run test   # vitest
```

## 版本规范

- 版本号统一为 `MAJOR.MINOR.PATCH`（语义化版本），集中登记在 [VERSIONS.json](VERSIONS.json)。
- **必须同步更新**的版本文件：
  - `backend/pom.xml`
  - `backend/src/main/resources/version.json`（同时追加后端 changelog）
  - `admin-web/package.json`
  - `h5/package.json`
  - `x86-v3/VERSIONS.json`（终端版本，打包时由 `build_installer.py` 读取）
  - `VERSIONS.json`（根：各模块版本 + 各端 changelog + `system` 汇总版本）
- 提交信息沿用 `模块 V版本: 变更摘要` 格式；推送 main 后 CI 自动发布 deploy 分支。

## 需求与设计文档

| 文档 | 说明 |
|------|------|
| [DEPLOY.md](DEPLOY.md) | 部署、升级、备份、故障排查、目录结构 |
| [docs/01-后端服务.md](docs/01-后端服务.md) | 后端 API、安全模型、数据模型、业务规则、Redis 缓存 |
| [docs/02-管理后台.md](docs/02-管理后台.md) | 管理后台路由、RBAC 权限、UI 组件、设计系统 |
| [docs/03-H5订餐端.md](docs/03-H5订餐端.md) | H5 登录、订餐、订单、个人中心、图片缓存策略 |
| [docs/04-X86终端.md](docs/04-X86终端.md) | X86 终端缓存策略、SSE 实时更新、管理入口、读卡器、安全边界 |
| [docs/05-PythonShell.md](docs/05-PythonShell.md) | Python Shell 架构、读卡器集成、单实例限制、PyInstaller 打包 |
| [docs/微信公众号功能部署指南.md](docs/微信公众号功能部署指南.md) | 微信公众号登录 / 订阅消息 / 事件回调配置 |
| [docs/SERVER_HARDENING.md](docs/SERVER_HARDENING.md) | 服务器加固与安全基线 |
| [docs/SERVER_REDEPLOY.md](docs/SERVER_REDEPLOY.md) | 服务器重新部署步骤 |