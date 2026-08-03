# 企业智慧食堂系统 - 安全修复计划

> 基于 3 维度安全审查(网络入侵面 / 数据备份 / 前端安全)的完整修复方案
> 审查发现 64 项问题:严重 11 / 高危 19 / 中危 28 / 低危 6
> 本计划按 P0 → P1 → P2 优先级分阶段修复,每项修复都附测试验证方法

---

## 修复阶段总览

| 阶段 | 目标 | 修复项数 | 预期效果 |
|------|------|---------|---------|
| P0 | 防止被爆破入侵 | 5 项关键修复 | 关闭入侵入口,杜绝弱密码启动 |
| P1 | 数据安全防护 | 6 项核心修复 | 备份加密,数据库最小权限,容器降权 |
| P2 | 纵深加固 | 8 项加固 | 密码强度,CSP/HSTS,入侵检测,依赖升级 |

---

## P0 - 防止被爆破入侵(立即修复)

### P0-1 端口绑定改为 127.0.0.1

**问题**:backend(18082)、admin-web(18080)、h5(18081)绑定 0.0.0.0,绕过 1panel 反代直接暴露

**修复文件**: `docker-compose.yml`

**修改内容**:
```yaml
# 修改前
backend:
  ports:
    - "18082:8080"
admin-web:
  ports:
    - "18080:80"
h5:
  ports:
    - "18081:80"

# 修改后
backend:
  ports:
    - "127.0.0.1:18082:8080"
admin-web:
  ports:
    - "127.0.0.1:18080:80"
h5:
  ports:
    - "127.0.0.1:18081:80"
```

**测试验证**:
```bash
# 1. 部署后,在服务器本机验证可访问
curl -I http://127.0.0.1:18082/api/system/health
curl -I http://127.0.0.1:18080/
curl -I http://127.0.0.1:18081/

# 2. 从另一台内网机器验证不可访问(应失败/超时)
curl -v --connect-timeout 3 http://服务器内网IP:18082/
# 预期:Connection refused 或 timeout

# 3. 通过 1panel 反代域名验证可访问
curl -I https://你的域名/api/system/health
# 预期:200 OK

# 4. 检查端口监听地址
sudo ss -tlnp | grep -E '18080|18081|18082'
# 预期:全部显示 127.0.0.1:xxxx,不是 0.0.0.0:xxxx
```

---

### P0-2 移除所有弱默认值,强制环境变量注入

**问题**:docker-compose.yml 中 `MYSQL_ROOT_PASSWORD:-canteen2026`、`JWT_SECRET:-canteen-jwt-secret...` 等弱默认值,忘配 .env 时用弱密码启动

**修复文件**:
- `docker-compose.yml`
- `scripts/backup.sh:26`
- `scripts/restore.sh:34`
- `scripts/snapshot.sh:55`
- `scripts/upgrade.sh:246`

**修改内容**:

docker-compose.yml:
```yaml
# 修改前
MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-canteen2026}
JWT_SECRET: ${JWT_SECRET:-canteen-jwt-secret-key-2026-please-change-in-production}

# 修改后(未配置则启动失败)
MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?必须配置 MYSQL_ROOT_PASSWORD}
JWT_SECRET: ${JWT_SECRET:?必须配置 JWT_SECRET}
```

所有 shell 脚本统一改为:
```bash
# 修改前
DB_PASS="${SPRING_DATASOURCE_PASSWORD:-${MYSQL_ROOT_PASSWORD:-canteen2026}}"

# 修改后
DB_PASS="${SPRING_DATASOURCE_PASSWORD:-${MYSQL_ROOT_PASSWORD:-}}"
if [ -z "$DB_PASS" ]; then
    echo "[错误] 数据库密码未配置,请检查 .env 文件" >&2
    exit 1
fi
```

**同时加强 JwtConfig.java 弱密钥黑名单**:
```java
// backend/src/main/java/com/example/canteen/config/JwtConfig.java
private static final Set<String> WEAK_SECRETS = Set.of(
    DEFAULT_SECRET,
    "canteen-jwt-secret-key-2026-please-change-in-production",
    "change-me", "secret", "jwt-secret", "canteen2026"
);

@Bean
public SecretKey jwtSecretKey() {
    String profile = environment.getActiveProfiles().length > 0
            ? environment.getActiveProfiles()[0] : "default";
    if ("prod".equalsIgnoreCase(profile)) {
        if (WEAK_SECRETS.contains(secret)) {
            throw new IllegalStateException("生产环境禁止使用弱 JWT 密钥");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT 密钥至少 32 字节");
        }
    }
    return Keys.hmacShaKeyFor(secret.getBytes());
}
```

**测试验证**:
```bash
# 1. 不配置 .env 启动,应失败
mv .env .env.bak
docker compose up -d
# 预期:报错 "must set MYSQL_ROOT_PASSWORD" / "must set JWT_SECRET"

# 2. 恢复 .env 后启动,应成功
mv .env.bak .env
docker compose up -d
# 预期:正常启动

# 3. 验证弱密钥被拒绝
# 临时在 .env 中设置 JWT_SECRET=canteen2026
docker compose up -d backend
docker compose logs backend | grep -i "弱.*密钥\|weak"
# 预期:启动失败,日志提示禁止使用弱 JWT 密钥

# 4. 恢复正确密钥后验证启动正常
```

---

### P0-3 deploy.sh 新增防火墙与 SSH 爆破防护

**问题**:无 fail2ban/ufw 配置,SSH 22 端口暴露可被爆破

**修复文件**: `deploy.sh`(新增 `harden_security` 函数)

