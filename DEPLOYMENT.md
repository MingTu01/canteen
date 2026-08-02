# 部署方案与更新流程(生产环境)

> 版本：V1.0 ｜ 更新日期：2026-08-01 ｜ 适用：企业智慧食堂预定餐系统

---

## 一、部署架构

### 1.1 设计理念：卷映射模式

本系统采用 **基础镜像 + 卷映射** 的部署方式，核心优势：

- **更新无需重建镜像**：业务产物（jar/dist）通过卷映射挂入容器，更新只需替换文件 + 重启容器
- **宿主机无需 JDK/Node.js**：所有构建在 Docker 容器中完成
- **国内源全链路加速**：Docker 镜像源、Maven 仓库、npm 仓库均使用国内镜像

### 1.2 服务拓扑

```
                    ┌──────────────────────────────────────────────────┐
                    │              服务器（2C4G 5M / 40GB）              │
                    │                                                  │
   用户/浏览器 ──────┼──> 80  ──> [admin-web nginx] ──┐                 │
                    │      81 ──> [h5       nginx] ──┤  /api/          │
   X86 终端(EXE) ───┼─────────────────────────────────┤  反向代理       │
                    │                                ├─────────────────┼──> [backend:8080]
                    │                                │     │           │     Spring Boot 3.5
                    │                                │     │           │     Java 25 + JWT
                    │                                │     │           │
                    │                                │     ├──> [redis:6379] (128MB LRU)
                    │                                │     │           │
                    │                                │     └──> [mysql:3306] (256MB buffer)
                    │                                                  │
                    └──────────────────────────────────────────────────┘

  说明:X86 终端为独立 Windows EXE 安装包,不参与 Docker 部署,
        通过 HTTPS 直连后端 API(默认地址 https://canteen.908521.xyz)。

  deploy/ 目录(卷映射,更新时只需替换文件):
  ├── backend/app.jar          → /app/app.jar
  ├── admin-web/html/          → /usr/share/nginx/html
  ├── admin-web/nginx.conf     → /etc/nginx/conf.d/default.conf
  ├── h5/html/                 → /usr/share/nginx/html
  └── h5/nginx.conf            → /etc/nginx/conf.d/default.conf
```

### 1.3 端口与目录映射

| 服务 | 端口 | 镜像 | 卷映射 | 用途 |
|------|------|------|--------|------|
| admin-web | 80 | nginx:alpine | deploy/admin-web/{html,nginx.conf} | 管理后台 |
| h5 | 81 | nginx:alpine | deploy/h5/{html,nginx.conf} | H5 订餐端 |
| backend | 8080 | canteen-backend-runtime(基于 JRE 25) | deploy/backend/app.jar | 后端 API |
| mysql | 127.0.0.1:3306 | mysql:8.0 | 卷 mysql_data | 数据库 |
| redis | 127.0.0.1:6379 | redis:7-alpine | 卷 redis_data | 缓存 |

> X86 终端为独立 Windows EXE 安装包,不参与 Docker 部署,打包方式见「第十章 X86 终端 EXE 安装包打包」。

### 1.4 持久化目录

| 主机目录 | 容器路径 | 用途 |
|----------|----------|------|
| ./deploy/ | 各容器 | 业务产物(jar/dist/conf) |
| ./backup/ | /app/backup | 数据库备份 |
| ./uploads/ | /app/uploads | 上传的图片文件 |
| ./logs/ | — | 运维日志(可选) |
| .m2-cache/ | — | Maven 本地仓库缓存(加速重复构建) |

---

## 二、一键部署（首次部署）

### 2.1 前置要求

| 项目 | 要求 |
|------|------|
| 操作系统 | CentOS 7+/8/9, Ubuntu 18.04+, Debian 10+ |
| CPU | 2 核以上 |
| 内存 | 4GB 以上 |
| 磁盘 | 40GB 以上 |
| 网络 | 能访问互联网（国内源） |
| 权限 | root 或 sudo 权限（安装 Docker 用） |

### 2.2 一键部署

