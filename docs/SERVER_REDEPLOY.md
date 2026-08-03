# 生产服务器清理与重新部署操作文档

> 适用场景:生产服务器存在旧代码残留 / 文件缓存污染 / 部署状态不一致,需要彻底清理后重新部署最新版本
> 目标系统:Ubuntu 26.04 LTS + 1panel + Docker
> 部署分支:`deploy`(存放预构建产物,服务器只拉取此分支)
> 操作用户:`canteen`(普通用户,sudo 提权执行系统级命令)
> 预计耗时:30 ~ 45 分钟(含数据备份与验证)

---

## 0. 操作流程总览

| 阶段 | 步骤 | 风险等级 | 是否可回滚 |
|------|------|---------|-----------|
| 准备 | 备份数据库与配置 | 无 | — |
| 停服 | 优雅停止所有服务 | 低 | 是 |
| 清理 | 删除旧代码 / 镜像 / 卷 / 缓存 | 中 | 需从备份恢复 |
| 拉取 | 克隆或更新 deploy 分支 | 低 | 是 |
| 配置 | 重新生成安全的 .env | 低 | 是 |
| 部署 | 执行 deploy.sh 向导 | 低 | 是 |
| 验证 | 健康检查 + 冒烟测试 | 无 | — |

**核心原则**:
- 每一步执行后检查输出,失败立即停止,不要继续后续步骤
- 备份文件必须先校验完整性,再执行破坏性清理
- 清理 Docker 卷会删除数据库数据,**必须先备份**
- 所有命令在 `/opt/canteen` 目录下执行(除非另有说明)

---

## 1. 前置准备(必做)

### 1.1 确认当前部署状态

```bash
cd /opt/canteen

# 查看当前分支与版本
git branch -vv
git log --oneline -5

# 查看运行中的服务
docker compose ps

# 查看磁盘剩余空间(清理前需确认有空间存放备份)
df -h /opt
```

**预期输出**:
- `git branch` 显示 `* deploy`(若显示 detached HEAD,参见第 6 节故障处理)
- `df -h` 显示 `/opt` 剩余空间 ≥ 5GB

### 1.2 通知用户停服窗口

清理与重部署期间服务不可用,建议在非用餐时段执行(推荐 14:00-16:00 或 21:00 后)。

### 1.3 准备备份目录

```bash
# 备份目录:带时间戳,便于多次回滚
BACKUP_DIR="/opt/canteen-backup-$(date +%Y%m%d-%H%M%S)"
sudo mkdir -p "$BACKUP_DIR"
sudo chown canteen:canteen "$BACKUP_DIR"
echo "备份目录: $BACKUP_DIR"
```

---

## 2. 备份数据(关键,不可跳过)

### 2.1 备份数据库

```bash
cd /opt/canteen

# 方式一:通过 Docker 执行 mysqldump(推荐,热备份不锁表)
docker compose exec -T mysql mysqldump \
  -u root \
  -p"$(grep MYSQL_ROOT_PASSWORD .env | cut -d= -f2)" \
  --single-transaction \
  --routines \
  --triggers \
  --databases canteen \
  | gzip > "$BACKUP_DIR/canteen-db-$(date +%Y%m%d-%H%M%S).sql.gz"

# 校验备份文件非空
ls -lh "$BACKUP_DIR"/canteen-db-*.sql.gz
gunzip -t "$BACKUP_DIR"/canteen-db-*.sql.gz && echo "备份文件完整性校验通过"
```

**校验要点**:
- 文件大小应 > 1MB(空库异常)
- `gunzip -t` 必须返回 `完整性校验通过`

### 2.2 备份配置文件与上传文件

```bash
# 备份 .env(含数据库密码、JWT 密钥等敏感信息)
cp /opt/canteen/.env "$BACKUP_DIR/.env.bak"

# 备份上传的图片/文件(Docker 卷)
docker run --rm \
  -v canteen_canteen-uploads:/data/uploads:ro \
  -v "$BACKUP_DIR":/backup \
  alpine:latest \
  tar czf /backup/uploads-$(date +%Y%m%d-%H%M%S).tar.gz -C /data uploads

# 备份 Redis 持久化数据(若启用 RDB)
docker run --rm \
  -v canteen_canteen-redis-data:/data/redis:ro \
  -v "$BACKUP_DIR":/backup \
  alpine:latest \
  tar czf /backup/redis-$(date +%Y%m%d-%H%M%S).tar.gz -C /data/redis .

ls -lh "$BACKUP_DIR"/
```