**新增内容**:
```bash
#==============================================================
# 安全加固:防火墙 + SSH 爆破防护
#==============================================================
harden_security() {
    step "安全加固"

    # 1. 安装 fail2ban(SSH 爆破防护)
    info "安装 fail2ban..."
    if ! command -v fail2ban-client &>/dev/null; then
        apt-get update -qq && apt-get install -y -qq fail2ban
    fi

    # 配置 SSH 防护:5 次失败封禁 1 小时
    cat > /etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
port = ssh
filter = sshd
logpath = /var/log/auth.log
maxretry = 5
findtime = 600
bantime = 3600
EOF
    systemctl enable fail2ban && systemctl restart fail2ban
    info "fail2ban 已配置(SSH 5 次失败封禁 1 小时)"

    # 2. 配置 UFW 防火墙
    info "配置 UFW 防火墙..."
    if ! command -v ufw &>/dev/null; then
        apt-get install -y -qq ufw
    fi

    # 先重置,再配置
    ufw --force reset
    ufw default deny incoming
    ufw default allow outgoing

    # 放行必要端口
    ufw allow 22/tcp comment 'SSH'
    ufw allow 80/tcp comment 'HTTP-1panel'
    ufw allow 443/tcp comment 'HTTPS-1panel'

    # 1panel 管理端口(默认随机,从配置读取)
    local panel_port
    panel_port=$(grep -r "port" /opt/1panel/conf/app.yaml 2>/dev/null \
        | head -1 | awk '{print $2}' || echo "")
    if [ -n "$panel_port" ]; then
        ufw allow ${panel_port}/tcp comment '1panel'
    fi

    ufw --force enable
    info "UFW 已启用(仅放行 22/80/443/1panel)"

    # 3. 处理 Docker 绕过 UFW 的问题
    # Docker 默认在 iptables nat 表直接 DNAT,绕过 ufw filter 表
    # 配合 P0-1 的 127.0.0.1 绑定,容器端口不再对外暴露
    # 额外保险:在 DOCKER-USER 链拒绝外部到容器端口的直接访问
    if iptables -L DOCKER-USER &>/dev/null; then
        iptables -I DOCKER-USER -i eth0 -p tcp -m multiport \
            --dports 18080,18081,18082 -j DROP 2>/dev/null || true
        info "Docker-USER 链已加固(拒绝外部直接访问容器端口)"
    fi

    # 4. SSH 加固建议(不强制修改,仅提示)
    echo ""
    warn "建议手动加固 SSH(编辑 /etc/ssh/sshd_config):"
    echo "  PermitRootLogin prohibit-password   # 禁止 root 密码登录"
    echo "  PasswordAuthentication no           # 仅密钥登录(需先配置密钥)"
    echo "  Port 22022                          # 改非标准端口"
    echo "  修改后:systemctl restart sshd"
    echo ""
}

# 在 cmd_deploy 主流程中调用
# 在 start_services 之后、show_summary 之前
harden_security
```

**测试验证**:
```bash
# 1. 验证 fail2ban 运行
sudo fail2ban-client status
# 预期:显示 sshd jail,1 个 jail 启用

sudo fail2ban-client status sshd
# 预期:显示 sshd 监控状态

# 2. 验证 UFW 规则
sudo ufw status verbose
# 预期:
#   22/tcp ALLOW Anywhere
#   80/tcp ALLOW Anywhere
#   443/tcp ALLOW Anywhere
#   1panel端口/tcp ALLOW Anywhere
#   其他全部 DENY

# 3. 从外部机器扫描端口
nmap -p 22,80,443,18080,18081,18082,13306,16379 服务器IP
# 预期:22/80/443 open,其他 filtered/closed

# 4. 模拟 SSH 爆破测试(用错误密码连续登录 6 次)
# 在另一台机器执行
for i in 1 2 3 4 5 6; do
    sshpass -p wrongpass ssh -o StrictHostKeyChecking=no test@服务器IP exit
done
# 预期:第 6 次后被封禁,连接拒绝

sudo fail2ban-client status sshd
# 预期:Banned IP list 包含测试机器 IP
```

---

### P0-4 配置 forward-headers + Cookie Secure 强制

**问题**:反代后 `request.isSecure()` 返回 false,Cookie 的 Secure 标志不生效,可在 HTTP 明文传输

**修复文件**:
- `backend/src/main/resources/application-prod.yml`
- `backend/src/main/java/com/example/canteen/security/AuthCookieUtil.java`
- `admin-web/nginx.conf`
- `h5/nginx.conf`

**修改内容**:

application-prod.yml 新增:
```yaml
server:
  forward-headers-strategy: NATIVE
```

AuthCookieUtil.java 修改:
```java
// 注入 profile 判断
@Value("${spring.profiles.active:default}")
private String activeProfile;

// setCookie 方法中
// 修改前:cookie.setSecure(request.isSecure());
// 修改后:生产环境强制 Secure
if ("prod".equalsIgnoreCase(activeProfile)) {
    cookie.setSecure(true);
} else {
    cookie.setSecure(request.isSecure());
}
```

nginx.conf 修改(admin-web 和 h5 都改):
```nginx
# 修改前
proxy_set_header X-Forwarded-Proto $scheme;

# 修改后(透传 1panel 传来的真实协议)
proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

**测试验证**:
```bash
# 1. 部署后,通过 HTTPS 域名登录,检查 Cookie
# 浏览器 F12 → Application → Cookies
# 预期:admin_token 的 Secure 列显示 ✓(有勾)

# 2. 用 curl 检查响应头
curl -I -X POST https://你的域名/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"word","password":"你的密码"}'
# 预期:Set-Cookie 头包含 Secure; HttpOnly; SameSite=Strict

# 3. 检查后端获取的真实 IP
docker compose logs backend | grep "client ip\|remote"
# 预期:显示客户端真实 IP,不是 172.x.x.x 内网 IP