```bash
# 1. 克隆项目（使用 GitHub 加速器，国内推荐）
# 加速器任选其一：
#   https://ghproxy.net/https://github.com/MingTu01/canteen.git
#   https://gh.llkk.cc/https://github.com/MingTu01/canteen.git
#   https://hub.gitmirror.com/https://github.com/MingTu01/canteen.git
git clone https://ghproxy.net/https://github.com/MingTu01/canteen.git /opt/canteen
cd /opt/canteen

# 2. 一键部署（自动安装 Docker + 配置国内源 + 构建 + 启动）
chmod +x deploy.sh
sudo ./deploy.sh
```

部署脚本自动完成以下 6 步：

| 步骤 | 说明 |
|------|------|
| 1. 检测/安装 Docker | 使用阿里云镜像源安装 Docker CE + Compose 插件 |
| 2. 配置镜像加速器 | 写入 /etc/docker/daemon.json，配置 5 个国内 Docker 镜像源 |
| 3. 配置环境变量 | 自动生成 .env（随机密码） |
| 4. 构建业务产物 | 在 Docker 容器中构建 jar + dist，输出到 deploy/ |
| 5. 构建运行时镜像 | 构建后端基础镜像（仅首次，后续更新无需重建） |
| 6. 启动服务 | docker compose up -d + 健康检查 |

### 2.3 已有 Docker 环境的部署

如果服务器已安装 Docker，可跳过环境安装：

```bash
git clone https://ghproxy.net/https://github.com/MingTu01/canteen.git /opt/canteen
cd /opt/canteen
chmod +x deploy.sh
./deploy.sh --skip-env
```

### 2.4 部署后验证

```bash
# 1. 检查容器状态（backend 必须 healthy）
docker compose ps

# 2. 后端健康检查
curl http://localhost:8080/api/system/health
# 期望: {"code":200,"data":{"status":"UP",...}}

# 3. 前端访问
curl -o /dev/null -w "admin: %{http_code}\n"  http://localhost/
curl -o /dev/null -w "h5: %{http_code}\n"    http://localhost:81/
# 期望: 两个都返回 200
```

### 2.5 默认账号

| 角色 | 用户名 | 密码 | 权限 |
|------|--------|------|------|
| 超级管理员 | admin | 123456 | 所有门店 |

> **首次登录后立即在「管理员管理」修改密码。**

---

## 三、各端更新方式（不重建镜像）

### 3.1 核心原理

更新流程 = **重新构建产物** + **重启容器**，**全程不涉及镜像构建**。

```
代码更新 → build.sh 在容器中编译 → 产物输出到 deploy/ → restart 加载新产物
```

### 3.2 一键更新（推荐）

```bash
cd /opt/canteen

# 更新全部（拉取代码 + 构建全部 + 重启全部）
./scripts/update.sh

# 仅更新后端
./scripts/update.sh backend

# 仅更新管理后台
./scripts/update.sh admin-web

# 仅更新 H5
./scripts/update.sh h5
```

`update.sh` 自动完成：`git pull` → `build.sh` → `docker compose restart`

> X86 终端不在 Docker 中部署,更新方式见「第十章 X86 终端 EXE 安装包打包」。

### 3.3 手动更新（分步操作）

#### 3.3.1 更新后端

```bash
cd /opt/canteen

# 1. 拉取最新代码
git pull

# 2. 重新构建后端 jar（在 Docker 容器中构建，输出到 deploy/backend/app.jar）
./scripts/build.sh backend

# 3. 重启后端容器（卷映射自动加载新 jar，无需重建镜像）
docker compose restart backend

# 4. 查看启动日志确认 Flyway 迁移成功
docker compose logs -f backend
# 关键日志:
#   "Successfully validated N migrations"
#   "Schema `canteen` is up to date" 或 "Migrating schema with VXX__xxx.sql"
#   "Started CanteenApplication in X seconds"

# 5. 健康检查
curl http://localhost:8080/api/system/health
```

#### 3.3.2 更新前端（admin-web / h5）

