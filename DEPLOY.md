# 企业智慧食堂系统 - 部署运维指南

## 系统要求

| 项目 | 要求 |
|------|------|
| 操作系统 | CentOS 7+/8/9、Ubuntu 18.04+/20.04/22.04/24.04、Debian 10+ |
| 内存 | ≥ 2GB（推荐 4GB） |
| 磁盘 | ≥ 10GB（含 Docker 镜像 + 数据） |
| 端口 | 80（管理后台）、81（H5 订餐）、8080（后端 API）、3306/6379（仅内部） |
| 软件 | Docker + Docker Compose（由部署脚本自动安装） |

> X86 终端为独立 Windows 安装包，不参与 Docker 部署，详见 `src-python/` 目录。

---

## 一、首次部署

### 1.1 一键部署（推荐）

```bash
# 克隆项目到服务器(国内使用 ghproxy 加速)
git clone https://ghproxy.net/https://github.com/MingTu01/canteen.git /opt/canteen
cd /opt/canteen

# 赋予执行权限
chmod +x deploy.sh scripts/*.sh

# 启动部署向导（需要 root 或 sudo）
sudo ./deploy.sh
```

部署向导会交互式引导你完成以下步骤：

1. **检测 Docker 环境**（已安装自动跳过，未安装则使用阿里云源安装）
2. **配置镜像加速器**（已有配置会询问是否替换 y/n，首次自动配置国内加速源）
3. **配置环境变量**：
   - MySQL 密码（留空则自动生成随机密码）
   - JWT 密钥（自动生成 64 位随机十六进制）
   - **超管账号密码**（自定义设置，至少 8 位）
4. **构建产物**（在 Docker 容器中构建，宿主机无需 JDK/Node.js）
5. **构建运行时镜像**（首次需要，后续更新无需重建）
6. **启动服务**（Docker Compose 编排）
7. **配置开机启动**（systemd service，服务器重启后自动恢复服务）
8. **安装 canteen 命令**（自动安装到系统 PATH，无需单独操作）
9. **部署验证**（健康检查 + 访问地址输出）

### 1.2 跳过 Docker 安装

如果服务器已安装 Docker：

```bash
sudo ./deploy.sh --skip-env
```

### 1.3 部署后访问

部署完成后会输出访问地址（IP 根据服务器自动检测）：

| 服务 | 地址 |
|------|------|
| 管理后台 | `http://<服务器IP>` |
| H5 订餐端 | `http://<服务器IP>:81` |
| 后端 API | `http://<服务器IP>:8080` |

使用部署时设置的超管账号密码登录管理后台。

### 1.4 canteen 管理命令（部署时自动安装）

部署向导的第 8 步会自动安装 `canteen` 系统命令，无需单独操作。部署完成后在服务器任意目录输入 `canteen` 即可弹出管理面板：

```bash
canteen              # 打开交互式管理菜单
canteen status       # 查看服务状态
canteen upgrade      # 安全升级（含快照+自动回退）
canteen backup       # 手动创建快照
```

如需修复或重新安装 `canteen` 命令：

```bash
sudo ./canteen.sh install
# 或通过菜单: canteen → 12) 修复 canteen 系统命令
```