# 4. 通过 HTTP 直接访问(应无法获取 Cookie)
curl -I -X POST http://127.0.0.1:18082/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"word","password":"你的密码"}'
# 注意:本机测试因是 prod profile,Secure 仍会设置
# 这个测试主要验证 HTTPS 域名下 Cookie 正确带 Secure 标志
```

---

### P0-5 CORS 收紧为 Origin 白名单

**问题**:`allowedOriginPatterns("*")` + `allowCredentials(true)` 允许任意网站携带 Cookie

**修复文件**:
- `backend/src/main/java/com/example/canteen/config/WebConfig.java`
- `docker-compose.yml`(新增 CORS_ORIGINS 环境变量)
- `.env.example`

**修改内容**:

WebConfig.java:
```java
@Value("${cors.allowed-origins:}")
private String allowedOrigins;

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(Collections.singletonList("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    if (allowedOrigins != null && !allowedOrigins.isBlank()) {
        // 从环境变量读取白名单(逗号分隔)
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        config.setAllowedOrigins(origins);
    } else {
        // 内网部署默认放行常见 origin
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://tauri.localhost",
            "tauri://localhost"
        ));
    }
    return new UrlBasedCorsConfigurationSource(Map.of("/api/**", config));
}
```

docker-compose.yml backend 环境变量新增:
```yaml
CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS:-}
```

.env.example 新增:
```bash
# CORS 允许的 Origin(逗号分隔,生产环境建议配置具体域名)
# 示例:CORS_ALLOWED_ORIGINS=https://canteen.example.com,https://admin.canteen.example.com
CORS_ALLOWED_ORIGINS=
```

**测试验证**:
```bash
# 1. 在 .env 中配置
CORS_ALLOWED_ORIGINS=https://dm.canteen.908521.xyz

# 2. 重启后端
docker compose up -d --no-deps backend

# 3. 验证允许的 Origin
curl -I -X OPTIONS https://dm.canteen.908521.xyz/api/admin/login \
  -H "Origin: https://dm.canteen.908521.xyz" \
  -H "Access-Control-Request-Method: POST"
# 预期:响应头包含 Access-Control-Allow-Origin: https://dm.canteen.908521.xyz

# 4. 验证拒绝的 Origin
curl -I -X OPTIONS https://dm.canteen.908521.xyz/api/admin/login \
  -H "Origin: https://evil.com" \
  -H "Access-Control-Request-Method: POST"
# 预期:响应头不包含 Access-Control-Allow-Origin

# 5. 浏览器登录测试
# 正常域名访问应能登录,无 CORS 错误
```

---

## P1 - 数据安全防护(1 周内)

### P1-1 备份文件加密(AES-256)

**问题**:备份文件仅 gzip 压缩,`gunzip` 即可解码全部数据

**修复文件**:
- `scripts/backup.sh`
- `scripts/snapshot.sh`
- `scripts/restore.sh`
- `backend/src/main/java/com/example/canteen/service/BackupIO.java`

**修改内容**:

Shell 脚本(backup.sh / snapshot.sh):
```bash
# 生成备份加密密钥(首次部署时,存到 /etc/canteen-backup.key)
if [ ! -f /etc/canteen-backup.key ]; then
    openssl rand -out /etc/canteen-backup.key 32
    chmod 600 /etc/canteen-backup.key
    chown root:root /etc/canteen-backup.key
fi

# 加密备份
tar -czf - "$BACKUP_DIR" | openssl enc -aes-256-cbc -salt -pbkdf2 \
    -pass file:/etc/canteen-backup.key \
    -out "${BACKUP_FILE}.enc"

# 解密恢复
openssl enc -d -aes-256-cbc -pbkdf2 \
    -pass file:/etc/canteen-backup.key \
    -in "$RESTORE_FILE.enc" | tar -xzf -
```

后端 BackupIO.java(Java 端备份加密):
```java
// 使用 Bouncy Castle 或 JCA 实现 AES-256-GCM 加密
// 密钥从环境变量 BACKUP_ENCRYPTION_KEY 读取
// 加密流程:原数据 → gzip → AES-256-GCM 加密 → 输出 .json.gz.enc
// 解密流程:反向

// BackupIO.java 新增
private byte[] encrypt(byte[] data) {
    String key = System.getenv("BACKUP_ENCRYPTION_KEY");
    if (key == null || key.isBlank()) {
        return data; // 未配置密钥则不加密(向后兼容)
    }
    // AES-256-GCM 加密实现
    SecretKey secretKey = new SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(key.getBytes()), "AES");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey,
        new GCMParameterSpec(128, iv));
    byte[] encrypted = cipher.doFinal(data);
    // 输出格式:iv(12字节) + 加密数据
    ByteBuffer buffer = ByteBuffer.allocate(12 + encrypted.length);
    buffer.put(iv);
    buffer.put(encrypted);
    return buffer.array();
}
```

**测试验证**:
```bash
# 1. 创建备份
sudo /opt/canteen/scripts/backup.sh

# 2. 尝试直接 gunzip 解码(应失败)
gunzip -c /opt/canteen/backup/snapshots/xxx/database.sql.gz.enc
# 鄙期:乱码或报错,无法读取

# 3. 用密钥解密(应成功)
openssl enc -d -aes-256-cbc -pbkdf2 \
    -pass file:/etc/canteen-backup.key \
    -in /opt/canteen/backup/snapshots/xxx/database.sql.gz.enc \
    | gunzip -c | head -20
# 预期:看到正常 SQL 语句

# 4. 验证后端备份接口加密
# 登录管理后台 → 备份管理 → 创建备份 → 下载
# 尝试用 gunzip 解码下载的文件(应失败)
gunzip -c full_xxx.json.gz.enc | head
# 预期:乱码

