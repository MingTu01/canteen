# 企业智慧食堂预定餐系统

> 版本：V1.0 ｜ 更新日期：2026-08-01

集团级企业智慧食堂预定餐系统，支持多门店数据隔离、多端适配（管理后台、H5 订餐端、X86 终端），采用企业级架构标准。

## 快速部署（生产环境）

```bash
# 国内推荐使用 GitHub 加速器克隆（任选其一）
git clone https://ghproxy.net/https://github.com/MingTu01/canteen.git /opt/canteen
cd /opt/canteen

# 一键部署（自动安装 Docker + 国内源 + 构建 + 启动）
chmod +x deploy.sh
sudo ./deploy.sh
```

部署完成后访问 `http://服务器IP`，默认账号 `admin / 123456`。

> 详细部署与更新流程见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 25 + Spring Boot 3.5.x + MyBatis Plus + MySQL 8.0 + Redis 7 |
| 管理后台 | Vue 3 + TypeScript + Vite 5 + Element Plus + Tailwind CSS 4 |
| H5 订餐端 | Vue 3 + TypeScript + Vite 5 + Tailwind CSS 4 |
| X86 终端 | Vue 3 + TypeScript + Vite 5 + Tailwind CSS 4 |
| 终端桌面壳 | Python 3.10（32 位）+ PyQt5 + QWebEngineView（兼容 Win7/Win7 32 位） |
| 认证 | JWT Token + HttpOnly Cookie + BCrypt 密码加密 |
| 数据库迁移 | Flyway（V1~V13）+ SchemaMigrationRunner（增量补丁） |
| 部署 | Docker Compose（后端/管理后台/H5）+ PyInstaller + Inno Setup EXE 安装包（X86 终端） |

## 项目结构

```
canteen/
├── backend/                    # Spring Boot 后端
│   ├── src/main/java/com/example/canteen/
│   │   ├── controller/         # 控制器层
│   │   ├── service/            # 业务逻辑层
│   │   ├── mapper/             # 数据访问层
│   │   ├── entity/             # 实体类
│   │   ├── dto/                # 数据传输对象
│   │   ├── security/           # JWT 安全
│   │   ├── config/             # 配置类
│   │   └── migration/          # SchemaMigrationRunner 增量补丁
│   ├── src/main/resources/
│   │   ├── db/migration/       # Flyway 迁移脚本 V1~V14
│   │   ├── mapper/             # MyBatis XML
│   │   ├── application.yml     # 主配置
│   │   ├── application-dev.yml # H2 dev profile
│   │   ├── application-prod.yml
│   │   ├── schema-h2.sql       # H2 dev 建表脚本
│   │   └── version.json        # 版本信息
│   ├── src/test/               # 单元测试
│   ├── Dockerfile              # 完整构建镜像（备用方案）
│   ├── Dockerfile.runtime      # 运行时基础镜像（卷映射模式用）
│   └── pom.xml
├── admin-web/                  # 管理后台 (Vue 3)
├── h5/                         # H5 订餐端
├── terminal/                   # X86 终端前端 (Vue 3)
├── src-python/                 # X86 终端桌面壳 (Python + PyQt5)
├── scripts/                    # 运维脚本
│   ├── build.sh                # 构建产物脚本（Docker 容器中构建）
│   ├── update.sh               # 更新脚本（pull + build + restart）
│   ├── backup.sh               # 数据库备份
│   ├── restore.sh              # 数据库恢复
│   └── cron_backup.sh          # 定时备份入口
├── docs/                       # 需求文档（各端完整说明）
├── docker-compose.yml          # Docker 编排（卷映射模式，更新无需重建镜像）
├── deploy.sh                   # 一键部署（含 Docker 安装 + 国内源）
├── DEPLOYMENT.md               # 部署方案与更新流程
├── .env.example                # 环境变量模板
└── .gitignore
```

## 需求文档

各端的完整需求文档位于 `docs/` 目录：