```bash
cd /opt/canteen

# 1. 拉取最新代码
git pull

# 2. 重新构建前端 dist（在 Docker 容器中构建，输出到 deploy/xxx/html/）
./scripts/build.sh admin-web    # 或 h5

# 3. 重启对应容器
docker compose restart admin-web    # 或 h5

# 4. 验证
curl -o /dev/null -w "%{http_code}\n" http://localhost/      # admin
curl -o /dev/null -w "%{http_code}\n" http://localhost:81/   # h5

# 5. 浏览器强制刷新（Vite 构建产物文件名带 contenthash，自动失效缓存）
```

### 3.4 构建脚本详解

```bash
./scripts/build.sh [目标]
```

| 目标 | 说明 | 产物 |
|------|------|------|
| all | 构建全部（默认） | deploy/ 全部内容 |
| backend | 仅后端 | deploy/backend/app.jar |
| admin-web | 仅管理后台 | deploy/admin-web/{html,nginx.conf} |
| h5 | 仅 H5 | deploy/h5/{html,nginx.conf} |

> X86 终端不在 build.sh 构建范围内,需在 Windows 上运行 `src-python/build_installer.py` 打包。

构建脚本特点：
- **使用 Docker 容器构建**：宿主机无需安装 JDK 25 / Node.js 20
- **国内源加速**：Maven 使用阿里云仓库，npm 使用 npmmirror
- **Maven 缓存**：`.m2-cache/` 目录缓存依赖，加速重复构建
- **产物隔离**：构建产物统一输出到 `deploy/`，与源码分离

### 3.5 回滚

```bash
# 1. 回退代码到指定版本
git log --oneline -10          # 查看历史
git checkout <旧版本commit>

# 2. 重新构建旧版本产物
./scripts/build.sh all

# 3. 重启服务
docker compose restart backend admin-web h5
```

> **数据库回滚**：如果新版本包含数据库迁移，必须从升级前备份恢复：
> ```bash
> ./scripts/restore.sh backup/full_auto_<时间戳>.json.gz
> ```

---

## 四、国内源配置说明

### 4.1 Docker 镜像加速器

部署脚本自动写入 `/etc/docker/daemon.json`：

```json
{
  "registry-mirrors": [
    "https://docker.1panel.live",
    "https://docker.m.daocloud.io",
    "https://dockerhub.icu",
    "https://hub-mirror.c.163.com",
    "https://mirror.baidubce.com"
  ]
}
```

手动配置方法：
```bash
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1panel.live",
    "https://docker.m.daocloud.io"
  ]
}
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
```

### 4.2 Maven 仓库（后端构建）

构建脚本自动使用阿里云 Maven 仓库：
- 地址：`https://maven.aliyun.com/repository/public`
- 配置方式：构建时内联 `settings.xml`，无需手动配置

### 4.3 npm 仓库（前端构建）

构建脚本自动使用 npmmirror：
- 地址：`https://registry.npmmirror.com`
- 配置方式：`npm install --registry=https://registry.npmmirror.com`

### 4.4 GitHub 加速器（克隆代码）

国内克隆 GitHub 仓库推荐使用以下加速器（任选其一）：

| 加速器 | 克隆命令 |
|--------|----------|
| ghproxy.net | `git clone https://ghproxy.net/https://github.com/MingTu01/canteen.git` |
| gh.llkk.cc | `git clone https://gh.llkk.cc/https://github.com/MingTu01/canteen.git` |
| gitmirror | `git clone https://hub.gitmirror.com/https://github.com/MingTu01/canteen.git` |
| gitclone | `git clone https://gitclone.com/github.com/MingTu01/canteen.git` |

全局配置（一劳永逸）：
```bash
git config --global url."https://ghproxy.net/https://github.com/".insteadOf "https://github.com/"
# 取消配置:
# git config --global --unset url."https://ghproxy.net/https://github.com/".insteadOf
```

---

## 五、目录结构