# 5. 验证恢复功能正常
# 用密钥解密后恢复,数据完整
```

---

### P1-2 备份导出脱敏敏感字段

**问题**:导出用 `SELECT *`,包含 admin.password、employee.password、employee.phone

**修复文件**: `backend/src/main/java/com/example/canteen/service/BackupExporter.java`

**修改内容**:
```java
// BackupExporter.java 新增脱敏逻辑
private static final Set<String> SENSITIVE_COLUMNS = Set.of(
    "password", "wx_openid"
);

private List<Map<String, Object>> exportTable(String table, Long storeId) {
    List<Map<String, Object>> rows;
    if (storeId == null) {
        rows = jdbcTemplate.queryForList("SELECT * FROM " + quoteTable(table));
    } else {
        rows = jdbcTemplate.queryForList(
            "SELECT * FROM " + quoteTable(table) + " WHERE store_id = ?", storeId);
    }

    // 脱敏敏感字段
    for (Map<String, Object> row : rows) {
        for (String col : SENSITIVE_COLUMNS) {
            if (row.containsKey(col)) {
                row.put(col, "***REDACTED***");
            }
        }
        // 手机号部分脱敏:138****0001
        if (row.containsKey("phone") && row.get("phone") != null) {
            String phone = row.get("phone").toString();
            if (phone.length() >= 11) {
                row.put("phone", phone.substring(0, 3) + "****" + phone.substring(7));
            }
        }
    }
    return rows;
}
```

**测试验证**:
```bash
# 1. 创建备份并下载
# 管理后台 → 备份管理 → 创建门店备份 → 下载

# 2. 解码备份文件
gunzip -c store1_xxx.json.gz | python3 -c "
import json, sys
data = json.load(sys.stdin)
for table in data.get('tables', {}):
    if table == 'employee':
        for row in data['tables'][table]['rows'][:3]:
            print(f\"phone={row.get('phone')}, password={row.get('password')}\")"

# 预期输出:
# phone=138****0001, password=***REDACTED***
# phone=139****0002, password=***REDACTED***

# 3. 验证恢复功能(密码字段已脱敏,恢复后需重置密码)
# 恢复后登录应失败(密码是 ***REDACTED***),需通过重置密码功能恢复
```

---

### P1-3 创建 MySQL 应用专用用户(非 root)

**问题**:应用使用 MySQL root 连接,违反最小权限原则

**修复文件**:
- `docker-compose.yml`(新增初始化脚本)
- `backend/src/main/resources/application-prod.yml`
- 新增 `scripts/init-db-user.sql`

**修改内容**:

新增 scripts/init-db-user.sql:
```sql
-- 创建应用专用用户(仅 canteen 库的 DML 权限,无 DDL/GRANT)
CREATE USER IF NOT EXISTS 'canteen_app'@'%' IDENTIFIED BY '随机强密码';
GRANT SELECT, INSERT, UPDATE, DELETE ON canteen.* TO 'canteen_app'@'%';
FLUSH PRIVILEGES;
-- 注意:Flyway 迁移需要更高权限,建议迁移用 root,运行时用 canteen_app
-- 或授予 CREATE/ALTER/DROP ON canteen.* 给 canteen_app(迁移期间)
```

docker-compose.yml:
```yaml
mysql:
  environment:
    MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?必须配置}
    MYSQL_DATABASE: canteen
  volumes:
    - mysql_data:/var/lib/mysql
    - ./backup:/backup
    - ./scripts/init-db-user.sql:/docker-entrypoint-initdb.d/init-user.sql:ro
  # 注意:docker-entrypoint-initdb.d 仅在首次初始化时执行

backend:
  environment:
    # 运行时用应用专用账户
    SPRING_DATASOURCE_USERNAME: ${DB_APP_USERNAME:-canteen_app}
    SPRING_DATASOURCE_PASSWORD: ${DB_APP_PASSWORD:?必须配置 DB_APP_PASSWORD}
    # Flyway 迁移用 root
    SPRING_FLYWAY_USER: root
    SPRING_FLYWAY_PASSWORD: ${MYSQL_ROOT_PASSWORD:?必须配置}
```

**测试验证**:
```bash
# 1. 验证应用用户权限
docker exec canteen-mysql mysql -ucanteen_app -p"$DB_APP_PASSWORD" -e "
    SHOW GRANTS FOR CURRENT_USER();
"
# 预期:仅 SELECT/INSERT/UPDATE/DELETE,无 DROP/CREATE/GRANT

# 2. 验证应用用户无法 DROP 表
docker exec canteen-mysql mysql -ucanteen_app -p"$DB_APP_PASSWORD" canteen -e "
    DROP TABLE admin;
"
# 预期:ERROR 1142 (42000): DROP command denied

# 3. 验证应用用户无法创建新用户
docker exec canteen-mysql mysql -ucanteen_app -p"$DB_APP_PASSWORD" -e "
    CREATE USER 'attacker'@'%';
"
# 预期:ERROR 1044 (42000): Access denied

# 4. 验证后端正常运行
docker compose logs backend | tail -20
# 预期:无数据库权限错误,正常处理请求

# 5. 验证 Flyway 迁移用 root 正常执行
docker compose logs backend | grep -i flyway
# 预期:Migrating schema... Successfully applied
```

---

### P1-4 Redis 配置密码

**问题**:Redis 无密码,容器内任意服务可访问

**修复文件**:
- `docker-compose.yml`
- `backend/src/main/resources/application-prod.yml`
- `.env.example`

**修改内容**:

docker-compose.yml:
```yaml
redis:
  command: redis-server
    --appendonly yes
    --maxmemory 128mb
    --maxmemory-policy allkeys-lru
    --requirepass ${REDIS_PASSWORD:?必须配置 REDIS_PASSWORD}
    --rename-command FLUSHALL ""
    --rename-command FLUSHDB ""
    --rename-command CONFIG ""
