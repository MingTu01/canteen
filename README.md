# 企业智慧食堂预定餐系统

> 版本：V0.0.1 ｜ 更新日期：2026-07-28

集团级企业智慧食堂预定餐系统，支持多门店数据隔离、多端适配（管理后台、H5 订餐端、X86 终端），采用企业级架构标准。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 25 + Spring Boot 3.5.x + MyBatis Plus + MySQL 8.0 + Redis 7 |
| 前端 | Vue 3 + TypeScript + Vite 5 + Element Plus + Tailwind CSS 4 |
| 认证 | JWT Token + HttpOnly Cookie + BCrypt 密码加密 |
| 数据库迁移 | Flyway |
| 部署 | Docker Compose |

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
│   │   ├── db/migration/       # Flyway 迁移脚本 V1~V11
│   │   ├── mapper/             # MyBatis XML
│   │   ├── application.yml     # 主配置
│   │   ├── application-dev.yml # H2 dev profile
│   │   ├── application-prod.yml
│   │   ├── schema-h2.sql       # H2 dev 建表脚本
│   │   └── version.json        # 版本信息
│   ├── src/test/               # 单元测试
│   ├── Dockerfile
│   ├── pom.xml
│   └── settings-aliyun.xml    # Maven 国内源
├── admin-web/                  # 管理后台 (Vue 3)
├── h5/                         # H5 订餐端
├── terminal/                   # X86 终端
├── scripts/                    # 运维脚本
│   ├── backup.sh               # 数据库备份
│   ├── restore.sh              # 数据库恢复
│   ├── cron_backup.sh          # 定时备份入口
│   ├── upgrade.sh              # 一键升级
│   └── seed-dev.sql            # 本机开发测试数据
├── docs/                       # 文档
│   ├── 产品说明.md
│   ├── H5_订餐端需求书.md
│   └── X86终端需求书.md
├── docker-compose.yml          # Docker 编排(含日志轮转)
├── deploy.sh                   # 一键部署
├── DEPLOYMENT.md               # 部署方案与更新流程
├── .env.example                # 环境变量模板
└── .gitignore
```

## 快速开始

### 一键部署（生产环境）

```bash
# 1. 克隆项目
git clone <仓库地址> /opt/canteen
cd /opt/canteen

# 2. 配置环境变量
cp .env.example .env
vim .env   # 修改数据库密码与 JWT 密钥

# 3. 部署（Flyway 自动建表,初始仅有默认超管 admin/123456）
chmod +x deploy.sh
./deploy.sh
```

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
| X86 终端 | http://localhost:82 | 订餐机 / 取餐机 |
| 后端 API | http://localhost:8080 | `/api/system/health` 健康检查 |

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

详细版本管理与升级流程见 [DEPLOYMENT.md](file:///c:/Users/Administrator/Desktop/canteen/DEPLOYMENT.md)。

## 多租户数据隔离

所有业务表通过 `store_id` 字段实现行级数据隔离：
- 每个门店只能访问自己的数据
- 超级管理员可访问所有门店数据
- 门店管理员只能管理本门店

## X86 终端配置

终端使用 **Tauri** 打包为原生 EXE(`src-tauri/dist-portable/canteen-terminal.exe`),绿色免安装,**默认全屏无边框启动**。

### 配置文件 config.json

EXE 同目录放置 `config.json`(支持 `//` 行注释)配置以下字段:

| 字段 | 说明 | 默认值 |
|------|------|--------|
| `admin_password_hash` | 管理员密码的 SHA256 哈希(64 位十六进制),用于右上角 6 次点击后的密码验证 | "admin" 的哈希 |
| `server_url` | 预设后端服务器地址,绑定页面会自动填入;留空则要求手动输入 | "" |

修改后重启 EXE 生效。密码哈希生成方法见 [X86 终端需求书](file:///d:/文档/enterprise-canteen/enterprise-canteen/docs/X86终端需求书.md#81-configjson-配置说明)。

### 管理入口

运行模式下界面无管理按钮,**连续点击窗口右上角 6 下**(2 秒内)→ 弹出密码框 → 验证通过显示三按钮菜单:
- **配置模式**:退出全屏,进入 `/settings` 配置页(绑定/解绑/切换模式)
- **退出**:关闭整个应用
- **取消**:返回运行模式

### 终端本地配置(localStorage)

通过设置页面配置:
1. 服务器 API 地址
2. 管理员账号密码 + 食堂安全码(绑定签发终端 token)
3. 运行模式(订餐 / 取餐)

配置存储在终端本地,不同终端可独立配置。本地 IndexedDB 缓存菜品图片和菜单数据,断网时仍可展示。

### CH372 读卡器驱动

内置 CH372/CH375/CH376 USB 芯片驱动(位于 `src-tauri/drivers/CH372/`),支持 VID_4348&PID_5537 等多种芯片。首次部署时运行 `读写器驱动安装32or64bit.exe` 安装。

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
docker compose up -d --build backend     # 重建后端
./scripts/upgrade.sh                     # 一键升级
```

## 文档

- [部署方案与更新流程](file:///c:/Users/Administrator/Desktop/canteen/DEPLOYMENT.md)
- [产品说明](file:///c:/Users/Administrator/Desktop/canteen/docs/产品说明.md)
- [H5 订餐端需求书](file:///c:/Users/Administrator/Desktop/canteen/docs/H5_订餐端需求书.md)
- [X86 终端需求书](file:///c:/Users/Administrator/Desktop/canteen/docs/X86终端需求书.md)
