# 部署方案与更新流程

> 版本：V0.0.1 ｜ 更新日期：2026-07-28 ｜ 适用：企业智慧食堂预定餐系统

---

## 一、部署架构

### 1.1 服务拓扑

```
                ┌─────────────────────────────────────────────────┐
                │              服务器（2C4G 5M / 40GB）              │
                │                                                 │
   用户/浏览器 ──┼──> 80  ──> [admin-web nginx] ──┐                │
                │      81 ──> [h5       nginx] ──┤  /api/         │
   X86 终端  ───┼──> 82 ──> [terminal  nginx] ──┤  反向代理      │
                │                                ├────────────────┼──> [backend:8080]
                │                                │     │          │     Spring Boot 3.5
                │                                │     │          │     Java 25 + JWT
                │                                │     │          │
                │                                │     ├──> [redis:6379] (128MB LRU)
                │                                │     │          │
                │                                │     └──> [mysql:3306] (256MB buffer)
                │                                                 │
                └─────────────────────────────────────────────────┘
```

### 1.2 端口与目录映射

| 服务 | 端口 | 容器内路径 | 主机挂载 | 用途 |
|------|------|------------|----------|------|
| admin-web | 80 | /usr/share/nginx/html | — | 管理后台 |
| h5 | 81 | /usr/share/nginx/html | — | H5 订餐端 |
| terminal | 82 | /usr/share/nginx/html | — | X86 终端 |
| backend | 8080 | /app | ./uploads → /app/uploads<br/>./backup → /app/backup | 后端 API |
| mysql | 127.0.0.1:3306 | /var/lib/mysql | 卷 `mysql_data` | 数据库 |
| redis | 127.0.0.1:6379 | /data | 卷 `redis_data` | 缓存 |

### 1.3 资源约束（docker-compose.yml 已配置）

| 服务 | 内存上限 | CPU | 日志轮转 |
|------|----------|-----|----------|
| backend | JVM -Xmx512m（~600m 容器） | 共享 | 50m × 3 |
| mysql | innodb-buffer-pool 256m（~400m 容器） | 共享 | 50m × 3 |
| redis | maxmemory 128m + LRU（~150m 容器） | 共享 | 50m × 3 |
| nginx ×3 | 各 ~30m | 共享 | 50m × 3 |

总占用 ~1.4GB 内存，4GB 系统留有充足余量。

---

## 二、首次部署流程

### 2.1 服务器准备

```bash
# 1. 安装 Docker + Docker Compose（CentOS/Ubuntu 通用）
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker

# 2. 同步项目代码到服务器（任选一种）
# 方式 A：git clone（推荐）
git clone <仓库地址> /opt/canteen
cd /opt/canteen

# 方式 B：scp 上传（离线部署）
# 在本地：tar -czf canteen.tar.gz --exclude=node_modules --exclude=target canteen/
# scp canteen.tar.gz user@server:/opt/
# 在服务器：cd /opt && tar -xzf canteen.tar.gz && cd canteen

# 3. 配置环境变量
cp .env.example .env
vim .env   # 修改 MYSQL_ROOT_PASSWORD / JWT_SECRET 为强密码
```

### 2.2 .env 必填项

```bash
MYSQL_ROOT_PASSWORD=<强密码,至少16位>
JWT_SECRET=<JWT密钥,至少32位随机字符串>
JWT_EXPIRATION=86400000     # 默认 24h
SERVER_PORT=8080
```

### 2.3 一键部署

```bash
chmod +x deploy.sh
./deploy.sh
# 选择 1：完整部署（后端 + 管理端 + H5 + 终端）
```

部署脚本会自动完成：
1. 创建 `backup/`、`uploads/` 目录
2. `docker compose up -d --build` 拉起全部 6 个容器
3. backend 等待 mysql + redis 健康后启动
4. Flyway 自动执行 `db/migration/V1~V11__*.sql` 建表

### 2.4 部署后验证

```bash
# 1. 检查容器状态（backend 必须 healthy）
docker compose ps

# 2. 健康检查
curl http://localhost:8080/api/system/health
# 期望: {"code":200,"data":{"status":"UP","version":"0.0.1",...}}

# 3. 前端访问
curl -o /dev/null -w "admin:%{http_code}\n" http://localhost/
curl -o /dev/null -w "h5:%{http_code}\n"   http://localhost:81/
curl -o /dev/null -w "terminal:%{http_code}\n" http://localhost:82/
# 期望: 三个都返回 200
```