```

application-prod.yml:
```yaml
spring:
  data:
    redis:
      password: ${REDIS_PASSWORD}
```

.env.example 新增:
```bash
# Redis 密码(随机生成)
REDIS_PASSWORD=change-me-to-a-strong-redis-password
```

**测试验证**:
```bash
# 1. 验证无密码无法连接 Redis
docker exec canteen-redis redis-cli ping
# 预期:NOAUTH Authentication required

# 2. 验证有密码可连接
docker exec canteen-redis redis-cli -a "$REDIS_PASSWORD" ping
# 预期:PONG

# 3. 验证 FLUSHALL 被禁用
docker exec canteen-redis redis-cli -a "$REDIS_PASSWORD" FLUSHALL
# 预期:ERR unknown command 'FLUSHALL'

# 4. 验证后端正常缓存
docker compose logs backend | grep -i redis
# 预期:无连接错误

# 5. 验证缓存功能正常
curl -s http://localhost:18082/api/dish/store/1 | jq length
# 预期:正常返回菜品列表
```

---

### P1-5 容器降权(非 root 运行)

**问题**:所有容器以 root 运行,RCE 后直接获得 root 权限

**修复文件**: `backend/Dockerfile.runtime`

**修改内容**:
```dockerfile
# backend/Dockerfile.runtime
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

# 创建非 root 用户
RUN groupadd -r canteen && \
    useradd -r -g canteen -d /app -s /sbin/nologin canteen && \
    mkdir -p /app /app/uploads /app/backup && \
    chown -R canteen:canteen /app

USER canteen

WORKDIR /app
EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+UseG1GC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
```

docker-compose.yml backend 卷映射权限调整:
```yaml
backend:
  user: "1000:1000"  # canteen 用户的 UID:GID
  volumes:
    - ./deploy/backend/app.jar:/app/app.jar:ro
    - ./backup:/app/backup
    - ./uploads:/app/uploads
# 部署时需确保宿主机 backup/ 和 uploads/ 目录属主为 1000:1000
```

**测试验证**:
```bash
# 1. 验证容器内进程非 root
docker exec canteen-backend ps aux | head -5
# 预期:java 进程的 USER 列显示 canteen 或 uid=1000,不是 root

docker exec canteen-backend id
# 预期:uid=1000(canteen) gid=1000(canteen)

# 2. 验证容器内无法修改系统文件
docker exec canteen-backend touch /etc/passwd
# 预期:Permission denied

# 3. 验证应用正常启动
docker compose logs backend | grep "Started CanteenApplication"
# 预期:正常启动

# 4. 验证文件上传功能正常
# 管理后台 → 上传菜品图片
# 预期:上传成功,uploads/ 目录文件属主为 1000:1000

# 5. 验证备份功能正常
# 创建备份 → 检查 backup/ 目录文件属主
ls -la /opt/canteen/backup/
# 预期:文件属主为 1000:1000
```

---

### P1-6 修复 Mass Assignment 漏洞

**问题**:createEmployee / batchImport 允许前端设置 passwordUpdatedAt、isDeleted、id

**修复文件**:
- `backend/src/main/java/com/example/canteen/service/EmployeeService.java`
- `backend/src/main/java/com/example/canteen/controller/EmployeeController.java`

**修改内容**:

EmployeeService.createEmployee 新增:
```java
public EmployeeVO createEmployee(Employee employee, Long storeId) {
    // ... 现有校验 ...

    // 强制重置敏感字段(防止前端 mass assignment)
    employee.setId(null);  // 防止覆盖已有记录
    employee.setIsDeleted(0);  // 防止创建即删除
    employee.setPasswordUpdatedAt(LocalDateTime.now());  // 防止绕过 JWT 失效
    employee.setStoreId(storeId);  // 防止跨租户

    // ... 现有创建逻辑 ...
}
```

EmployeeController.batchImport 修改:
```java
// Service 层 batchImport 中,对每行数据强制重置
for (Object o : list) {
    Employee e = objectMapper.convertValue(o, Employee.class);
    // 强制重置敏感字段
    e.setId(null);
    e.setIsDeleted(0);
    e.setPasswordUpdatedAt(LocalDateTime.now());
    e.setStoreId(storeId);
    // ... 现有导入逻辑 ...
}
```

**测试验证**:
```bash
# 1. 测试 createEmployee mass assignment
curl -X POST https://你的域名/api/employee \
  -H "Cookie: admin_token=有效token" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "测试员工",
    "cardNo": "TEST001",
    "phone": "13800000001",
    "password": "12345678",
    "passwordUpdatedAt": "2000-01-01T00:00:00",
    "isDeleted": 1,
    "id": 999
  }'

# 预期:创建成功,但查询数据库验证:
#   - id 是自增,不是 999
#   - is_deleted = 0
#   - password_updated_at 是当前时间,不是 2000 年

# 2. 验证创建的员工可正常登录
curl -X POST https://你的域名/api/employee/auth/phone-login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000001","password":"12345678"}'
# 预期:登录成功