| 文档 | 说明 |
|------|------|
| [01-后端服务.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/01-后端服务.md) | 后端 API、安全模型、数据模型、业务规则、Redis 缓存 |
| [02-管理后台.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/02-管理后台.md) | 管理后台 24 个路由、RBAC 权限、UI 组件、设计系统 |
| [03-H5订餐端.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/03-H5订餐端.md) | H5 订餐端登录、订餐、订单、个人中心、图片缓存策略 |
| [04-X86终端.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/04-X86终端.md) | X86 终端缓存策略、SSE 实时更新、管理入口、配置程序、读卡器、安全边界 |
| [05-PythonShell.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/05-PythonShell.md) | Python Shell 架构、读卡器集成、单实例限制、PyInstaller 打包、QtWebEngine 配置 |

## 快速开始

### 一键部署（生产环境）

```bash
# 1. 克隆项目（国内使用 GitHub 加速器）
git clone https://ghproxy.net/https://github.com/MingTu01/canteen.git /opt/canteen
cd /opt/canteen

# 2. 一键部署（自动安装 Docker + 配置国内源 + 构建 + 启动）
chmod +x deploy.sh
sudo ./deploy.sh
```

部署脚本会自动完成：Docker 安装 → 镜像加速器配置 → 环境变量生成 → 构建产物 → 启动服务 → 健康检查。

> 已安装 Docker 的服务器可使用 `./deploy.sh --skip-env` 跳过环境安装。

### 更新服务（不重建镜像）

```bash
cd /opt/canteen

# 更新全部
./scripts/update.sh

# 仅更新后端
./scripts/update.sh backend

# 仅更新前端
./scripts/update.sh admin-web    # 或 h5
```

更新脚本自动完成：`git pull` → 在 Docker 容器中构建产物 → 重启对应服务。**全程不涉及镜像重建**。

> X86 终端不在 Docker 中部署,需在 Windows 上运行 `src-python/build_installer.py` 打包为 EXE 安装包,详见 [DEPLOYMENT.md](DEPLOYMENT.md) 第十章。

### 本机开发（恢复测试数据）

```bash
# 部署后如需测试数据,手动执行 seed 脚本
docker cp scripts/seed-dev.sql canteen-mysql:/tmp/
docker exec canteen-mysql mysql -uroot -p<pwd> canteen -e "source /tmp/seed-dev.sql"

# 测试数据包含:2门店/5部门/5员工/13菜品/6菜单/3通知/3管理员
# 密码均为 123456
```

### 服务访问

| 端 | 地址 | 说明 |
|----|------|------|
| 管理后台 | http://localhost | admin / 123456 |
| H5 订餐端 | http://localhost:81 | 员工订餐 |
| 后端 API | http://localhost:8080 | `/api/system/health` 健康检查 |

> X86 终端为独立 Windows EXE 安装包,不在 Docker 中部署,默认连接 `https://canteen.908521.xyz`。

### 默认账号

| 角色 | 用户名 | 密码 | 权限 |
|------|--------|------|------|
| 超级管理员 | admin | 123456 | 所有门店 |
| 门店管理员 | store1 | 123456 | 总部食堂 |
| 门店管理员 | store2 | 123456 | 科技园食堂 |

> 首次登录后请立即在「管理员管理」修改密码。

## 版本规范

- 当前版本：**V0.0.1**
- 版本号统一管理：`MAJOR.MINOR.PATCH`（语义化版本）
- 版本号文件（修改时必须同步更新）：
  - `backend/src/main/resources/version.json`
  - `backend/pom.xml`
  - `admin-web/package.json`
  - `h5/package.json`
  - `terminal/package.json`

详细版本管理与升级流程见 [DEPLOYMENT.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/DEPLOYMENT.md)。

## 多租户数据隔离

所有业务表通过 `store_id` 字段实现行级数据隔离：
- 每个门店只能访问自己的数据
- 超级管理员可访问所有门店数据
- 门店管理员只能管理本门店

## X86 终端