> 详见下方「[四、升级更新](#四升级更新安全升级链路)」章节。

---

## 二、CLI 命令参考

`deploy.sh` 提供以下子命令：

```bash
./deploy.sh [命令]
```

| 命令 | 说明 |
|------|------|
| `deploy` | 交互式部署向导（默认，无参数时执行） |
| `status` | 查看所有服务运行状态 + 健康检查 |
| `logs [服务]` | 查看日志（可指定 backend/admin-web/h5/mysql/redis） |
| `stop` | 停止所有服务 |
| `restart [服务]` | 重启服务（不指定则重启全部） |
| `reset-admin` | 重置超管账号密码（交互式） |
| `help` | 显示帮助信息 |

### 示例

```bash
# 查看服务状态
./deploy.sh status

# 查看后端日志（实时跟踪）
./deploy.sh logs backend

# 仅重启 H5
./deploy.sh restart h5

# 重置超管密码
./deploy.sh reset-admin
```

---

## 三、超管账号管理

### 3.1 首次设置

部署向导中会提示输入超管账号和密码。后端首次启动时，`AdminInitializer` 会读取 `.env` 中的 `INIT_ADMIN_USERNAME` / `INIT_ADMIN_PASSWORD` 环境变量创建超管账号。

**安全机制：**
- 仅在 admin 表为空或只有默认 admin 账号时生效
- 不会覆盖已运营系统中已创建的其他管理员
- 创建自定义超管后，默认 `admin/123456` 账号会被自动删除
- 密码至少 8 位，前后端一致校验

### 3.2 重置超管密码

若忘记超管密码，使用 CLI 重置：

```bash
./deploy.sh reset-admin
```

此命令会：
1. 交互式输入账号名和新密码
2. 写入 `.env` 并设置 `INIT_ADMIN_FORCE=true`
3. 重启后端服务使配置生效
4. `AdminInitializer` 更新超管密码

> **安全提醒：** 重置成功后，建议删除 `.env` 中的 `INIT_ADMIN_FORCE` 和 `INIT_ADMIN_PASSWORD`，避免明文存储密码。

### 3.3 角色说明

| 角色 ID | 名称 | 权限 |
|---------|------|------|
| 1 | 超级管理员 | 全部食堂、全部功能 |
| 2 | 店长 | 本食堂全部管理 |
| 4 | 厨师长 | 订餐汇总 + 菜品管理 |
| 5 | 财务 | 报表 + 充值 |
| 6 | 店长（受限） | 本食堂基础管理 |

超管在管理后台「账号管理」页面创建各食堂管理员并指派到对应食堂。

---

## 四、升级更新（安全升级链路）

### 4.1 使用 canteen 菜单升级（推荐）

安装 `canteen` 系统命令后（见下方 4.2），在服务器任意目录输入：

```bash
canteen
```

弹出交互式菜单：

```
╔══════════════════════════════════════════════╗
║   企业智慧食堂系统 - 管理面板                 ║
╠══════════════════════════════════════════════╣
║  版本: v1.0.0    状态: ● 全部运行中           ║
╠══════════════════════════════════════════════╣
║                                              ║
║  【升级】                                     ║
║   1) 升级全部 (后端+前端) 含备份+自动回退     ║
║   2) 仅升级后端                              ║
║   3) 仅升级前端 (admin-web + h5)             ║
║                                              ║
║  【备份与恢复】                              ║
║   4) 手动备份 (创建快照)                     ║
║   5) 恢复备份 (从快照恢复)                   ║
║   6) 查看快照列表                            ║
║                                              ║
║  【管理】                                     ║
║   7) 查看服务状态                            ║
║   8) 重置管理员密码                          ║
║   9) 查看日志                                ║
║  10) 重启服务                                ║
║  11) 停止服务                                ║
║                                              ║
║  【系统】                                     ║
║  12) 安装 canteen 系统命令                   ║
║                                              ║
║   0) 退出                                     ║
╚══════════════════════════════════════════════╝
```

选择 `1`（升级全部）即可，升级脚本会自动完成安全升级全流程。

### 4.2 安装 canteen 系统命令

首次部署后，安装 `canteen` 为系统命令：

```bash
sudo ./canteen.sh install
```

安装后可在任意目录直接输入 `canteen` 打开管理面板。

卸载：

```bash
sudo canteen uninstall
```

> `canteen` 是指向项目 `canteen.sh` 的软链接，项目更新后菜单自动更新，无需重新安装。

### 4.3 安全升级链路（自动执行）

升级是高风险操作，本系统设计了完整的安全链路，**每一步都有保护**：

```
┌─────────────────────────────────────────────────┐
│              安全升级流程                        │
├─────────────────────────────────────────────────┤
│                                                 │
│  ① 创建升级前快照                               │
│     ├─ 数据库 mysqldump 压缩                    │
│     ├─ deploy/ 产物打包                         │
│     ├─ git commit SHA 记录                      │
│     └─ 版本号记录                               │
│           │                                     │
│           ▼                                     │
│  ② git pull 拉取最新代码                        │
│           │                                     │
│           ▼                                     │
│  ③ build.sh 重建产物 ────── 失败 ──▶ 自动回退   │
│           │                                     │
│           ▼                                     │
│  ④ docker compose restart ── 失败 ──▶ 自动回退  │
│           │                                     │
│           ▼                                     │
│  ⑤ 健康检查 (等待 120s) ── 失败 ──▶ 自动回退    │
│           │                                     │
│           ▼                                     │
│  ⑥ 成功 → 清理旧快照(保留最近5个)               │
│                                                 │
└─────────────────────────────────────────────────┘
```

**自动回退机制：** 当构建失败、重启失败或健康检查超时时，系统自动执行回退：
1. 恢复 `deploy/` 产物到升级前状态
2. 恢复数据库到升级前状态
3. `git checkout` 回退代码到升级前 commit
4. 重启所有服务
5. 回退后健康检查，确认服务恢复正常

> 这意味着即使升级出问题，系统也能自动恢复到升级前的正常状态，不会崩溃。

### 4.4 命令行直接升级

不用菜单，直接执行升级脚本：

```bash
./scripts/upgrade.sh              # 升级全部（后端 + 前端）
./scripts/upgrade.sh backend      # 仅升级后端
./scripts/upgrade.sh frontend     # 仅升级前端（admin-web + h5）
```

或通过 canteen 子命令：

```bash
canteen upgrade           # 升级全部
canteen upgrade backend   # 仅升级后端
canteen upgrade frontend  # 仅升级前端
```

### 4.5 快速更新（无备份，开发用）

如果不需要快照保护（仅开发测试环境），可使用更简洁的更新脚本：

```bash
./scripts/update.sh              # 更新全部
./scripts/update.sh backend      # 仅更新后端
./scripts/update.sh admin-web    # 仅更新管理后台
./scripts/update.sh h5           # 仅更新 H5
```

> **生产环境务必使用 `upgrade.sh` 或 `canteen` 菜单**，不要用 `update.sh`。

### 4.6 手动回退到指定快照

通过菜单：`canteen` → `5`（恢复备份）→ 选择快照序号

或命令行：

```bash
# 列出所有快照
./scripts/snapshot.sh list

# 恢复到指定快照(序号或 ID)
./scripts/snapshot.sh restore 0
./scripts/snapshot.sh restore 20260802_180000
```

手动恢复会：
1. 恢复数据库（需输入 `yes` 确认）
2. 恢复 deploy/ 产物
3. 回退代码到快照时的 commit
4. 重启所有服务
5. 健康检查

### 4.7 快照管理

快照存储在 `backup/snapshots/<时间戳>/` 目录，每次升级自动创建，保留最近 5 个。

```bash
# 创建手动快照
./scripts/snapshot.sh create "上线前备份"

# 列出所有快照
./scripts/snapshot.sh list

# 清理旧快照(保留最近 10 个)
./scripts/snapshot.sh clean 10

# 查看最新快照 ID
./scripts/snapshot.sh latest
```

---

## 五、数据备份与恢复

### 5.1 手动备份

```bash
./scripts/backup.sh                    # 自动命名（时间戳）
./scripts/backup.sh my_backup_name     # 自定义名称
```

备份文件输出到 `backup/` 目录（`.tar.gz` 格式，含 `database.sql`）。

### 5.2 定时备份

配置 cron 定时任务：

```bash
crontab -e
```

添加（路径替换为实际项目路径）：

```
# 每天凌晨 2 点备份
0 2 * * * /opt/canteen/scripts/cron_backup.sh
```

备份日志记录在 `logs/backup.log`，自动清理 30 天前的旧备份。

### 5.3 恢复数据

```bash
./scripts/restore.sh backup/<备份文件名>.tar.gz
```

恢复后建议重启后端刷新缓存：

```bash
docker compose restart backend
```

> 也可通过管理后台 UI 上传 `.json.gz` 格式的应用层备份文件进行恢复（与管理后台导出格式一致）。

### 5.4 两种备份格式说明

| 格式 | 来源 | 恢复方式 |
|------|------|---------|
| `.tar.gz`（含 database.sql） | `scripts/backup.sh`（mysqldump） | `scripts/restore.sh` |
| `.json.gz` | 管理后台 UI 导出（应用层） | 管理后台 UI 上传恢复 |

---

## 六、环境变量配置

### 6.1 .env 文件

部署向导自动生成 `.env` 文件，包含以下变量：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | 部署时设置/随机生成 |
| `JWT_SECRET` | JWT 签名密钥 | 随机生成 64 位十六进制 |
| `JWT_EXPIRATION` | 管理后台 Token 过期（毫秒） | 86400000（24h） |
| `JWT_EMPLOYEE_EXPIRATION` | H5 员工 Token 过期（毫秒） | 2592000000（30d） |
| `JWT_TERMINAL_EXPIRATION` | 终端 Token 过期（毫秒） | 31536000000（365d） |
| `INIT_ADMIN_USERNAME` | 初始超管账号（首次部署） | 部署时设置 |
| `INIT_ADMIN_PASSWORD` | 初始超管密码（首次部署） | 部署时设置 |
| `INIT_ADMIN_FORCE` | 强制更新超管密码（reset-admin 用） | 未设置 |

### 6.2 手动修改配置

```bash
# 编辑 .env
vi .env

# 重启后端使配置生效
docker compose restart backend
```

> 参考模板：`.env.example`

---

## 七、开机启动

部署向导第 7 步会自动配置开机启动，包含两部分：

### 7.1 Docker 服务开机启动

```bash
systemctl enable docker   # 已由部署脚本自动执行
```

### 7.2 应用服务开机启动

部署脚本创建 systemd 服务 `canteen.service`，服务器重启后自动拉起 Docker Compose：

```ini
# /etc/systemd/system/canteen.service
[Unit]
Description=Enterprise Canteen System (Docker Compose)
Requires=docker.service
After=docker.service network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=<项目目录>
ExecStart=/usr/bin/docker compose up -d
ExecStop=/usr/bin/docker compose down
TimeoutStartSec=300

[Install]
WantedBy=multi-user.target
```

### 7.3 管理开机启动

```bash
# 查看状态
systemctl status canteen

# 禁用开机启动
sudo systemctl disable canteen

# 重新启用
sudo systemctl enable canteen

# 手动通过 systemd 启动/停止(等同于 docker compose up/down)
sudo systemctl start canteen
sudo systemctl stop canteen
```

> 日常运维建议使用 `canteen` 菜单或 `docker compose` 命令，systemd 服务主要用于开机自启场景。

---

## 八、X86 终端部署

X86 终端为 Windows 独立安装包，用于食堂现场刷卡订餐。

### 8.1 打包

在 Windows 上运行：

```bash
cd src-python
python build_installer.py
```

产物：`output/CanteenTerminal-Setup-1.0.0.exe`

### 8.2 安装与使用

1. 双击安装包，按向导完成安装
2. 启动后进入全屏无边框模式
3. 点击右上角 6 次（2 秒内）进入管理模式
4. 输入管理员密码，配置后端地址（`https://canteen.908521.xyz` 或自部署地址）
5. 绑定食堂：使用店长账号登录绑定本终端到对应食堂

### 8.3 卸载

卸载程序会自动关闭运行中的进程并清理：
- 安装目录（`C:\Program Files\CanteenTerminal`）
- 应用数据（`%APPDATA%\CanteenTerminal`）
- 本地缓存（`%LOCALAPPDATA%\CanteenTerminal`）

---

## 九、目录结构

```
enterprise-canteen/
├── canteen.sh                 # 服务器管理面板（输入 canteen 打开）
├── deploy.sh                  # 部署 CLI 入口
├── docker-compose.yml         # Docker 编排配置
├── .env / .env.example        # 环境变量
├── scripts/
│   ├── build.sh              # 构建产物（Docker 容器内构建）
│   ├── upgrade.sh            # 安全升级（含快照+自动回退）
│   ├── update.sh             # 快速更新（无备份，开发用）
│   ├── snapshot.sh           # 快照管理（创建/列出/恢复/清理）
│   ├── backup.sh             # 数据库备份（mysqldump）
│   ├── restore.sh            # 数据库恢复
│   └── cron_backup.sh        # 定时备份 cron 脚本
├── backend/                   # Spring Boot 后端
├── admin-web/                 # 管理后台前端（Vue 3）
├── h5/                        # H5 订餐端前端（Vue 3）
├── src-python/               # X86 终端（Python + PyQt5）
├── deploy/                    # 构建产物输出目录
│   ├── backend/app.jar
│   ├── admin-web/{html,nginx.conf}
│   └── h5/{html,nginx.conf}
├── backup/                    # 备份文件
│   └── snapshots/            # 升级快照（数据库+产物+代码版本）
├── uploads/                   # 上传文件
└── logs/                      # 日志
```

---

## 十、常见问题

### Q: 部署后访问管理后台显示空白

A: 前端可能还在启动中，等待 30 秒后刷新。使用 `./deploy.sh status` 检查服务状态。

### Q: 后端启动失败

A: 查看日志定位问题：

```bash
./deploy.sh logs backend
```

常见原因：MySQL 未就绪（等待 healthcheck 通过）、`.env` 配置缺失。

### Q: 店长账号无法登录管理后台

A: 确保超管已在「账号管理」中创建了该店长账号并指派到对应食堂。密码至少 8 位。

### Q: X86 终端连接后端失败

A: 检查终端配置的后端地址是否正确，确保服务器 8080 端口对局域网开放。

### Q: 如何修改 MySQL 密码

A: 修改 `.env` 中的 `MYSQL_ROOT_PASSWORD`，然后：

```bash
docker compose down
docker volume rm enterprise-canteen_mysql_data  # ⚠️ 会丢失数据，先备份！
docker compose up -d
```

> 修改 MySQL 密码需重建数据卷，务必先备份。生产环境不建议修改。

### Q: 升级后前端没变化

A: 浏览器缓存问题。强制刷新（Ctrl+Shift+R）或清除缓存。nginx 已配置 index.html 禁止缓存。

---

## 十一、安全注意事项

1. **生产环境必须修改默认密码** — 部署向导中设置自定义超管密码
2. **妥善保管 .env 文件** — 包含 MySQL 密码、JWT 密钥等敏感信息，已通过 `.gitignore` 忽略
3. **定期备份** — 配置 cron 定时备份，备份文件不要与服务器同机存放
4. **端口暴露** — 仅 80/81/8080 对外开放，3306/6379 仅绑定 127.0.0.1
5. **HTTPS** — 生产环境建议在 nginx 前端加 HTTPS 反向代理（如 Nginx + Let's Encrypt）
6. **密码策略** — 全系统统一最低 8 位密码校验（管理后台、H5、终端）
