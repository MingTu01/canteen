# 企业智慧食堂系统 - 部署运维指南

## 系统要求

| 项目 | 要求 |
|------|------|
| 操作系统 | CentOS 7+/8/9、Ubuntu 18.04+/20.04/22.04/24.04、Debian 10+ |
| 内存 | ≥ 2GB（推荐 4GB） |
| 磁盘 | ≥ 10GB（含 Docker 镜像 + 数据） |
| 端口 | 18080（管理后台）、18081（H5 订餐）、18082（后端 API）、13306/16379（仅 127.0.0.1） |
| 软件 | Docker + Docker Compose（由部署脚本自动安装） |

> X86 终端为独立 Windows 安装包，不参与 Docker 部署，详见 `src-python/` 目录。

---

## 〇、分支架构说明（v0.7.0+）

本仓库采用**双分支架构**，将源代码与部署产物分离：

| 分支 | 用途 | 内容 | 谁使用 |
|------|------|------|--------|
| `main` | 源代码 | backend/admin-web/h5 源码 + 构建脚本 | 开发机 |
| `deploy` | 部署产物（orphan 分支，独立历史） | 构建产物(jar/dist) + docker-compose.yml + 运行时脚本 + install.sh | 服务器 |

**核心优势：**
- 服务器**无需安装 Maven / Node.js**，秒级更新（git pull + docker restart）
- 杜绝服务器构建失败导致的升级回退（如 TypeScript 严格模式报错）
- 源码与产物版本原子化绑定（CI 一次构建同时推送）
- **一行命令部署**：服务器执行 install.sh 即可完成全部部署

**CI 自动构建发布（推荐）：**

推送 `main` 分支后，GitHub Actions 自动构建后端/管理后台/H5 产物，组装并推送到 `deploy` 分支（`.github/workflows/deploy.yml`）。CI 在 Linux runner 上设置脚本可执行位，彻底规避 Windows 不保留 +x 导致的权限问题。

```bash
# 1. 在 main 分支修改代码并提交
git add -A && git commit -m "feat: xxx"
git push origin main
# 2. GitHub Actions 自动构建并发布到 deploy 分支(无需手动操作)
```

> CI 不可用时，仍可在开发机手动发布：`./scripts/publish.sh all`（需 Maven/Node.js 环境）。

**服务器更新流程（deploy 分支，免构建）：**

```bash
canteen          # 菜单 → 1) 升级全部
# 或
canteen upgrade all
```

> `upgrade.sh` 会自动检测当前分支：deploy 分支免构建（5 步），main 分支需构建（6 步）。

---

## 一、首次部署

### 1.1 一键部署（推荐）

> **一行命令完成全部部署**：`install.sh` 自动检查环境、安装 git、克隆 deploy 分支（含 CI 预构建产物）、创建专用用户、修正权限，然后调用 `deploy.sh` 引导你设置超管账号密码。彻底解决权限和密码易出错的问题。

```bash
# 在任意全新服务器上执行一行命令(root 或 sudo):
curl -fsSL https://raw.githubusercontent.com/MingTu01/canteen/deploy/install.sh -o /tmp/canteen-install.sh && sudo bash /tmp/canteen-install.sh

# 或指定安装目录:
sudo bash /tmp/canteen-install.sh /opt/my-canteen
```

`install.sh` 自动完成：
1. **环境检查**：root 权限、基础依赖（curl/tar/gzip）、磁盘空间（≥2GB）
2. **安装 git**：如缺失自动安装（apt-get/yum）
3. **克隆仓库**：以实际用户身份克隆 deploy 分支（含 CI 预构建产物，无需编译）
4. **创建专用用户**：root 直接运行时自动创建 `canteen` 用户
5. **全面修正权限**：目录所有权、脚本 +x、git safe.directory、运行时目录、.env 600
6. **调用 deploy.sh**：以实际用户身份启动部署向导（curl|bash 模式自动重定向 stdin）

> **国内服务器加速**：若 GitHub raw 访问慢，可先手动 clone 再运行：
> ```bash
> git clone -b deploy https://api.gitproxy.dev/https://github.com/MingTu01/canteen.git /opt/canteen
> cd /opt/canteen && sudo bash install.sh
> ```

部署向导（deploy.sh）会交互式引导你完成以下步骤：

1. **检测 Docker 环境**（已安装自动跳过，未安装则使用阿里云源安装）
   - 自动把当前用户加入 docker 组（避免后续 canteen 需要 sudo）