```
/opt/canteen/                       # 项目根目录
├── deploy/                         # 构建产物（卷映射到容器，gitignore）
│   ├── backend/
│   │   └── app.jar                 # 后端 Spring Boot jar
│   ├── admin-web/
│   │   ├── html/                   # 管理后台 dist 内容
│   │   └── nginx.conf              # nginx 配置
│   ├── h5/
│   │   ├── html/                   # H5 dist 内容
│   │   └── nginx.conf
├── backup/                         # 数据库备份（持久化）
├── uploads/                        # 上传图片（持久化）
├── .m2-cache/                      # Maven 缓存（加速构建，gitignore）
├── logs/                           # 运维日志
├── .env                            # 环境变量（密码等，gitignore）
│
├── docker-compose.yml              # Docker 编排（卷映射模式）
├── deploy.sh                       # 一键部署脚本
├── scripts/
│   ├── build.sh                    # 构建产物脚本
│   ├── update.sh                   # 更新脚本（pull + build + restart）
│   ├── backup.sh                   # 数据库备份
│   ├── restore.sh                  # 数据库恢复
│   └── ...
├── backend/                        # 后端源码
├── admin-web/                      # 管理后台源码
├── h5/                             # H5 源码
└── terminal/                       # 终端源码
```

---

## 六、数据库迁移

### 6.1 自动迁移

后端启动时 Flyway **自动**按版本号顺序执行迁移脚本：

- 路径：`backend/src/main/resources/db/migration/`
- 命名规则：`V{版本号}__{描述}.sql`（双下划线分隔）
- **已执行的迁移脚本不可修改**，只能新增

### 6.2 增量补丁迁移

SchemaMigrationRunner 在 Flyway 之后执行增量补丁（新增字段/索引/配置项）：

- 路径：`backend/src/main/java/com/example/canteen/migration/SchemaMigrationRunner.java`
- 特点：幂等设计，失败重跑不破坏数据

### 6.3 新增迁移流程

```bash
# 1. 在 db/migration/ 下新增脚本（版本号 > 当前最大版本）
#    例: V15__add_xxx_column.sql

# 2. 更新代码 + 构建后端 + 重启
./scripts/update.sh backend

# 3. 查看迁移日志
docker compose logs backend | grep -i -E "flyway|migration"
```

---

## 七、备份与恢复

### 7.1 自动备份

- 调度：每天凌晨 2:00（`BackupSchedulerService`）
- 保留：30 份（超出自动清理最旧的）
- 路径：`./backup/full_auto_<时间戳>.json.gz`
- 格式：JSON + GZIP（应用层备份，不依赖 mysqldump）

管理后台「系统设置」可修改：备份开关、cron 表达式、保留份数。

### 7.2 手动备份

```bash
# 方式 A:管理后台 UI
# 登录管理后台 → 备份恢复 → 立即备份

# 方式 B:OS 级 mysqldump 备份
./scripts/backup.sh
```

### 7.3 恢复

```bash
# 应用层备份恢复（推荐）
./scripts/restore.sh backup/full_auto_<时间戳>.json.gz

# 或通过管理后台 UI 上传 .json.gz 文件恢复
```

---

## 八、运维操作

### 8.1 常用命令

```bash
# 查看服务状态
docker compose ps

# 查看实时日志
docker compose logs -f backend        # 后端
docker compose logs -f --tail=100 h5  # H5 最近 100 行

# 重启服务
docker compose restart backend        # 仅后端
docker compose restart               # 全部

# 进入容器
docker exec -it canteen-backend bash
docker exec -it canteen-mysql mysql -uroot -p canteen

# 清理 Docker 悬空镜像（释放磁盘）
docker image prune -f
docker builder prune -f
```

### 8.2 监控检查项

| 检查项 | 命令 | 频率 |
|--------|------|------|
| 磁盘空间 | `df -h` | 每周 |
| 容器健康 | `docker compose ps` | 每日 |
| 后端日志错误 | `docker compose logs backend \| grep -i error` | 每日 |
| MySQL 慢查询 | `docker exec canteen-mysql mysql -uroot -p -e "SHOW PROCESSLIST"` | 异常时 |
| 备份文件 | `ls -lh backup/` | 每周 |

### 8.3 磁盘容量规划（40GB）