### 2.3 备份校验清单

```bash
# 必须存在以下文件,缺一不可
echo "=== 备份校验清单 ==="
for f in \
  "$BACKUP_DIR"/canteen-db-*.sql.gz \
  "$BACKUP_DIR"/.env.bak \
  "$BACKUP_DIR"/uploads-*.tar.gz; do
    if [[ -f "$f" ]]; then
        echo "[OK] $(basename "$f") ($(du -h "$f" | cut -f1))"
    else
        echo "[缺失] $f"
    fi
done
```

**只有全部显示 [OK] 才能继续下一步。** 若有缺失,立即排查原因,不要继续清理。

---

## 3. 停止服务

### 3.1 优雅停止 Docker Compose 服务

```bash
cd /opt/canteen

# 停止所有服务(保留卷与网络)
docker compose down

# 确认所有容器已退出
docker compose ps -a
# 预期:无运行中容器,已退出的容器列表
```

### 3.2 停止 1panel 反向代理(可选)

> 若 1panel 反代指向 18080/18081/18082,停服期间用户访问会显示 502。
> 如需显示维护页面,可在 1panel 中临时修改反代指向静态维护页。

```bash
# 查看 1panel 的 OpenResty 是否在运行
sudo systemctl status openresty 2>/dev/null || sudo 1pctl status

# 一般无需停止 1panel,保持反代运行即可(后端停止后会返回 502)
```

### 3.3 确认端口已释放

```bash
# 端口应不再被监听
sudo ss -tlnp | grep -E '18080|18081|18082'
# 预期:无输出(端口已释放)
```

---

## 4. 清理旧代码与缓存

> 此阶段为破坏性操作,执行前确认第 2 节备份已完成并通过校验。

### 4.1 清理旧代码(保留 .env 备份)

```bash
cd /opt

# 重命名旧目录(不直接删除,便于回滚)
if [[ -d /opt/canteen ]]; then
    sudo mv /opt/canteen "$BACKUP_DIR/canteen-old-code"
    echo "旧代码已移动到 $BACKUP_DIR/canteen-old-code"
fi
```

### 4.2 清理 Docker 旧镜像

```bash
# 删除本项目相关的旧镜像(保留 mysql/redis 基础镜像可加速重建)
docker images | grep -E 'canteen-backend|canteen-admin-web|canteen-h5'

# 删除旧的业务镜像
docker rmi $(docker images --filter "reference=canteen-*" -q) 2>/dev/null || true

# 清理悬空镜像(<none> 标签的中间层)
docker image prune -f

# 查看清理结果
docker images
```

### 4.3 清理 Docker 卷(谨慎!含数据库数据)

```bash
# 列出项目相关卷
docker volume ls | grep canteen

# 确认备份已完成(第 2.1 节)后,删除旧卷
# ⚠️ 此操作不可逆!数据库数据将被删除!
docker compose down -v 2>/dev/null || true

# 手动清理残留卷(若 compose down -v 未清干净)
docker volume rm $(docker volume ls --filter "name=canteen_" -q) 2>/dev/null || true
docker volume rm $(docker volume ls --filter "name=canteen-canteen" -q) 2>/dev/null || true

# 确认卷已清空
docker volume ls | grep canteen
# 预期:无输出
```

### 4.4 清理 Docker 网络与构建缓存

```bash
# 清理未使用的网络
docker network prune -f

# 清理构建缓存(释放磁盘空间,下次构建会重新生成)
docker builder prune -f

# 清理 Docker 系统悬空资源
docker system prune -f
```

### 4.5 清理浏览器缓存(客户端)

> 服务端无法自动清理,需通过版本号强制浏览器刷新。

```bash
# 部署后,前端会带新版本号(VERSIONS.json),浏览器检测到新版本会自动刷新缓存
# 用户操作:
#   1. 强制刷新:Ctrl + F5(Windows)/ Cmd + Shift + R(Mac)
#   2. 或清除浏览器缓存后重新访问
```