2. **配置镜像加速器**（已有配置会询问是否替换 y/n，首次自动配置国内加速源）
3. **配置环境变量**：
   - MySQL 密码（自动生成随机高强度密码，重配时复用旧值不破坏已有数据库）
   - JWT 密钥（自动生成 64 位随机十六进制）
   - **超管账号密码**（自定义设置，至少 8 位；密码含特殊字符安全保留，不做 shell 展开）
   - 自动 chown `.env` 给当前用户，权限 600
4. **构建运行时镜像**（首次需要，仅含 JRE+curl，不含业务代码；deploy 分支已含产物，无需构建）
5. **启动服务**（分阶段启动：MySQL+Redis 健康 → 创建应用用户 → backend+前端，消除竞态）
6. **配置开机启动**（systemd service，服务器重启后自动恢复服务）
7. **安装 canteen 命令**（自动安装到系统 PATH，无需单独操作）
8. **部署验证**（健康检查 + 超管账号落库校验 + 访问地址输出）
   - `fix_all_permissions` 统一修正权限（目录所有权/脚本+x/git safe.directory/.env 600/Docker组）

> **权限与密码安全保障（v0.7.0+）：**
> - `install.sh` 以实际用户身份克隆，避免文件归 root 所有；root 直接运行时自动创建专用用户 `canteen`
> - `deploy.sh` 的 `fix_all_permissions` 统一处理所有权限问题（目录所有权/脚本+x/git safe.directory/运行时目录/.env 600/Docker组）
> - 密码处理用 `escape_env_value` 转义特殊字符 + `write_env_line`/printf 逐行写入 .env（不做 shell 展开），密码含 `$`/单引号/反引号/反斜杠均原样保留
> - `read_password` 有界 3 次重试 + 空值防护 + 非交互环境明确失败，杜绝静默写入空密码
> - `verify_admin_initialized` 先校验超管落库、后清理 .env 中的 INIT_ADMIN_PASSWORD，未确认则保留提示
> - 部署完成后**重新登录**让 docker 组生效，否则 `canteen` 命令仍需 sudo

### 1.2 ZIP 包部署（国内服务器推荐）

> **适用场景：** GitHub 加速器下载 deploy 分支慢/失败、或 deploy 分支因含 45MB jar 推送超时无法同步时，使用 ZIP 包手动上传部署。这是**最可靠**的部署方式，不依赖 git。

#### 步骤 1：获取 ZIP 包

开发机构建后生成的 ZIP 包：`canteen-deploy-v0.0.8.zip`（约 47MB），包含全部部署产物。

#### 步骤 2：上传到服务器

```bash
# 用 scp 上传（替换为你的服务器 IP 和用户名）
scp canteen-deploy-v0.0.8.zip canteen@<服务器IP>:/tmp/

# 或用 1Panel 面板上传到 /tmp/ 目录
```

#### 步骤 3：解压并部署

```bash
# 用普通用户登录(如 canteen / ubuntu),不要用 root
sudo mkdir -p /opt/canteen
sudo chown -R $(whoami):$(whoami) /opt/canteen

# 解压 ZIP 包到 /opt/canteen
cd /opt/canteen
unzip /tmp/canteen-deploy-v0.0.8.zip
chmod +x *.sh scripts/*.sh

# 用 sudo 运行 deploy.sh
sudo ./deploy.sh
```

#### 步骤 4：后续升级

ZIP 包部署后，服务器**没有 git 仓库**，无法用 `canteen upgrade`（依赖 git pull）。后续升级方式：

```bash
# 方式一：再次上传新 ZIP 包覆盖升级（最简单）
# 1. 开发机生成新 ZIP 包
# 2. 上传到服务器 /tmp/
# 3. 在服务器执行：
cd /opt/canteen
unzip -o /tmp/canteen-deploy-v*.zip
chmod +x *.sh scripts/*.sh
docker compose up -d
# 4. 健康检查
curl -s http://localhost:18082/api/system/health

# 方式二：初始化 git 仓库跟踪 deploy 分支（一次性，后续可用 canteen upgrade）
cd /opt/canteen
git init
git remote add origin https://api.gitproxy.dev/https://github.com/MingTu01/canteen.git
git fetch origin deploy
git checkout -b deploy FETCH_HEAD
# 此后可用 canteen upgrade 正常升级
```