| 类别 | 占用 | 说明 |
|------|------|------|
| 系统 + Docker | ~7-10 GB | 一次性 |
| MySQL 数据 | ~0.5 GB/年 | 500 员工 × 3 餐 × 365 天 |
| 菜品图片 | 50-200 MB | 一次上传基本不变 |
| 自动备份 | 30-500 MB | 30 份 GZIP，自动清理 |
| Docker 日志 | ≤900 MB | 已配置轮转 50m × 3 × 6 服务 |
| 构建缓存 | ~500 MB | .m2-cache + node_modules |
| **合计** | **~10-12 GB（首年）** | 剩余 28+ GB |

---

## 九、故障排查

### 9.1 后端启动失败

```bash
# 查看日志
docker compose logs backend

# 常见原因:
#   "Communications link failure" → mysql 未启动,等 healthy 后重启 backend
#   "Flyway migration failed"     → 数据库状态异常,需从备份恢复
#   "Bean creation exception"     → 代码 bug,检查 git pull 是否完整
```

### 9.2 前端 502 Bad Gateway

```bash
# backend 未启动或健康检查未通过
docker compose ps            # 确认 backend 为 healthy
docker compose logs --tail=50 backend
```

### 9.3 卷映射文件未生效

```bash
# 确认产物文件存在
ls -la deploy/backend/app.jar
ls -la deploy/admin-web/html/index.html

# 确认容器内挂载正确
docker exec canteen-backend ls -la /app/app.jar
docker exec canteen-admin ls -la /usr/share/nginx/html/index.html

# 如果文件存在但未生效,重启容器
docker compose restart backend admin-web
```

### 9.4 构建失败

```bash
# Maven 构建失败（网络问题）
# 解决:检查国内源配置,或手动执行构建查看详细错误
./scripts/build.sh backend

# npm 构建失败（网络问题）
# 解决:检查 npmmirror 可用性
./scripts/build.sh admin-web

# 清理构建缓存重新构建
rm -rf .m2-cache
./scripts/build.sh backend
```

### 9.5 X86 终端无法连接服务器

1. 检查终端设置页的 API 地址是否正确（默认 `https://canteen.908521.xyz`，可改为服务器 IP/域名）
2. 检查服务器防火墙/安全组是否放行后端端口（8080 或 443/HTTPS）
3. 若使用域名 + HTTPS，确认 Nginx/反向代理已正确转发 `/api/` 到 backend
4. 终端首次使用需完成「绑定」（管理员账号 + 食堂安全码），详见第十章

---

## 十、X86 终端 EXE 安装包打包

> X86 终端（订餐机/取餐机）为独立 Windows 应用,**不参与 Docker 部署**,
> 通过 PyInstaller + Inno Setup 打包为正式 EXE 安装包,内置 CH375 读卡器驱动,
> 默认连接 `https://canteen.908521.xyz`。

### 10.1 打包前置条件

在 **Windows 打包机**上准备以下环境：

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| Node.js | 18+ | 构建终端 Vue 前端 |
| Python | 3.10 **32 位** | PyInstaller 打包（OUR_IDR.dll 是 32 位,必须用 32 位 Python） |
| PyQt5 + PyQtWebEngine | 最新 | `pip install PyQt5 PyQtWebEngine pyinstaller` |
| Inno Setup | 6+ | 打包正式安装包,下载: https://jrsoftware.org/isdl.php |
| CH375 驱动文件 | — | 放入 `src-python/drivers/`（CH375WDM.INF / CH375W64.SYS / CH375WDM.CAT 等） |

> 驱动文件获取:南京沁恒电子官网 http://www.wch.cn/downloads/CH372DRV_EXE.html ,
> 或随读卡器附赠光盘。详细清单见 `src-python/drivers/README.txt`。

### 10.2 一键打包

```bash
cd src-python

# 完整打包（构建前端 + PyInstaller + Inno Setup）
python build_installer.py

# 跳过已构建的前端
python build_installer.py --skip-web

# 跳过已构建的 PyInstaller,仅重新打包安装包
python build_installer.py --skip-web --skip-py

# 仅运行 Inno Setup（前端和 PyInstaller 产物均已就绪）
python build_installer.py --only-iss
```