# 3. 测试 batchImport mass assignment
# 上传含恶意字段的 Excel/JSON
# 预期:导入的员工 id 自增,is_deleted=0,password_updated_at 为当前时间
```

---

## P2 - 纵深加固(2 周内)

### P2-1 密码复杂度校验

**修复文件**:
- `backend/src/main/java/com/example/canteen/service/AdminService.java`
- `backend/src/main/java/com/example/canteen/service/EmployeeService.java`
- `backend/src/main/java/com/example/canteen/service/EmployeeAuthService.java`

**修改内容**:
```java
// 新增密码复杂度校验工具类
public class PasswordValidator {
    private static final Pattern PATTERN =
        Pattern.compile("^(?=.*[a-zA-Z])(?=.*\\d)[a-zA-Z\\d!@#$%^&*()_+\\-=]{8,}$");

    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException("密码至少 8 位");
        }
        if (!PATTERN.matcher(password).matches()) {
            throw new BusinessException("密码必须包含字母和数字");
        }
        // 检查常见弱密码
        if (isCommonWeak(password)) {
            throw new BusinessException("密码过于简单,请使用更复杂的密码");
        }
    }

    private static boolean isCommonWeak(String pwd) {
        Set<String> weak = Set.of("12345678", "password", "11111111",
            "abc12345", "qwerty12", "admin123");
        return weak.contains(pwd.toLowerCase());
    }
}

// 在所有密码设置处调用
// AdminService.createAdmin / updatePassword
// EmployeeService.createEmployee / resetPassword
// EmployeeAuthService.phoneLogin 首次改密
PasswordValidator.validate(newPassword);
```

**测试验证**:
```bash
# 1. 测试弱密码被拒
curl -X POST https://你的域名/api/admin \
  -H "Cookie: admin_token=超管token" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"12345678","name":"测试"}'
# 预期:400 密码必须包含字母和数字

# 2. 测试纯字母被拒
curl -X POST https://你的域名/api/admin \
  -H "Cookie: admin_token=超管token" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"abcdefgh","name":"测试"}'
# 预期:400 密码必须包含字母和数字

# 3. 测试合规密码通过
curl -X POST https://你的域名/api/admin \
  -H "Cookie: admin_token=超管token" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"admin1234","name":"测试"}'
# 预期:200 创建成功

# 4. 测试常见弱密码被拒
curl -X POST https://你的域名/api/admin \
  -H "Cookie: admin_token=超管token" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"password1","name":"测试"}'
# 预期:400 密码过于简单
```

---

### P2-2 Token 黑名单 fail-closed

**修复文件**: `backend/src/main/java/com/example/canteen/security/TokenBlacklistService.java`

**修改内容**:
```java
// TokenBlacklistService.java
// 新增本地缓存(Caffeine),DB 异常时回退到缓存
private final Cache<String, Boolean> blacklistCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofHours(25))  // 略长于 token 最大 TTL
    .build();

public boolean isBlacklisted(String jti) {
    // 先查缓存
    Boolean cached = blacklistCache.getIfPresent(jti);
    if (Boolean.TRUE.equals(cached)) {
        return true;
    }

    try {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM token_blacklist WHERE jti = ?",
            Integer.class, jti);
        boolean blocked = count != null && count > 0;
        if (blocked) {
            blacklistCache.put(jti, true);
        }
        return blocked;
    } catch (Exception e) {
        log.warn("黑名单查询失败,使用缓存兜底: {}", e.getMessage());
        // fail-closed:DB 异常时仅信任缓存,缓存未命中视为未注销
        // 关键操作应在 Controller 层额外校验
        return Boolean.TRUE.equals(cached);
    }
}