> **ZIP 包部署 vs git clone 部署：**
> | 对比 | ZIP 包 | git clone deploy |
> |------|--------|-----------------|
> | 下载速度 | 快（单文件 47MB） | 慢（含 jar 的 git 历史） |
> | 后续升级 | 需重新上传 ZIP 或初始化 git | `canteen upgrade` 一键 |
> | 可靠性 | 高（不依赖 git） | 受网络影响 |
> | 推荐场景 | 国内服务器、网络不稳定 | 网络稳定环境 |

### 1.3 跳过 Docker 安装

如果服务器已安装 Docker：

```bash
sudo ./deploy.sh --skip-env
```

### 1.4 部署后访问

部署完成后会输出访问地址（IP 根据服务器自动检测）：

| 服务 | 地址 |
|------|------|
| 管理后台 | `http://<服务器IP>:18080` |
| H5 订餐端 | `http://<服务器IP>:18081` |
| 后端 API | `http://<服务器IP>:18082` |

使用部署时设置的超管账号密码登录管理后台。

### 1.5 canteen 管理命令（部署时自动安装）

部署向导的第 7 步会自动安装 `canteen` 系统命令，无需单独操作。部署完成后在服务器任意目录输入 `canteen` 即可弹出管理面板：

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
║  系统版本: v0.7.0    状态: ● 全部运行中       ║
║  后端: v0.0.16  管理后台: v0.0.16  H5: v0.0.14 ║
║  终端: v1.0.4  分支: deploy                   ║
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
║   7) 查看服务状态 (含容器资源使用+运行时间)   ║
║   8) 重置管理员密码                          ║
║   9) 查看日志                                ║
║  10) 重启服务                                ║
║  11) 停止服务                                ║
║                                              ║
║  【系统】                                     ║
║  12) 修复 canteen 系统命令                   ║
║  13) 查看版本详情与更新日志                  ║
║  14) 系统诊断 (OS/CPU/内存/磁盘/Docker/端口)  ║
║  15) 清理 Docker 镜像 (释放磁盘空间)          ║
║  16) 查看配置信息 (.env 关键配置脱敏展示)     ║
║                                              ║
║   0) 退出                                     ║
╚══════════════════════════════════════════════╝
```

> 菜单顶部显示当前分支（`deploy` 或 `main` 或 `detached`），升级步骤会根据分支自动适配。
> 每次操作前自动执行权限自愈（`self_heal_permissions`），检测并修复运行时目录/.env/脚本+x/git safe.directory/Docker组等常见权限问题。

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

### 4.3 安全升级链路（自动执行，分支感知）

升级是高风险操作，本系统设计了完整的安全链路，**每一步都有保护**。

**deploy 分支（服务器，免构建，5 步）：**

```
┌─────────────────────────────────────────────────┐
│         安全升级流程 - deploy 分支               │
├─────────────────────────────────────────────────┤
│                                                 │
│  ① 创建升级前快照                               │
│     ├─ 数据库 mysqldump 压缩                    │
│     ├─ deploy/ 产物打包                         │
│     ├─ git commit SHA 记录                      │
│     └─ 版本号记录                               │
│           │                                     │
│           ▼                                     │
│  ② git pull origin deploy (拉取最新产物)        │
│           │                                     │
│           ▼                                     │
│  ③ docker compose up -d ── 失败 ──▶ 自动回退    │
│           │                                     │
│           ▼                                     │
│  ④ 健康检查 (等待 120s) ── 失败 ──▶ 自动回退    │
│           │                                     │
│           ▼                                     │
│  ⑤ 成功 → 清理旧快照(保留最近5个)               │
│                                                 │
└─────────────────────────────────────────────────┘
```

**main 分支（开发机，需构建，6 步）：**

```
┌─────────────────────────────────────────────────┐
│          安全升级流程 - main 分支                │
├─────────────────────────────────────────────────┤
│                                                 │
│  ① 创建升级前快照                               │
│           │                                     │
│           ▼                                     │
│  ② git pull origin main (拉取最新源码)          │
│           │                                     │
│           ▼                                     │
│  ③ build.sh 重建产物 ────── 失败 ──▶ 自动回退   │
│           │                                     │
│           ▼                                     │
│  ④ docker compose up -d ── 失败 ──▶ 自动回退    │
│           │                                     │
│           ▼                                     │
│  ⑤ 健康检查 (等待 120s) ── 失败 ──▶ 自动回退    │
│           │                                     │
│           ▼                                     │
│  ⑥ 成功 → 清理旧快照(保留最近5个)               │
│                                                 │
└─────────────────────────────────────────────────┘
```

**detached HEAD 自动修复：** 如果服务器处于 detached HEAD 状态（历史遗留问题），`upgrade.sh` 会自动切换到 `deploy` 分支并继续升级，无需手动处理。

**自动回退机制：** 当构建失败、重启失败或健康检查超时时，系统自动执行回退：
1. 恢复 `deploy/` 产物到升级前状态
2. 恢复数据库到升级前状态
3. `git reset --hard` 回退代码到升级前 commit（保持分支上下文，避免 detached HEAD）
4. 重启所有服务
5. 回退后健康检查，确认服务恢复正常

> 这意味着即使升级出问题，系统也能自动恢复到升级前的正常状态，不会崩溃。
> 注意：回退使用 `git reset --hard` 而非 `git checkout`，避免进入 detached HEAD 状态导致后续 `git pull` 失败。

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

### 4.5 快速更新（无备份，仅 main 分支开发用）

> **注意：** `update.sh` 依赖 `build.sh` 本地构建，仅适用于 `main` 分支的开发测试环境。`deploy` 分支服务器请使用 `upgrade.sh` 或 `canteen` 菜单。

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
3. `git reset --hard` 回退代码到快照时的 commit（保持分支上下文）
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

### 4.8 完全清理重部署（从零开始）

当服务器出现严重问题（权限混乱、detached HEAD 无法修复、容器状态异常）时，可执行完全清理重部署。

> **⚠️ 警告：** 此流程会删除所有容器和数据卷（数据库数据将丢失），执行前务必备份！

#### 阶段一：创建 deploy 分支（仅首次需要，已有 deploy 分支可跳过）

如果远程仓库还没有 `deploy` 分支，需要在有 Maven/Node.js 的机器（开发机或服务器）上运行一次 `publish.sh` 创建：

```bash
# 切换到 main 分支并拉取最新代码
git checkout main
git pull origin main
chmod +x *.sh scripts/*.sh

# 构建全部产物并发布到 deploy 分支
./scripts/publish.sh all
```

验证 deploy 分支已创建：

```bash
git ls-remote origin deploy
```

应显示一个 commit SHA。

#### 阶段二：备份现有数据

```bash
# 备份 .env 文件(含生产密码和密钥)
cp /opt/canteen/.env /tmp/canteen-env-backup

# 备份数据库
source /opt/canteen/.env
docker exec canteen-mysql mysqldump -uroot -p"${MYSQL_ROOT_PASSWORD}" --single-transaction canteen > /tmp/canteen-db-backup.sql

# 验证备份文件非空
ls -lh /tmp/canteen-db-backup.sql
```

#### 阶段三：清理服务器

```bash
# 停止并删除所有容器
cd /opt/canteen
docker compose down

# 删除旧代码目录
cd /tmp
sudo rm -rf /opt/canteen

# (可选)删除 Docker 数据卷 — 会丢失所有数据库数据!
# 如需保留数据库数据,跳过此步骤,数据卷会在阶段四自动复用
docker volume rm canteen_mysql_data canteen_redis_data
```

#### 阶段四：重新部署（两种方式任选）

> **权限问题根源：** 历史上多次出现 `.env 被 root 所有`、`deploy/ 目录被 root 所有`、`docker compose 权限拒绝`，根源是混用 sudo 和普通用户。下面的流程严格区分：**系统级操作用 sudo，业务文件归普通用户**。

**方式 A：ZIP 包部署（推荐，不依赖 git，最可靠）**

```bash
# 用普通用户登录(如 canteen / ubuntu),不要用 root
sudo mkdir -p /opt/canteen
sudo chown -R $(whoami):$(whoami) /opt/canteen

# 解压 ZIP 包(已上传到 /tmp/)
cd /opt/canteen
unzip /tmp/canteen-deploy-v*.zip
chmod +x *.sh scripts/*.sh

# 用 sudo 运行 deploy.sh
sudo ./deploy.sh
```

**方式 B：git clone deploy 分支**

**步骤 1：用普通用户克隆（不要用 sudo clone）**

```bash
# 用你的普通用户(如 canteen / ubuntu)登录,不要用 root
# /opt 通常需要 root 创建,所以先 sudo mkdir 再 chown
sudo mkdir -p /opt/canteen
sudo chown -R $(whoami):$(whoami) /opt/canteen

# 用普通用户克隆(这样 /opt/canteen 直接归你所有,无需后续 chown)
git clone -b deploy https://api.gitproxy.dev/https://github.com/MingTu01/canteen.git /opt/canteen
cd /opt/canteen
chmod +x *.sh scripts/*.sh
```

**步骤 2：用 sudo 运行 deploy.sh（仅 Docker 安装和系统命令需要 sudo）**

```bash
sudo ./deploy.sh
```

`deploy.sh` 内部已自动处理权限（v0.0.5+）：
- `fix_ownership()`：sudo 运行时自动把项目目录 chown 给实际调用者（SUDO_USER）
- `add_user_to_docker_group()`：自动把 SUDO_USER 加入 docker 组（避免后续 canteen 需要 sudo）
- `.env` 创建后立即 chown 给 SUDO_USER

**步骤 3：重新登录让 docker 组生效**

```bash
# deploy.sh 会提示:已加入 docker 组,需重新登录后生效
exit
# 重新 SSH 登录后,docker 组权限才会生效
```

或临时生效（不退出登录）：

```bash
newgrp docker
```

**步骤 4：验证权限正确（关键检查点）**

```bash
cd /opt/canteen

# 1. 检查目录所有权(应显示你的用户名,不是 root)
ls -ld /opt/canteen
ls -l /opt/canteen/.env

# 2. 检查 docker 组成员(应包含你的用户名)
groups

# 3. 验证普通用户可运行 docker(无需 sudo)
docker compose ps

# 4. 验证 git 可用(无 dubious ownership 错误)
git status
```

如果以上 4 项都通过，后续 `canteen` 命令和 `git pull` 不会再有权限问题。

**步骤 5：恢复 .env 配置（如果有备份）**

```bash
cp /tmp/canteen-env-backup /opt/canteen/.env
chmod 600 /opt/canteen/.env
# 所有者应已是当前用户(deploy.sh 的 fix_ownership 已处理)
```

**步骤 6：构建后端运行时镜像并启动**

```bash
cd /opt/canteen

# 构建后端运行时镜像(首次需要,仅含 JRE+curl,不含业务代码)
docker compose build backend

# 启动所有服务
docker compose up -d

# 安装 canteen 系统命令(这一步需要 sudo,因为写入 /usr/local/bin)
sudo ./canteen.sh install
```

#### 阶段五：验证部署

```bash
# 检查健康状态
canteen status
# 或直接 curl 检查
curl -s http://localhost:18082/api/system/health

# 验证前端可访问(应返回 200)
curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/
curl -s -o /dev/null -w "%{http_code}" http://localhost:18081/

# 恢复数据库(如果阶段三删除了数据卷)
source /opt/canteen/.env
docker exec -i canteen-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" canteen < /tmp/canteen-db-backup.sql
docker compose restart backend

# 验证登录
# 浏览器访问 http://<服务器IP>:18080 管理后台,使用超管账号登录测试
```

#### 权限注意事项（历史教训总结）

1. **目录所有权**：`/opt/canteen` 必须归 `canteen:canteen` 所有
   ```bash
   sudo chown -R canteen:canteen /opt/canteen
   ```
2. **.env 权限**：必须可写，建议 `chmod 600` + `chown canteen:canteen`
3. **Git safe.directory**：避免 "dubious ownership" 错误
4. **脚本可执行位**：Windows 仓库不保留 +x，每次 pull 后需 `chmod +x *.sh scripts/*.sh`（`canteen.sh` 和 `upgrade.sh` 已自动处理）
5. **deploy 目录权限**：不要用 sudo 运行 build，避免 `deploy/` 被 root 所有
6. **禁止混用 sudo**：要么全程普通用户，要么全程 sudo，混用会导致文件所有权混乱

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

### main 分支（源代码，开发机）

```
enterprise-canteen/
├── install.sh                 # 一键安装脚本（GitHub 一行命令部署入口）
├── canteen.sh                 # 服务器管理面板（输入 canteen 打开）
├── deploy.sh                  # 部署 CLI 入口
├── pack_deploy_zip.py         # 打包 deploy 分支为 zip（离线部署用）
├── docker-compose.yml         # Docker 编排配置
├── VERSIONS.json              # 版本号集中管理
├── .env / .env.example        # 环境变量
├── .github/workflows/
│   └── deploy.yml            # GitHub Actions CI（自动构建并发布到 deploy 分支）
├── scripts/
│   ├── build.sh              # 构建产物（Docker 容器内构建）
│   ├── publish.sh            # 构建并发布到 deploy 分支（CI 不可用时手动用）
│   ├── upgrade.sh            # 安全升级（分支感知：deploy免构建/main构建）
│   ├── update.sh             # 快速更新（无备份，仅 main 分支开发用）
│   ├── snapshot.sh           # 快照管理（创建/列出/恢复/清理）
│   ├── backup.sh             # 数据库备份（mysqldump）
│   ├── restore.sh            # 数据库恢复
│   └── cron_backup.sh        # 定时备份 cron 脚本
├── backend/                   # Spring Boot 后端源码 + Dockerfile.runtime
├── admin-web/                 # 管理后台前端（Vue 3）
├── h5/                        # H5 订餐端前端（Vue 3）
├── src-python/               # X86 终端（Python + PyQt5）
├── deploy/                    # 构建产物输出目录（.gitignore 忽略）
│   ├── backend/app.jar
│   ├── admin-web/{html,nginx.conf}
│   └── h5/{html,nginx.conf}
├── backup/                    # 备份文件
│   └── snapshots/            # 升级快照（数据库+产物+代码版本）
├── uploads/                   # 上传文件
└── logs/                      # 日志
```

### deploy 分支（部署产物，服务器，orphan 分支独立历史）

```
enterprise-canteen/            # 服务器 /opt/canteen
├── install.sh                 # 一键安装脚本（curl 获取后运行）
├── canteen.sh                 # 服务器管理面板
├── deploy.sh                  # 部署 CLI 入口
├── docker-compose.yml         # Docker 编排配置
├── VERSIONS.json              # 版本号集中管理
├── .env.example               # 环境变量模板(.env 不入库)
├── backend/
│   └── Dockerfile.runtime     # 后端运行时镜像构建文件
├── scripts/
│   ├── upgrade.sh            # 安全升级（分支感知，deploy 分支免构建）
│   ├── snapshot.sh           # 快照管理
│   ├── backup.sh             # 数据库备份
│   └── restore.sh            # 数据库恢复
└── deploy/                    # 构建产物（直接可用，无需构建）
    ├── backend/app.jar
    ├── admin-web/{html,nginx.conf}
    └── h5/{html,nginx.conf}
```

> `deploy` 分支不含源码（backend/admin-web/h5 源码目录）、不含 build.sh/publish.sh/update.sh，服务器无需 Maven/Node.js。

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

A: 检查终端配置的后端地址是否正确，确保服务器 18082 端口对局域网开放。

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

### Q: git pull 报错"您当前不在一个分支上"

A: 服务器处于 detached HEAD 状态（历史遗留问题）。解决方法：

```bash
# 方法1:运行 upgrade.sh,会自动切换到 deploy 分支
canteen upgrade all

# 方法2:手动切换到 deploy 分支
git checkout deploy
git pull origin deploy
```

> v0.0.5+ 的 `upgrade.sh` 会自动检测并修复 detached HEAD 状态，无需手动处理。

### Q: git pull 报错 "detected dubious ownership"

A: 项目目录所有权与当前用户不一致。修复：

```bash
sudo chown -R canteen:canteen /opt/canteen
git config --global --add safe.directory /opt/canteen
```

### Q: 部署后 .env 无法写入（权限拒绝）

A: `.env` 被 root 所有（通常因 sudo 运行 deploy.sh 导致）。修复：

```bash
sudo chown canteen:canteen /opt/canteen/.env
chmod 600 /opt/canteen/.env
```

### Q: deploy 分支不存在怎么办

A: `deploy` 分支需要由开发机通过 `publish.sh` 首次创建。在有 Maven/Node.js 的机器上：

```bash
git checkout main
git pull origin main
./scripts/publish.sh all
```

创建后服务器即可 `git clone -b deploy` 克隆。

---

## 十一、安全注意事项

1. **生产环境必须修改默认密码** — 部署向导中设置自定义超管密码
2. **妥善保管 .env 文件** — 包含 MySQL 密码、JWT 密钥等敏感信息，已通过 `.gitignore` 忽略
3. **定期备份** — 配置 cron 定时备份，备份文件不要与服务器同机存放
4. **端口暴露** — 仅 18080/18081/18082 对外开放，13306/16379 仅绑定 127.0.0.1
5. **HTTPS** — 生产环境建议在 nginx 前端加 HTTPS 反向代理（如 Nginx + Let's Encrypt）
6. **密码策略** — 全系统统一最低 8 位密码校验（管理后台、H5、终端）