### 2.5 默认账号

部署完成后，数据库中仅有 1 个默认超管账号（无测试业务数据）：

| 角色 | 用户名 | 密码 | 权限范围 |
|------|--------|------|----------|
| 超级管理员 | admin | 123456 | 所有门店 |

> **首次登录后立即在「管理员管理」修改密码。**

### 2.6 本机开发测试数据（可选）

如需在本机开发环境填充测试数据，手动执行 seed 脚本：

```bash
# 拷贝并执行 seed-dev.sql（包含 2门店/5部门/5员工/13菜品/6菜单/3通知/3管理员）
# 注意:必须用 docker cp + source,禁止用 PowerShell 管道(Get-Content 默认非 UTF-8 会导致中文双重编码)
docker cp scripts/seed-dev.sql canteen-mysql:/tmp/seed-dev.sql
docker exec canteen-mysql mysql -uroot -p<密码> --default-character-set=utf8mb4 canteen -e "source /tmp/seed-dev.sql"
```

> **此脚本仅供本机开发使用，生产环境切勿执行。**

### 2.7 X86 终端配置（每台独立）

终端启动后进入设置页（默认配置见 [terminal/src/views/Settings.vue](file:///c:/Users/Administrator/Desktop/canteen/terminal/src/views/Settings.vue)）：
1. 服务器 API 地址：`http://<服务器IP>:8080`
2. 门店 ID：从管理后台「门店管理」获取
3. 运行模式：订餐机 / 取餐机

配置持久化在浏览器 localStorage，断网时本地 IndexedDB 缓存可继续展示菜品与订单。

---

## 三、各端更新流程

### 3.1 版本号规范

- 所有端版本号统一：`MAJOR.MINOR.PATCH`（语义化版本）
- V0.0.1 为初始基线，后续按以下规则递增：
  - PATCH（0.0.X）：bug 修复、配置调整，无破坏性变更
  - MINOR（0.X.0）：新增功能，向后兼容
  - MAJOR（X.0.0）：破坏性变更，需数据库迁移或配置变更
- **版本号文件位置**（修改版本时必须同步更新）：

| 端 | 文件 | 字段 |
|----|------|------|
| backend | `backend/src/main/resources/version.json` | `version` |
| backend | `backend/pom.xml` | `<version>` |
| admin-web | `admin-web/package.json` | `version` |
| h5 | `h5/package.json` | `version` |
| terminal | `terminal/package.json` | `version` |

每次发版前确认 5 个文件版本号一致。

### 3.2 后端更新流程

```bash
# 1. 拉取新代码
cd /opt/canteen
git pull

# 2. 仅后端有代码变更时
docker compose build backend
docker compose up -d backend

# 3. 查看启动日志确认 Flyway 迁移成功
docker compose logs -f backend
# 关键日志:
#   "Successfully validated N migrations"
#   "Schema `canteen` is up to date" 或 "Migrating schema with V12__xxx.sql"
#   "Started CanteenApplication in X seconds"

# 4. 健康检查
curl http://localhost:8080/api/system/health
```

**回滚**（数据库无迁移时）：
```bash
# 回退代码
git checkout <旧版本commit>
docker compose build backend && docker compose up -d backend
```

**回滚**（数据库已有新迁移时）：
```bash
# 必须从升级前备份恢复,不能简单回退代码
./scripts/restore.sh backup/full_auto_<时间戳>.json.gz
git checkout <旧版本commit>
docker compose build backend && docker compose up -d backend
```

### 3.3 前端更新流程（admin-web / h5 / terminal 通用）

```bash
# 1. 拉取新代码
git pull

# 2. 仅某个前端变更时,只重建对应服务
docker compose build admin-web    # 或 h5 / terminal
docker compose up -d admin-web    # 或 h5 / terminal

# 3. 验证（注意:前端无构建状态,看 nginx 是否返回新版本 HTML）
curl -o /dev/null -w "%{http_code}\n" http://localhost/      # admin
curl -o /dev/null -w "%{http_code}\n" http://localhost:81/   # h5
curl -o /dev/null -w "%{http_code}\n" http://localhost:82/    # terminal

# 4. 浏览器端强制刷新（Vite 构建产物文件名带 contenthash,自动失效）
#    用户无需手动清缓存,index.html 已配置 no-cache
```

### 3.4 数据库迁移流程

- 迁移脚本路径：`backend/src/main/resources/db/migration/`
- 命名规则：`V{版本号}__{描述}.sql`（双下划线分隔）
- backend 启动时 Flyway **自动**按版本号顺序执行
- **已执行的迁移脚本不可修改**，只能新增

新增迁移流程：
```bash
# 1. 在 db/migration/ 下新增脚本（版本号 > 当前最大版本 11）
#    例:V12__add_xxx_column.sql

# 2. 重启 backend 即可自动执行
docker compose restart backend
docker compose logs backend | grep -i flyway
```

### 3.5 全量更新（一键脚本）

```bash
./scripts/upgrade.sh
# 脚本会自动:
#   1. 自动备份当前数据库
#   2. git pull 拉取最新代码
#   3. docker compose build 重建变更的镜像
#   4. docker compose up -d 滚动重启
#   5. 健康检查
```

---

## 四、备份与恢复

### 4.1 自动备份（已配置）

- 调度服务：[BackupSchedulerService.java](file:///c:/Users/Administrator/Desktop/canteen/backend/src/main/java/com/example/canteen/service/BackupSchedulerService.java)
- 默认 cron：`0 0 2 * * ?`（每天凌晨 2:00）
- 保留份数：30（超出自动清理最旧的）
- 存储路径：`./backup/full_auto_<时间戳>.json.gz`
- 格式：JSON + GZIP（应用层备份，不依赖 mysqldump）

可在管理后台「系统设置」修改：
- `backup_auto_enabled`：是否启用
- `backup_cron`：cron 表达式
- `backup_keep_copies`：保留份数

### 4.2 手动备份

```bash
# 方式 A:管理后台 UI
# 登录管理后台 → 备份恢复 → 立即备份

# 方式 B:OS 级 mysqldump 备份(与应用层备份互补)
./scripts/backup.sh
```

### 4.3 恢复

```bash
# 应用层备份恢复（推荐）
./scripts/restore.sh backup/full_auto_<时间戳>.json.gz

# 或通过管理后台 UI 上传 .json.gz 文件恢复
```

---

## 五、运维操作

### 5.1 常用命令

```bash
# 查看服务状态
docker compose ps

# 查看实时日志
docker compose logs -f backend        # 后端
docker compose logs -f --tail=100 h5   # h5 最近 100 行

# 重启服务
docker compose restart backend         # 仅后端
docker compose restart                # 全部

# 进入容器
docker exec -it canteen-backend bash
docker exec -it canteen-mysql mysql -uroot -p canteen

# 清理 Docker 悬空镜像（释放磁盘）
docker image prune -f
docker builder prune -f
```

### 5.2 监控检查项

| 检查项 | 命令 | 频率 |
|--------|------|------|
| 磁盘空间 | `df -h` | 每周 |
| 容器健康 | `docker compose ps` | 每日 |
| 后端日志错误 | `docker compose logs backend \| grep -i error` | 每日 |
| MySQL 慢查询 | `docker exec canteen-mysql mysql -uroot -p -e "SHOW PROCESSLIST"` | 异常时 |
| 备份文件 | `ls -lh backup/` | 每周 |

### 5.3 40GB 磁盘容量规划

| 类别 | 占用 | 说明 |
|------|------|------|
| 系统 + Docker | ~7-10 GB | 一次性 |
| MySQL 数据 | ~0.5 GB/年 | 500 员工 × 3 餐 × 365 天 |
| 菜品图片 | 50-200 MB | 一次上传基本不变 |
| 自动备份 | 30-500 MB | 30 份 GZIP，自动清理 |
| Docker 日志 | ≤900 MB | 已配置轮转 50m × 3 × 6 服务 |
| **合计** | **~10-12 GB（首年）** | 剩余 28+ GB |

**预期寿命：5-8 年无需扩容。**

---

## 六、故障排查

### 6.1 backend 启动失败

```bash
# 1. 查看日志
docker compose logs backend

# 常见原因:
#   "Communications link failure" → mysql 未启动,等 healthy 后重启 backend
#   "Flyway migration failed"     → 数据库状态异常,需从备份恢复
#   "Bean creation exception"     → 代码 bug,检查 git pull 是否完整
```

### 6.2 前端 502 Bad Gateway

```bash
# backend 未启动或健康检查未通过
docker compose ps     # 确认 backend 为 healthy
docker compose logs --tail=50 backend
```

### 6.3 X86 终端无法连接服务器

1. 检查终端设置页的 API 地址是否正确
2. 检查服务器防火墙是否放行 8080 端口
3. 检查 nginx 是否正确代理 `/api/` 到 backend

### 6.4 SSE 推送不生效

```bash
# 检查 nginx SSE 配置是否关闭 buffering
docker exec canteen-terminal nginx -T | grep -A5 "api/sse"
# 必须包含: proxy_buffering off; proxy_cache off;
```

---

## 七、变更记录

### V0.0.1 — 2026-07-29

#### 7.1 未订餐用餐功能(新增)

支持员工未提前订餐直接到食堂用餐,由打菜人员确认菜品后下单。

- **后端**:新增 `order_source` 字段(V13 迁移),`OrderSource` 枚举(NORMAL=0/UNSOLICITED=1);`OrderService.createOrder` 对 `orderSource=1` 绕过截止时间和防重复校验
- **H5**:[UnsolicitedOrder.vue](h5/src/views/UnsolicitedOrder.vue) 页面,使用服务器时间判断当前餐别,仅显示当前餐别菜品,确认后下单;入口位于"我的"页面
- **取餐端**:[PickupInfo.vue](terminal/src/views/PickupInfo.vue) 对 `orderSource=1` 订单显示橙色边框 + "未订餐用餐"标签
- **管理后台**:[OrderManagement.vue](admin-web/src/views/order/OrderManagement.vue) 订单列表增加"订单来源"列,Excel 导出包含"订单来源"字段
- **服务器时间同步**:新增 `/api/system/time` 接口和 [useServerTime.ts](h5/src/composables/useServerTime.ts),所有端时间以后端为准,避免本机时间篡改

#### 7.2 H5 登录页改造

- 移除卡号登录 TAB,统一使用**手机号+密码**登录
- 测试刷卡模块改为模拟手机号登录(使用员工手机号 + 默认密码 123456)
- 测试员工列表接口 `/api/test/employees` 在所有环境可用(移除 `@Profile("dev")`,加入白名单)
- 登录失败时显示明确错误提示(手机号或密码错误)

#### 7.3 图片 404 修复

**问题**:H5/terminal/admin-web 的 `/uploads/*.jpg` 返回 404。

**根因**:nginx.conf 中正则 location `~* \.(jpg|png|...)$` 优先级高于前缀 `location /uploads/`,导致图片请求被正则匹配走 `try_files $uri =404`,而 nginx 容器内无 `/usr/share/nginx/html/uploads/` 目录。

**修复**:三个 nginx.conf 的 `location /uploads/` 改为 `location ^~ /uploads/`(`^~` 前缀匹配优先级高于正则)。

#### 7.4 订单页取餐码去重

**问题**:订单列表页一个订单(含多个菜)显示多个相同取餐码。

**修复**:[Orders.vue](h5/src/views/Orders.vue) 的 `pickupOrders` 按 `orderId` 去重,一个订单只显示一个取餐码。

#### 7.5 H5 首页品牌标题模块

首页顶部新增居中显示的 logo + 食堂名称模块,数据来自 `brandingStore`。

#### 7.6 取餐端布局优化

- [PickupInfo.vue](terminal/src/views/PickupInfo.vue):员工头像放大至 120×120,布局改为"头像 → 部门·名字 → 菜品卡片";取餐完成按钮直接返回主页
- [PickupStandby.vue](terminal/src/views/PickupStandby.vue):删除"扫码取餐/刷卡取餐"按钮和弹窗,改为直接刷卡/扫码(USB 设备作为键盘输入,Enter 结束)
- [OrderMenu.vue](terminal/src/views/OrderMenu.vue):订餐选择页深色背景 + 白色文字 + 背景图