### 4.6 清理系统临时文件

```bash
# 清理 apt 缓存(可选,释放空间)
sudo apt-get clean

# 清理系统日志(保留最近 7 天)
sudo journalctl --vacuum-time=7d

# 清理 Docker 日志(单个容器日志过大时)
sudo sh -c 'truncate -s 0 /var/lib/docker/containers/*/*-json.log'

# 查看清理后的磁盘空间
df -h /opt
```

---

## 5. 拉取最新代码

### 5.1 克隆 deploy 分支(首次或重新克隆)

```bash
cd /opt

# 国内服务器使用 ghproxy 加速
git clone -b deploy https://ghproxy.net/https://github.com/MingTu01/canteen.git /opt/canteen

cd /opt/canteen

# 确认分支
git branch -vv
# 预期:* deploy xxxxxxx [origin/deploy] Latest commit message
```

### 5.2 设置目录所有权(避免权限问题)

```bash
# 将目录所有权交给 canteen 用户(避免 sudo git clone 导致的 root 所有权问题)
sudo chown -R canteen:canteen /opt/canteen
```

### 5.3 赋予脚本执行权限

```bash
# Windows Git 不保留 +x 权限,必须手动赋予
chmod +x /opt/canteen/deploy.sh
chmod +x /opt/canteen/scripts/*.sh
chmod +x /opt/canteen/canteen.sh

# 确认权限
ls -l /opt/canteen/*.sh /opt/canteen/scripts/*.sh
# 预期:所有 .sh 文件显示 -rwxr-xr-x
```

### 5.4 设置 Git 安全目录(防止 dubious ownership 报错)

```bash
# 若 git 提示 "detected dubious ownership",执行:
git config --global --add safe.directory /opt/canteen
```

---

## 6. 配置安全的 .env

### 6.1 生成强随机密钥

```bash
cd /opt/canteen

# 生成 JWT 密钥(64 字节十六进制 = 128 位熵)
JWT_SECRET=$(openssl rand -hex 64)
echo "JWT_SECRET=$JWT_SECRET"

# 生成 MySQL root 密码(32 字节)
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 32)
echo "MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD"

# 生成 Redis 密码(32 字节)
REDIS_PASSWORD=$(openssl rand -hex 32)
echo "REDIS_PASSWORD=$REDIS_PASSWORD"

# 生成备份加密密钥(32 字节,务必妥善保管!丢失则备份无法恢复)
BACKUP_ENCRYPTION_KEY=$(openssl rand -hex 32)
echo "BACKUP_ENCRYPTION_KEY=$BACKUP_ENCRYPTION_KEY"
echo ""
echo "⚠️ 请将 BACKUP_ENCRYPTION_KEY 单独保存到密码管理器,丢失将无法恢复备份!"
```

### 6.2 创建 .env 文件

```bash
cd /opt/canteen

cat > .env <<EOF
# ===== 数据库配置 =====
MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD
MYSQL_DATABASE=canteen

# ===== Redis 配置 =====
REDIS_PASSWORD=$REDIS_PASSWORD

# ===== JWT 配置(生产环境必须使用强随机值,禁止使用默认值)=====
JWT_SECRET=$JWT_SECRET

# ===== 管理员初始化 =====
INIT_ADMIN_USERNAME=admin
INIT_ADMIN_PASSWORD=ChangeMeStrongPassword123!
INIT_ADMIN_FORCE=true

# ===== 备份加密 =====
BACKUP_ENCRYPTION_KEY=$BACKUP_ENCRYPTION_KEY

# ===== 环境标识 =====
SPRING_PROFILES_ACTIVE=prod
EOF

# 设置严格权限(仅 owner 可读写,防止其他用户读取密码)
chmod 600 .env
chown canteen:canteen .env

# 确认权限
ls -l .env
# 预期:-rw------- 1 canteen canteen ... .env
```

### 6.3 修改默认管理员密码

```bash
# 编辑 .env,将 INIT_ADMIN_PASSWORD 改为强密码(至少 8 位,含大小写+数字+符号)
nano .env
# 修改 INIT_ADMIN_PASSWORD 为你的强密码
# 保存退出:Ctrl+O, Enter, Ctrl+X
```