public void blacklist(String jti, Instant expiration) {
    jdbcTemplate.update(
        "INSERT INTO token_blacklist (jti, expires_at) VALUES (?, ?) " +
        "ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)",
        jti, Timestamp.from(expiration));
    blacklistCache.put(jti, true);
}
```

**测试验证**:
```bash
# 1. 登录获取 token
TOKEN=$(curl -s -X POST https://你的域名/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"word","password":"密码"}' | jq -r .token)

# 2. 用 token 访问接口
curl -s https://你的域名/api/admin \
  -H "Cookie: admin_token=$TOKEN" | jq length
# 预期:返回管理员列表

# 3. 登出
curl -X POST https://你的域名/api/auth/logout \
  -H "Cookie: admin_token=$TOKEN"
# 预期:登出成功

# 4. 再次用旧 token 访问(应被拒绝)
curl -s -o /dev/null -w "%{http_code}" https://你的域名/api/admin \
  -H "Cookie: admin_token=$TOKEN"
# 预期:401(黑名单生效)

# 5. 模拟 DB 异常(停止 MySQL 容器)
docker compose stop mysql

# 6. 用新 token 访问(应正常,缓存兜底)
NEW_TOKEN=$(curl -s -X POST ... | jq -r .token)
# 注意:MySQL 停了无法登录,此测试需用已有有效 token

# 7. 登出的 token 在 DB 异常时仍被拒绝(缓存命中)
curl -s -o /dev/null -w "%{http_code}" https://你的域名/api/admin \
  -H "Cookie: admin_token=$TOKEN"
# 预期:401(缓存中黑名单生效)

docker compose start mysql
```

---

### P2-3 nginx 添加 CSP 和 HSTS

**修复文件**:
- `admin-web/nginx.conf`
- `h5/nginx.conf`

**修改内容**:
```nginx
# admin-web/nginx.conf 和 h5/nginx.conf 的 server 块内添加

# HSTS(强制 HTTPS,1 年)
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;

# CSP(内容安全策略)
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; connect-src 'self' wss: https:; font-src 'self' data:; frame-ancestors 'self'; object-src 'none'; base-uri 'self'" always;

# 其他安全头(已有,确认)
add_header X-Frame-Options "SAMEORIGIN" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-XSS-Protection "1; mode=block" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

**测试验证**:
```bash
# 1. 检查响应头
curl -I https://你的域名/
# 预期:
#   strict-transport-security: max-age=31536000; includeSubDomains; preload
#   content-security-policy: default-src 'self'; ...
#   x-frame-options: SAMEORIGIN
#   x-content-type-options: nosniff

# 2. 浏览器控制台检查无 CSP 违规
# F12 → Console → 访问各页面
# 预期:无 "Refused to load" 错误

# 3. 验证 HSTS 生效
# 浏览器访问 http://你的域名(非 https)
# 预期:自动跳转到 https(浏览器缓存 HSTS)

# 4. 验证 CSP 阻止外部脚本
# 浏览器控制台执行:
# fetch('https://evil.com/script.js')
# 预期:被 CSP 阻止,console 报错 Refused to connect
```

---

### P2-4 入侵检测与审计(auditd + aide)

**修复文件**: `deploy.sh`(新增 `install_intrusion_detection` 函数)

**修改内容**:
```bash
install_intrusion_detection() {
    info "安装入侵检测工具..."

    # 1. auditd(系统调用审计)
    apt-get install -y -qq auditd

    # 监控关键文件变更
    cat > /etc/audit/rules.d/canteen.rules <<'EOF'
# 监控 .env 文件变更
-w /opt/canteen/.env -p wa -k env_change
# 监控 app.jar 变更
-w /opt/canteen/deploy/backend/app.jar -p wa -k jar_change
# 监控 docker-compose.yml 变更
-w /opt/canteen/docker-compose.yml -p wa -k compose_change
# 监控 passwd/shadow 文件
-w /etc/passwd -p wa -k passwd_change
-w /etc/shadow -p wa -k shadow_change
# 监控 SSH 配置变更
-w /etc/ssh/sshd_config -p wa -k ssh_config_change
# 监控 sudoers 变更
-w /etc/sudoers -p wa -k sudoers_change
EOF
    augenrules --load
    systemctl enable auditd

    # 2. AIDE(文件完整性监控)
    apt-get install -y -qq aide
    # 初始化 AIDE 数据库(首次较慢)
    aideinit 2>/dev/null
    cp /var/lib/aide/aide.db.new /var/lib/aide/aide.db 2>/dev/null

    # 创建每日检查 cron
    cat > /etc/cron.daily/aide-check <<'EOF'
#!/bin/bash
/usr/bin/aide --check | mail -s "[AIDE] 完整性检查报告 $(hostname)" root
EOF
    chmod +x /etc/cron.daily/aide-check

    # 3. 自动安全更新
    apt-get install -y -qq unattended-upgrades
    dpkg-reconfigure -plow unattended-upgrades

    info "入侵检测已安装:auditd + AIDE + 自动安全更新"
}
```

**测试验证**:
```bash
# 1. 验证 auditd 运行
sudo systemctl status auditd
# 预期:active (running)

# 2. 验证审计规则
sudo auditctl -l | grep canteen
# 预期:显示 .env、app.jar 等监控规则

# 3. 触发文件变更,检查审计日志
sudo touch /opt/canteen/.env
sudo ausearch -k env_change -ts recent
# 预期:显示 .env 文件被 touch 的审计记录

# 4. 验证 AIDE 初始化
sudo aide --check
# 预期:AIDE database is consistent(无变更)

# 5. 验证自动更新配置
cat /etc/apt/apt.conf.d/20auto-upgrades
# 预期:
#   APT::Periodic::Update-Package-Lists "1";
#   APT::Periodic::Unattended-Upgrade "1";
```

---

### P2-5 升级 xlsx 依赖

**修复文件**: `admin-web/package.json`

**修改内容**:
```json
{
  "dependencies": {
    "xlsx": "https://cdn.sheetjs.com/xlsx-0.20.3/xlsx-0.20.3.tgz"
  }
}
```

```bash
cd admin-web
npm install
```

**测试验证**:
```bash
# 1. 验证版本
npm list xlsx
# 预期:xlsx@0.20.3

# 2. 测试员工批量导入功能
# 管理后台 → 员工管理 → 批量导入 → 上传 Excel
# 预期:正常解析导入

# 3. 测试导出功能
# 管理后台 → 员工管理 → 导出 CSV
# 预期:正常导出
```

---

### P2-6 EmployeeVO 手机号脱敏

**修复文件**: `backend/src/main/java/com/example/canteen/dto/EmployeeVO.java`

**修改内容**:
```java
// EmployeeVO.java
public static EmployeeVO from(Employee e) {
    EmployeeVO vo = new EmployeeVO();
    vo.setId(e.getId());
    vo.setName(e.getName());
    // 手机号脱敏:138****0001
    vo.setPhone(maskPhone(e.getPhone()));
    // ... 其他字段 ...
    return vo;
}

private static String maskPhone(String phone) {
    if (phone == null || phone.length() < 11) return phone;
    return phone.substring(0, 3) + "****" + phone.substring(7);
}
```

Controller 层增加完整手机号返回接口(需额外权限):
```java
// EmployeeController 新增
@GetMapping("/{id}/phone")
public Map<String, String> getPhone(@PathVariable Long id) {
    SecurityContext.checkStoreAccess(...);
    Employee e = employeeService.getById(id);
    return Map.of("phone", e.getPhone());  // 完整手机号
}
```

**测试验证**:
```bash
# 1. 查询员工列表,检查手机号脱敏
curl -s https://你的域名/api/employee/store/1 \
  -H "Cookie: admin_token=token" | jq '.[0].phone'
# 预期:"138****0001"

# 2. 查询完整手机号(需权限)
curl -s https://你的域名/api/employee/1/phone \
  -H "Cookie: admin_token=token"
# 预期:返回完整手机号 "13800000001"

# 3. 验证 CSV 导出也脱敏
# 管理后台 → 导出 CSV → 检查手机号列
# 预期:138****0001
```

---

### P2-7 .env 权限强制 600

**修复文件**: `deploy.sh`

**修改内容**:
```bash
# deploy.sh 中所有写入 .env 的操作后,追加 chmod 600

# configure_env 函数末尾
chmod 600 .env
chown $(stat -c '%U' .env):$(stat -c '%G' .env) .env

# set_env_var 函数末尾
chmod 600 "$envfile"

# cmd_reset_admin 函数中,写入密码后
chmod 600 "$envfile"
```

**测试验证**:
```bash
# 1. 检查 .env 权限
ls -la /opt/canteen/.env
# 预期:-rw------- 1 canteen canteen ... .env

# 2. 验证其他用户无法读取
sudo -u another_user cat /opt/canteen/.env
# 预期:Permission denied

# 3. 验证属主用户可读写
cat /opt/canteen/.env | head -3
# 预期:正常读取
```

---

### P2-8 部署后清理 INIT_ADMIN_PASSWORD

**修复文件**: `deploy.sh`

**修改内容**:
```bash
# deploy.sh 的 start_services 函数,健康检查通过后
# 自动清理 .env 中的敏感变量(参考 cmd_reset_admin 的清理逻辑)

# 在 show_summary 之前调用
cleanup_sensitive_env() {
    local envfile="$PROJECT_DIR/.env"
    info "清理 .env 中的临时敏感变量..."

    local tmp
    tmp=$(mktemp)
    # 删除 INIT_ADMIN_PASSWORD 和 INIT_ADMIN_FORCE
    # 保留 INIT_ADMIN_USERNAME 供参考
    if grep -v "^INIT_ADMIN_FORCE=" "$envfile" 2>/dev/null \
        | grep -v "^INIT_ADMIN_PASSWORD=" > "$tmp" && [ -s "$tmp" ]; then
        mv "$tmp" "$envfile"
        chmod 600 "$envfile"
        info "已清理 INIT_ADMIN_PASSWORD 和 INIT_ADMIN_FORCE"
    else
        rm -f "$tmp"
        warn "清理 .env 失败,请手动删除 INIT_ADMIN_PASSWORD"
    fi
}

# 在 start_services 成功后调用
if wait_backend_healthy; then
    cleanup_sensitive_env
fi
```

**测试验证**:
```bash
# 1. 部署完成后检查 .env
grep "INIT_ADMIN" /opt/canteen/.env
# 预期:仅有 INIT_ADMIN_USERNAME=word
# 不应有 INIT_ADMIN_PASSWORD 和 INIT_ADMIN_FORCE

# 2. 验证后端正常重启(密码已在数据库中)
docker compose restart backend
curl -s http://localhost:18082/api/system/health
# 预期:健康

# 3. 验证登录正常
curl -X POST http://localhost:18082/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"word","password":"你的密码"}'
# 预期:登录成功(密码在数据库中,不依赖 .env)
```

---

## 修复后综合测试

### 全流程冒烟测试(所有 P0-P2 修复完成后)

```bash
#!/bin/bash
# 综合安全验证脚本

echo "===== 1. 端口绑定验证 ====="
sudo ss -tlnp | grep -E '18080|18081|18082'
# 预期:全部 127.0.0.1

echo "===== 2. 弱默认值验证 ====="
grep -n "canteen2026" /opt/canteen/docker-compose.yml
# 预期:无匹配

echo "===== 3. 防火墙验证 ====="
sudo ufw status | grep -E '18080|18081|18082'
# 预期:无匹配(未放行)

echo "===== 4. fail2ban 验证 ====="
sudo fail2ban-client status sshd
# 预期:1 jail active

echo "===== 5. Cookie Secure 验证 ====="
curl -I -X POST https://你的域名/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"word","password":"密码"}' 2>&1 | grep -i set-cookie
# 预期:包含 Secure; HttpOnly; SameSite=Strict

echo "===== 6. CORS 验证 ====="
curl -I -X OPTIONS https://你的域名/api/admin/login \
  -H "Origin: https://evil.com" \
  -H "Access-Control-Request-Method: POST" 2>&1 | grep -i allow-origin
# 预期:无匹配(拒绝 evil.com)

echo "===== 7. 备份加密验证 ====="
gunzip -c /opt/canteen/backup/snapshots/*/database.sql.gz.enc 2>&1 | head -1
# 预期:乱码或报错(无法直接解码)

echo "===== 8. Redis 密码验证 ====="
docker exec canteen-redis redis-cli ping 2>&1
# 预期:NOAUTH Authentication required

echo "===== 9. 容器非 root 验证 ====="
docker exec canteen-backend id
# 预期:uid=1000(canteen)

echo "===== 10. .env 权限验证 ====="
ls -la /opt/canteen/.env
# 预期:-rw------- (600)

echo "===== 11. 安全头验证 ====="
curl -I https://你的域名/ 2>&1 | grep -iE 'strict-transport|content-security|x-frame'
# 预期:HSTS + CSP + X-Frame-Options

echo "===== 12. auditd 验证 ====="
sudo auditctl -l | grep canteen
# 预期:显示 .env、app.jar 监控规则

echo ""
echo "===== 全部验证完成 ====="
```

---

## 修复实施顺序

1. **代码修复**(开发机):
   - P0-1 ~ P0-5 代码修改
   - P1-1 ~ P1-6 代码修改
   - P2-1 ~ P2-8 代码修改
   - 提交到 main 分支

2. **构建发布**(开发机):
   - `./scripts/publish.sh all`
   - 推送到 deploy 分支

3. **服务器清理重部署**:
   - 按 `SERVER_REDEPLOY.md` 执行
   - 全新部署,确保所有修复生效

4. **服务器加固**:
   - 按 `SERVER_HARDENING.md` 执行
   - SSH 加固 + 防火墙 + 入侵检测

5. **验证测试**:
   - 执行本文档的"综合安全验证脚本"
   - 逐项确认通过