打包脚本自动完成三步：

| 步骤 | 说明 | 产物 |
|------|------|------|
| 1. 构建前端 | `npm run build`(终端 Vue 项目) | `terminal/dist/` |
| 2. PyInstaller | 打包 Python + PyQt5 + 前端 + 驱动 | `dist/canteen-terminal/`(绿色目录版) |
| 3. Inno Setup | 打包为正式安装包(含驱动自动安装) | `output/CanteenTerminal-Setup-1.0.0.exe` |

> 若未安装 Inno Setup 或未设置 `ISCC_PATH` 环境变量,脚本会自动查找常见安装路径。

### 10.3 安装包功能

最终生成的 `CanteenTerminal-Setup-1.0.0.exe` 安装包具备：

- 安装终端程序到 `C:\Program Files\CanteenTerminal\`
- **自动安装 CH375 读卡器驱动**（调用 `pnputil /add-driver`，静默执行）
- 创建开始菜单快捷方式 + 桌面快捷方式（可选）
- 开机自启（可选）
- 完整卸载程序（卸载时清理配置和缓存）
- 默认 API 地址内置为 `https://canteen.908521.xyz`（绑定页自动填入）

### 10.4 部署到终端设备

1. 将 `CanteenTerminal-Setup-1.0.0.exe` 拷贝到终端 Windows 设备
2. 双击运行安装包（需管理员权限,用于安装驱动）
3. 安装完成后从开始菜单或桌面快捷方式启动「企业智慧食堂终端」
4. 首次启动需绑定:
   - 连续点击窗口右上角 6 下(2 秒内)→ 弹出密码框(管理员密码)
   - 验证通过后进入配置页,确认/修改服务器地址(默认已填 `https://canteen.908521.xyz`)
   - 输入管理员账号密码 + 食堂安全码 → 点击绑定
5. 绑定成功后终端进入运行模式(订餐/取餐),刷卡即可使用

### 10.5 终端更新

重新在打包机上运行 `python build_installer.py` 生成新版本安装包,
在终端设备上覆盖安装即可（配置数据保留）。

> 仅更新前端/Python 逻辑而驱动未变时,也可只替换安装目录下的文件后重启程序,
> 但推荐使用安装包覆盖安装,确保文件完整。

### 10.6 手动分步打包（调试用）

如需单独调试某一环节,可分步执行：

```bash
cd src-python

# 1. 仅构建前端
cd ../terminal && npm install --registry=https://registry.npmmirror.com && npm run build && cd ../src-python

# 2. 仅运行 PyInstaller（生成绿色目录版 dist/canteen-terminal/）
C:\Python310-32\python.exe -m PyInstaller canteen-terminal.spec --clean --noconfirm

# 3. 仅运行 Inno Setup（生成正式安装包）
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer.iss
```

绿色目录版 `dist/canteen-terminal/canteen-terminal.exe` 可直接运行测试,
但不含驱动自动安装,需手动安装驱动后才能使用读卡器。

---

## 十一、变更记录

### V1.0 — 2026-08-01

- 部署架构改为 **基础镜像 + 卷映射** 模式，更新无需重建镜像
- 新增一键部署脚本 `deploy.sh`（自动安装 Docker + 国内源 + 构建 + 启动）
- 新增构建脚本 `scripts/build.sh`（在 Docker 容器中构建，宿主机无需 JDK/Node）
- 新增更新脚本 `scripts/update.sh`（pull + build + restart）
- 全链路国内源加速：Docker 镜像源、阿里云 Maven、npmmirror
- 文档补充 GitHub 加速器克隆方式
- X86 终端从 Docker 部署中移除,改为 **PyInstaller + Inno Setup 正式 EXE 安装包**
  - 内置 CH375 读卡器驱动,安装时自动调用 pnputil 安装
  - 默认 API 地址 `https://canteen.908521.xyz`
  - 打包脚本 `src-python/build_installer.py` 一键完成前端构建 + PyInstaller + Inno Setup