**密码强度要求**(前后端一致校验):
- 长度 ≥ 8 位
- 建议包含大写字母、小写字母、数字、符号

### 6.4 验证 .env 配置

```bash
# 检查所有必需变量都已配置
echo "=== .env 配置校验 ==="
for key in MYSQL_ROOT_PASSWORD REDIS_PASSWORD JWT_SECRET INIT_ADMIN_USERNAME INIT_ADMIN_PASSWORD BACKUP_ENCRYPTION_KEY; do
    val=$(grep "^${key}=" .env | cut -d= -f2-)
    if [[ -z "$val" ]]; then
        echo "[缺失] $key"
    else
        echo "[OK] $key (长度: ${#val})"
    fi
done

# 检查 JWT_SECRET 不是弱默认值
JWT_VAL=$(grep "^JWT_SECRET=" .env | cut -d= -f2-)
if [[ ${#JWT_VAL} -lt 32 ]]; then
    echo "[警告] JWT_SECRET 长度不足 32,存在被爆破风险!"
fi
```

---

## 7. 执行部署

### 7.1 运行部署向导

```bash
cd /opt/canteen

# 以 sudo 运行(deploy.sh 会自动修正目录所有权与脚本权限)
sudo ./deploy.sh
```

**部署向导交互流程**:
1. 检测 Docker 环境(已安装则跳过)
2. 检测 .env 配置(已存在则跳过生成)
3. 拉取 Docker 镜像(mysql/redis)
4. 构建业务产物(在 Docker 容器中,无需宿主机 JDK/Node)
5. 启动服务
6. 等待健康检查
7. 初始化管理员账号
8. 显示访问地址

**注意事项**:
- 若提示 `detected dubious ownership`,执行 `git config --global --add safe.directory /opt/canteen` 后重试
- 若提示脚本无执行权限,执行 `chmod +x /opt/canteen/*.sh /opt/canteen/scripts/*.sh` 后重试

### 7.2 监控部署日志

```bash
# 实时查看所有服务日志
docker compose logs -f

# 单独查看后端启动日志(关键,确认无报错)
docker compose logs -f backend | head -100

# 关注以下关键日志:
# - "Started CanteenApplication" - 后端启动成功
# - "AdminInitializer: 超管账号已创建/更新" - 管理员初始化成功
# - "Tomcat started on port 8080" - HTTP 服务就绪
```

### 7.3 等待健康检查通过

```bash
# 轮询健康检查接口(最多等待 60 秒)
for i in $(seq 1 30); do
    if curl -sf http://127.0.0.1:18082/api/system/health >/dev/null 2>&1; then
        echo "[OK] 后端健康检查通过"
        break
    fi
    echo "等待后端启动... ($i/30)"
    sleep 2
done
```

---

## 8. 部署后验证

### 8.1 服务状态检查

```bash
cd /opt/canteen

# 查看所有服务状态
docker compose ps
# 预期:5 个服务全部 Up

# 健康检查
curl -s http://127.0.0.1:18082/api/system/health | python3 -m json.tool
# 预期:{"status":"UP",...}

# 前端访问检查
curl -I http://127.0.0.1:18080/  # admin-web
curl -I http://127.0.0.1:18081/  # h5
# 预期:HTTP/1.1 200 OK
```

### 8.2 端口绑定检查(安全验证)

```bash
# 确认端口仅绑定到 127.0.0.1,不暴露到公网
sudo ss -tlnp | grep -E '18080|18081|18082'
# 预期:全部显示 127.0.0.1:xxxxx,不应出现 0.0.0.0:xxxxx
```

### 8.3 管理员登录测试

```bash
# 使用 .env 中的管理员账号登录
ADMIN_USER=$(grep "^INIT_ADMIN_USERNAME=" .env | cut -d= -f2)
ADMIN_PWD=$(grep "^INIT_ADMIN_PASSWORD=" .env | cut -d= -f2)

curl -s -X POST http://127.0.0.1:18082/api/admin/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PWD\"}" \
  | python3 -m json.tool

# 预期:{"code":200,"message":"成功","data":{...admin info...}}
# 若返回 403 账号锁定,重启后端清除锁定:docker compose restart backend
```