终端使用 **Python + PyQt5 + QWebEngineView** 打包为正式 Windows EXE 安装包（`src-python/output/CanteenTerminal-Setup-1.0.0.exe`），**内置 CH375 读卡器驱动自动安装**，默认连接 `https://canteen.908521.xyz`，兼容 Win7/Win10/Win11 32/64 位。

> X86 终端不参与 Docker 部署,需在 Windows 打包机上运行 `src-python/build_installer.py` 生成安装包。详细打包流程见 [DEPLOYMENT.md](DEPLOYMENT.md) 第十章。

### 一键打包

```bash
cd src-python

# 完整打包（前端 + PyInstaller + Inno Setup）
python build_installer.py

# 产物: output/CanteenTerminal-Setup-1.0.0.exe
```

前置条件：Node.js 18+、Python 3.10 **32 位**（含 PyQt5/PyQtWebEngine/pyinstaller）、Inno Setup 6+、CH375 驱动文件放入 `src-python/drivers/`。

详细架构与配置说明见 [05-PythonShell.md](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/05-PythonShell.md)。

### config.json 配置

安装目录下 `config.json`（支持 `//` 行注释）配置以下字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `server_url` | string | `"https://canteen.908521.xyz"` | 预设后端服务器地址，绑定页面会自动填入；留空则要求手动输入。不要带末尾斜杠 `/`，不要带 `/api` 后缀 |
| `window_mode` | string | `"fullscreen"` | `"fullscreen"`（全屏无边框）或 `"windowed"`（1280×800 窗口） |
| `card_interval` | float | `2.5` | 读卡防抖间隔（秒），推荐 1.0~3.0 |
| `idle_timeout` | int | `120` | 无操作自动返回待机页时间（秒），0=永不 |

> 管理员密码验证由后端 `/api/admin/login` 接口完成（BCrypt），**config.json 中无密码字段**。

### 管理入口

运行模式下界面无管理按钮，**连续点击窗口右上角 6 下**（2 秒内）→ 弹出密码框（调后端验证）→ 验证通过显示三按钮菜单：
- **配置模式**：退出全屏，进入 `/settings` 配置页（绑定/解绑/切换模式）
- **退出**：关闭整个应用
- **取消**：返回运行模式

### 终端本地配置（localStorage）

通过设置页面配置：
1. 服务器 API 地址（默认已填 `https://canteen.908521.xyz`）
2. 管理员账号密码 + 食堂安全码（绑定签发终端 token）
3. 运行模式（订餐 / 取餐）

配置存储在终端本地，不同终端可独立配置。本地 IndexedDB 缓存菜品、菜品图片、菜单、员工头像数据，断网时仍可展示，**减少服务器压力**。

### CH375 读卡器驱动

内置 CH372/CH375/CH376 USB 芯片驱动（通过 ctypes 调 `OUR_IDR.dll` + `IDUSB.DLL`），支持 VID_4348&PID_5537 等多种芯片。**安装包安装时自动调用 pnputil 安装驱动**,无需手动运行驱动安装程序。

## 数据备份

- **自动备份**：每天凌晨 2:00，保留 30 份，自动清理最旧的
- **手动备份**：管理后台 → 备份恢复 → 立即备份
- **OS 级备份**：`./scripts/backup.sh`（与应用层备份互补）
- **恢复**：`./scripts/restore.sh backup/<文件名>.json.gz`

## 单元测试

```bash
cd backend
mvn test
```

测试覆盖：OrderService、DishService、EmployeeService、AdminService、BackupService、JwtTokenProvider、ApiResponse 等。

## 常用命令

```bash
docker compose ps                        # 服务状态
docker compose logs -f backend           # 后端日志
docker compose restart backend          # 重启后端
docker compose down                      # 停止全部
./scripts/build.sh backend              # 重新构建后端产物
./scripts/build.sh admin-web            # 重新构建管理后台产物
./scripts/update.sh                     # 一键更新（pull + build + restart）
./scripts/backup.sh                     # 数据备份
./scripts/restore.sh backup/<file>      # 数据恢复
```