### 8.4 外部访问测试(通过 1panel 反代)

```bash
# 替换为你的实际域名
DOMAIN="dm.canteen.908521.xyz"

# 测试 HTTPS 反代访问
curl -I https://$DOMAIN:9999/
# 预期:HTTP/1.1 200 OK(或 301/302 跳转)

# 测试 H5 访问
curl -I https://$DOMAIN:9999/h5/
```

### 8.5 数据库连接验证

```bash
# 验证后端能正常连接数据库(查看菜单或店铺列表)
curl -s http://127.0.0.1:18082/api/store/list \
  -H "Cookie: auth_token=<登录后的token>" \
  | python3 -m json.tool
# 预期:返回店铺列表(新部署应为空数组或初始数据)
```

### 8.6 冒烟测试清单

在浏览器中完成以下操作,确认功能正常:

| 测试项 | 操作 | 预期结果 |
|--------|------|---------|
| 管理员登录 | 访问 `https://域名:9999/login`,输入管理员账号密码 | 登录成功,跳转仪表盘 |
| H5 登录 | 访问 `https://域名:9999/h5/`,输入员工手机号密码 | 登录成功,跳转首页 |
| 菜单加载 | H5 首页查看今日菜单 | 显示菜品列表(新部署为空) |
| 创建订单 | H5 选择菜品下单 | 下单成功,余额扣减 |
| 后台管理 | admin-web 查看订单列表 | 显示刚才的订单 |
| 文件上传 | admin-web 上传菜品图片 | 上传成功,图片显示正常 |
| 退出登录 | 点击退出 | 跳转登录页,Cookie 清除 |

---

## 9. 清理备份(部署成功 7 天后)

```bash
# ⚠️ 仅在部署成功且稳定运行 7 天后执行!

# 删除旧代码备份
rm -rf "$BACKUP_DIR/canteen-old-code"

# 保留数据库备份(建议长期保留至少 3 份)
# 可删除 7 天前的备份:
find /opt/canteen-backup-* -type d -mtime +7 -exec rm -rf {} \; 2>/dev/null || true
```

---

## 10. 故障处理

### 10.1 git pull 失败:detached HEAD

**现象**: `您当前不在一个分支上。请指定您要合并哪一个分支。`

**原因**: 服务器未跟踪 deploy 分支,处于分离 HEAD 状态。

**解决**:
```bash
cd /opt/canteen

# 切换到 deploy 分支并跟踪远程
git checkout -B deploy origin/deploy
git branch --set-upstream-to=origin/deploy deploy

# 验证
git branch -vv
# 预期:* deploy xxxxxxx [origin/deploy] ...
```

### 10.2 部署后管理员登录失败

**现象**: 提示账号锁定 / 密码错误

**原因**: `INIT_ADMIN_FORCE=true` 未生效,AdminInitializer 跳过了密码更新。

**解决**:
```bash
# 1. 确认 .env 中 INIT_ADMIN_FORCE=true
grep INIT_ADMIN_FORCE /opt/canteen/.env

# 2. 重启后端(清除登录锁定 + 重新触发 AdminInitializer)
cd /opt/canteen
docker compose up -d --force-recreate backend

# 3. 查看后端日志确认密码已更新
docker compose logs backend | grep -i "AdminInitializer"
# 预期:AdminInitializer: 超管账号 'admin' 已更新
```

### 10.3 端口被占用

**现象**: `Bind for 127.0.0.1:18082 failed: port is already allocated`

**解决**:
```bash
# 查找占用端口的进程
sudo ss -tlnp | grep -E '18080|18081|18082'

# 若是残留的 Docker 容器,强制删除
docker ps -a | grep canteen
docker rm -f $(docker ps -a --filter "name=canteen" -q)

# 若是其他进程,根据 PID 处理
sudo kill -9 <PID>
```

### 10.4 Docker 卷权限错误

**现象**: 后端启动报 `Permission denied: /app/uploads`

**原因**: Docker 卷的宿主机目录权限不正确。

**解决**:
```bash
# 查看卷的宿主机路径
docker volume inspect canteen_canteen-uploads

# 修正权限(uploads 目录需可写)
sudo chown -R 1000:1000 /var/lib/docker/volumes/canteen_canteen-uploads/_data
```

### 10.5 浏览器仍显示旧版本

**现象**: 部署后浏览器访问仍是旧界面

**原因**: 浏览器缓存未清理。

**解决**:
1. 强制刷新:`Ctrl + F5`(Windows)/ `Cmd + Shift + R`(Mac)
2. 或打开开发者工具(F12)→ Network 标签 → 勾选 "Disable cache" → 刷新
3. 或清除浏览器缓存后重新访问
4. 终极方案:无痕/隐身模式打开

### 10.6 1panel 反代 502 Bad Gateway

**现象**: 外部访问返回 502,但本机 curl 127.0.0.1:18080 正常

**原因**: 1panel 反代配置的后端地址错误,或后端未启动。

**解决**:
1. 登录 1panel 面板
2. 进入「网站」→ 找到反代站点 → 「配置」→「反向代理」
3. 确认后端地址为 `http://127.0.0.1:18080`(admin-web)或 `http://127.0.0.1:18081`(h5)
4. 确认后端服务已启动:`docker compose ps`

---

## 11. 回滚方案(部署失败时)

### 11.1 从备份恢复数据库

```bash
# 停止当前服务
cd /opt/canteen
docker compose down

# 恢复旧代码
cd /opt
rm -rf /opt/canteen
cp -r "$BACKUP_DIR/canteen-old-code" /opt/canteen
sudo chown -R canteen:canteen /opt/canteen

# 恢复 .env
cp "$BACKUP_DIR/.env.bak" /opt/canteen/.env
chmod 600 /opt/canteen/.env

# 启动服务
cd /opt/canteen
docker compose up -d

# 等待 MySQL 启动
sleep 15

# 恢复数据库
DB_FILE=$(ls "$BACKUP_DIR"/canteen-db-*.sql.gz | head -1)
gunzip < "$DB_FILE" | docker compose exec -T mysql \
  -u root \
  -p"$(grep MYSQL_ROOT_PASSWORD /opt/canteen/.env | cut -d= -f2)" \
  canteen

# 验证恢复
docker compose exec mysql mysql \
  -u root \
  -p"$(grep MYSQL_ROOT_PASSWORD /opt/canteen/.env | cut -d= -f2)" \
  -e "USE canteen; SELECT COUNT(*) FROM employee;"
```

### 11.2 恢复上传文件

```bash
UPLOAD_FILE=$(ls "$BACKUP_DIR"/uploads-*.tar.gz | head -1)
docker run --rm \
  -v canteen_canteen-uploads:/data/uploads \
  -v "$BACKUP_DIR":/backup \
  alpine:latest \
  tar xzf "/backup/$(basename "$UPLOAD_FILE")" -C /data
```

### 11.3 验证回滚

```bash
# 重启所有服务
docker compose restart

# 健康检查
curl -s http://127.0.0.1:18082/api/system/health

# 登录测试
curl -s -X POST http://127.0.0.1:18082/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"旧密码"}'
```

---

## 12. 操作检查清单

执行完毕后,逐项确认:

- [ ] 备份目录已创建,数据库/配置/上传文件备份完整
- [ ] `gunzip -t` 备份完整性校验通过
- [ ] 所有 Docker 服务已停止(`docker compose ps` 无运行中容器)
- [ ] 旧代码已移动到备份目录
- [ ] Docker 旧镜像/卷/网络已清理
- [ ] deploy 分支已克隆到 `/opt/canteen`
- [ ] 目录所有权为 `canteen:canteen`
- [ ] 所有 .sh 脚本具有执行权限
- [ ] `.env` 已生成,包含强随机密钥,权限为 600
- [ ] `INIT_ADMIN_FORCE=true` 已设置
- [ ] `deploy.sh` 执行完成,无报错
- [ ] 后端健康检查通过(`/api/system/health` 返回 UP)
- [ ] 端口绑定检查:全部为 `127.0.0.1`
- [ ] 管理员登录测试成功
- [ ] 1panel 反代访问正常(HTTPS 200)
- [ ] 冒烟测试全部通过
- [ ] 备份目录路径已记录(便于回滚)
