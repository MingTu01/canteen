# 服务器安全加固方案

> 目标系统:Ubuntu 26.04 LTS + 1panel 面板 + Docker
> 加固目标:纵深防御,降低被入侵、被爆破、被勒索的风险
> 执行用户:具有 sudo 权限的普通用户(`canteen`),禁止直接使用 root 登录
> 加固级别:生产环境推荐(兼顾安全性与运维便利性)
> 配套文档:`SECURITY_FIX_PLAN.md`(应用层修复)、`SERVER_REDEPLOY.md`(重部署流程)

---

## 0. 加固总览

| 层级 | 加固项 | 优先级 | 风险降幅 |
|------|--------|--------|---------|
| 系统层 | 自动安全更新 / 内核参数加固 | P1 | 关闭已知漏洞 |
| 账户层 | SSH 密钥登录 / 禁用 root / 密码复杂度 | P0 | 杜绝爆破 |
| 防火墙 | UFW 最小化端口开放 | P0 | 减少攻击面 |
| 入侵防护 | Fail2ban 自动封禁 | P0 | 自动拦截爆破 |
| 容器层 | Docker 守护进程加固 / 非 root 运行 | P1 | 隔离提权 |
| 文件层 | 关键文件权限 / AIDE 完整性监控 | P2 | 检测篡改 |
| 审计层 | auditd 操作审计 / 日志集中 | P2 | 事后追溯 |
| 面板层 | 1panel 安全入口 / 双因素 | P1 | 保护管理面板 |
| 网络层 | 内核网络参数加固 / SYN 防护 | P2 | 抗洪水攻击 |

**核心原则**:
- 最小权限:只开放必需端口,只授予必需权限
- 纵深防御:多层防护,单层失效不导致全线崩溃
- 可观测性:所有关键操作有日志,异常行为能告警
- 可回滚:每项加固都提供回滚方法,避免锁死自己

---

## 1. 系统更新与内核加固

### 1.1 启用自动安全更新

```bash
# 安装无人值守升级工具
sudo apt-get update
sudo apt-get install -y unattended-upgrades apt-listchanges

# 启用自动安全更新(交互式配置)
sudo dpkg-reconfigure -plow unattended-upgrades

# 验证配置
cat /etc/apt/apt.conf.d/50unattended-upgrades | grep -A5 "Unattended-Upgrade::Allowed-Origins"
# 预期:包含 "${distro_id}:${distro_codename}-security"
```

**手动配置方式**(非交互):
```bash
sudo tee /etc/apt/apt.conf.d/20auto-upgrades > /dev/null <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Download-Upgradeable-Packages "1";
APT::Periodic::AutocleanInterval "7";
APT::Periodic::Unattended-Upgrade "1";
EOF

# 配置仅自动安装安全更新(不自动升级业务软件,避免兼容性问题)
sudo tee /etc/apt/apt.conf.d/50unattended-upgrades > /dev/null <<'EOF'
Unattended-Upgrade::Allowed-Origins {
    "${distro_id}:${distro_codename}-security";
};
Unattended-Upgrade::Package-Blacklist {
    "docker-ce";
    "docker-ce-cli";
    "containerd.io";
};
Unattended-Upgrade::Automatic-Reboot "false";
Unattended-Upgrade::Automatic-Reboot-Time "04:00";
EOF
```

**验证**:
```bash
# 模拟运行,查看会升级哪些包
sudo unattended-upgrade --dry-run -v
```

### 1.2 内核参数加固(sysctl)

```bash
# 备份原配置
sudo cp /etc/sysctl.conf /etc/sysctl.conf.bak.$(date +%Y%m%d)

# 写入加固配置
sudo tee /etc/sysctl.d/99-hardening.conf > /dev/null <<'EOF'
# ===== 网络加固 =====
# 禁用 IPv6(若不使用,减少攻击面;若使用请注释此行)
# net.ipv6.conf.all.disable_ipv6 = 1
# net.ipv6.conf.default.disable_ipv6 = 1

# 禁用源路由(防止 IP 欺骗)
net.ipv4.conf.all.accept_source_route = 0
net.ipv4.conf.default.accept_source_route = 0

# 禁用 ICMP 重定向(防止中间人攻击)
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.default.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0
net.ipv4.conf.default.send_redirects = 0

# 启用反向路径过滤(防 IP 欺骗)
net.ipv4.conf.all.rp_filter = 1
net.ipv4.conf.default.rp_filter = 1

# SYN 洪水防护
net.ipv4.tcp_syncookies = 1
net.ipv4.tcp_max_syn_backlog = 4096
net.ipv4.tcp_synack_retries = 2
net.ipv4.tcp_syn_retries = 3

# 禁用 IP 转发(非路由器)
net.ipv4.ip_forward = 0

# 增加文件描述符上限(业务需要)
fs.file-max = 655350

# ===== 内核加固 =====
# 禁用 magic SysRq 键(防止物理访问提权)
kernel.sysrq = 0

# 限制 core dump 信息泄露
fs.suid_dumpable = 0

# 启用 Exec-Shield(若内核支持)
kernel.exec-shield = 1

# 限制 ptrace(防止进程被调试注入)
kernel.yama.ptrace_scope = 1

# 隐藏内核指针(防止 KASLR 绕过)
kernel.kptr_restrict = 2

# 禁用 Dmesg 读取(非 root)
kernel.dmesg_restrict = 1

# 性能优化(业务需要)
net.core.somaxconn = 4096
net.ipv4.tcp_max_tw_buckets = 5000
net.ipv4.tcp_tw_reuse = 1
vm.swappiness = 10
EOF

# 应用配置
sudo sysctl --system

# 验证关键参数
sysctl net.ipv4.tcp_syncookies kernel.kptr_restrict kernel.dmesg_restrict
```

**回滚**:
```bash
sudo rm /etc/sysctl.d/99-hardening.conf
sudo sysctl --system
```

### 1.3 限制内核模块加载

```bash
# 禁用不常用的文件系统模块(减少攻击面)
sudo tee /etc/modprobe.d/hardening.conf > /dev/null <<'EOF'
# 禁用不需要的文件系统(若需要请注释)
install cramfs /bin/true
install freevxfs /bin/true
install jffs2 /bin/true
install hfs /bin/true
install hfsplus /bin/true
install squashfs /bin/true
install udf /bin/true

# 禁用不需要的协议
install dccp /bin/true
install sctp /bin/true
install rds /bin/true
install tipc /bin/true

# 禁用 USB 存储(服务器通常不需要,防 USB 恶意设备)
# install usb-storage /bin/true
EOF
```

---

## 2. 账户与 SSH 加固

### 2.1 创建专用运维用户(若尚未创建)

```bash
# 创建 canteen 用户(若已存在跳过)
sudo adduser canteen

# 加入 sudo 组
sudo usermod -aG sudo canteen

# 加入 docker 组(免 sudo 操作 Docker)
sudo usermod -aG docker canteen

# 验证
groups canteen
# 预期:canteen : canteen sudo docker
```

### 2.2 配置 SSH 密钥登录

```bash
# 在你的本地电脑(非服务器)生成密钥对(ED25519,更安全更快)
ssh-keygen -t ed25519 -C "canteen-server-2026" -f ~/.ssh/canteen_ed25519

# 上传公钥到服务器
ssh-copy-id -i ~/.ssh/canteen_ed25519.pub canteen@服务器IP

# 测试密钥登录(应免密登录)
ssh -i ~/.ssh/canteen_ed25519 canteen@服务器IP
```

### 2.3 加固 SSH 配置

```bash
# 备份原配置
sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.$(date +%Y%m%d)

# 写入加固配置
sudo tee /etc/ssh/sshd_config.d/99-hardening.conf > /dev/null <<'EOF'
# ===== 基础加固 =====
# 监听端口(建议改为非 22,减少自动化扫描;1panel 不占用高位端口)
Port 22022

# 仅监听 IPv4(若不使用 IPv6)
AddressFamily inet

# ===== 认证加固 =====
# 禁用 root 登录(必须用普通用户 su/sudo 提权)
PermitRootLogin no

# 禁用密码登录(仅密钥登录)
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitEmptyPasswords no

# 启用密钥登录
PubkeyAuthentication yes

# 限制可登录用户(白名单)
AllowUsers canteen

# ===== 会话加固 =====
# 登录超时(300 秒无操作自动断开)
ClientAliveInterval 300
ClientAliveCountMax 0

# 限制最大认证尝试次数
MaxAuthTries 3

# 登录宽限时间(60 秒内必须完成认证)
LoginGraceTime 60

# ===== 转发限制 =====
# 禁用 X11 转发(服务器无需图形界面)
X11Forwarding no

# 限制端口转发(按需开启,生产环境建议关闭)
AllowTcpForwarding no
AllowAgentForwarding no
PermitTunnel no

# ===== 信息隐藏 =====
# 不显示登录 Banner
Banner none

# 禁用 lastlog(减少信息泄露)
PrintLastLog no

# 日志级别
LogLevel VERBOSE

# ===== 算法加固(禁用弱算法)=====
KexAlgorithms curve25519-sha256,curve25519-sha256@libssh.org,diffie-hellman-group16-sha512
Ciphers chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com
MACs hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com
EOF

# 测试配置语法(重要!避免锁死自己)
sudo sshd -t && echo "配置语法正确" || echo "配置语法错误,请检查!"

# ⚠️ 重要:保持当前 SSH 会话不要断开,新开一个终端测试新配置
sudo systemctl restart sshd

# 新开终端测试(使用新端口 + 密钥)
ssh -i ~/.ssh/canteen_ed25519 -p 22022 canteen@服务器IP
# 若能登录,加固成功;若不能,回滚配置
```

**回滚**(若新配置导致无法登录):
```bash
# 在原会话中执行
sudo rm /etc/ssh/sshd_config.d/99-hardening.conf
sudo systemctl restart sshd
```

### 2.4 配置 sudo 审计与超时

```bash
# sudo 操作记录到日志(便于追溯)
sudo tee /etc/sudoers.d/audit > /dev/null <<'EOF'
# sudo 操作记录到 syslog
Defaults syslog=authpriv

# sudo 会话超时(15 分钟无操作需重新输入密码)
Defaults timestamp_timeout=15

# 要求 tty(防止后台 sudo 调用)
Defaults requiretty

# 详细日志
Defaults log_input, log_output
EOF

sudo visudo -c  # 校验语法
```

### 2.5 配置密码复杂度策略

```bash
# 安装 PAM 密码质量模块
sudo apt-get install -y libpam-pwquality

# 配置密码复杂度
sudo tee /etc/security/pwquality.conf > /dev/null <<'EOF'
# 最小密码长度
minlen = 12
# 至少包含 1 个数字
dcredit = -1
# 至少包含 1 个大写字母
ucredit = -1
# 至少包含 1 个小写字母
lcredit = -1
# 至少包含 1 个特殊字符
ocredit = -1
# 新密码与旧密码至少 3 个字符不同
difok = 3
# 拒绝字典词
dictcheck = 1
# 拒绝回文密码
palindrome = 1
# 最大连续相同字符
maxrepeat = 3
# 最大连续同类字符
maxclassrepeat = 4
EOF

# 配置密码过期策略(90 天强制更换)
sudo tee /etc/login.defs.d/99-hardening > /dev/null <<'EOF' 2>/dev/null || sudo tee -a /etc/login.defs > /dev/null <<'EOF'
PASS_MAX_DAYS 90
PASS_MIN_DAYS 1
PASS_WARN_AGE 7
EOF

# 对现有用户应用策略
sudo chage --maxdays 90 --mindays 1 --warndays 7 canteen
```

### 2.6 禁用不必要的系统账户

```bash
# 查看可登录的系统账户(预期只有 root 和 canteen)
sudo awk -F: '($3<1000) && ($7!="/usr/sbin/nologin" && $7!="/bin/false") {print}' /etc/passwd

# 将系统账户的 shell 改为 nologin(不影响系统运行)
for user in news uucp irc games gnats; do
    sudo usermod -s /usr/sbin/nologin "$user" 2>/dev/null || true
done
```

---

## 3. 防火墙配置(UFW)

### 3.1 安装并启用 UFW

```bash
sudo apt-get install -y ufw

# ⚠️ 先放行 SSH(新端口),否则启用防火墙后会锁死自己!
sudo ufw allow 22022/tcp comment 'SSH'

# 放行 1panel 面板端口(默认随机端口,在 1panel 安装时设置)
# 查看你的 1panel 端口:
sudo 1pctl user-info 2>/dev/null | grep -i port
# 假设为 9999,放行:
sudo ufw allow 9999/tcp comment '1panel'

# 放行业务端口(1panel 反代入口,外网访问)
sudo ufw allow 9999/tcp comment 'canteen-https'
# 若有独立 HTTPS 端口
# sudo ufw allow 443/tcp comment 'https'

# 放行 Docker 内部通信(1panel 与 Docker 集成)
sudo ufw allow in on docker0 comment 'docker-internal'

# 启用防火墙
sudo ufw enable

# 设置默认策略(拒绝所有入站,允许所有出站)
sudo ufw default deny incoming
sudo ufw default allow outgoing

# 查看状态
sudo ufw status verbose
```

### 3.2 配置 Docker 与 UFW 兼容

> Docker 默认会绕过 UFW 直接开放端口,这是已知问题。

```bash
# 禁用 Docker 的 iptables 直接操作,改由 UFW 统一管控
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "iptables": false,
  "userland-proxy": false,
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "5"
  },
  "no-new-privileges": true,
  "live-restore": true,
  "userns-remap": "default"
}
EOF

# 重启 Docker
sudo systemctl restart docker

# 确认容器仍能正常启动(canteen 服务端口已绑定 127.0.0.1,不暴露)
cd /opt/canteen
docker compose ps
```

**注意事项**:
- 设置 `"iptables": false` 后,Docker 容器访问外网需手动配置 NAT
- 若容器无法访问外网,改用 `"iptables": true` 但确保所有业务端口绑定 `127.0.0.1`(已在 SECURITY_FIX_PLAN.md P0-1 修复)
- **推荐方案**:保持 `"iptables": true`,但确保 `docker-compose.yml` 中所有业务端口都绑定 `127.0.0.1`(已修复)

### 3.3 配置 UFW 限速(防端口扫描)

```bash
# 对 SSH 端口限速(每 IP 每 30 秒最多 6 次连接)
sudo ufw limit 22022/tcp

# 查看 UFW 规则
sudo ufw status numbered
```

---

## 4. Fail2ban 入侵防护

### 4.1 安装 Fail2ban

```bash
sudo apt-get install -y fail2ban

# 创建本地配置(不修改原文件,便于升级)
sudo tee /etc/fail2ban/jail.local > /dev/null <<'EOF'
[DEFAULT]
# 封禁时间(1 小时)
bantime = 3600
# 检测时间窗口(10 分钟)
findtime = 600
# 最大失败次数
maxretry = 3
# 封禁动作(使用 UFW)
banaction = ufw
# 忽略本地地址
ignoreip = 127.0.0.1/8 ::1 192.168.0.0/16 172.16.0.0/12 10.0.0.0/8

# ===== SSH 防护 =====
[sshd]
enabled = true
port = 22022
filter = sshd
logpath = /var/log/auth.log
maxretry = 3
bantime = 3600
findtime = 600

# ===== 1panel 防护 =====
[1panel]
enabled = true
port = 9999
filter = 1panel-auth
logpath = /opt/1panel/log/1Panel.log
maxretry = 5
bantime = 7200
findtime = 600
EOF
```

### 4.2 创建 1panel 日志过滤器

```bash
sudo tee /etc/fail2ban/filter.d/1panel-auth.conf > /dev/null <<'EOF'
[Definition]
failregex = ^.*"client_ip":"<HOST>".*"status":401.*$
            ^.*"client_ip":"<HOST>".*"status":403.*$
ignoreregex =
EOF

# 启动并设置开机自启
sudo systemctl enable fail2ban
sudo systemctl restart fail2ban

# 验证
sudo fail2ban-client status
sudo fail2ban-client status sshd
```

### 4.3 配置封禁告警(可选,需邮件服务)

```bash
# 在 jail.local 的 [DEFAULT] 段添加
# destemail = admin@your-domain.com
# sender = fail2ban@your-domain.com
# mta = sendmail
# action = %(action_mwl)s
```

### 4.4 验证 Fail2ban 工作

```bash
# 查看当前封禁列表
sudo fail2ban-client status sshd

# 手动解封某 IP
sudo fail2ban-client set sshd unbanip 1.2.3.4

# 查看封禁日志
sudo tail -f /var/log/fail2ban.log
```

---

## 5. Docker 守护进程加固

### 5.1 Docker 守护进程配置

```bash
# 备份原配置
sudo cp /etc/docker/daemon.json /etc/docker/daemon.json.bak 2>/dev/null || true

# 完整加固配置
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "5"
  },
  "live-restore": true,
  "userland-proxy": false,
  "no-new-privileges": true,
  "icc": false,
  "disable-legacy-registry": true,
  "experimental": false,
  "storage-driver": "overlay2",
  "default-ulimits": {
    "nofile": {
      "Hard": 65535,
      "Soft": 65535
    },
    "nproc": {
      "Hard": 4096,
      "Soft": 2048
    }
  }
}
EOF

# 重启 Docker
sudo systemctl restart docker

# 验证
docker info | grep -E "Logging Driver|Live Restore|Userland Proxy"
```

### 5.2 限制 Docker API 访问

```bash
# 确认 Docker socket 权限(仅 root 与 docker 组可访问)
sudo ls -l /var/run/docker.sock
# 预期:srwxrwxr-x 1 root docker

# 禁用远程 API(默认已禁用,确认无 -H tcp://0.0.0.0)
sudo systemctl cat docker | grep -i exec
```

### 5.3 定期清理 Docker 资源(防磁盘占满)

```bash
# 创建清理 cron 任务(每周日凌晨 3 点清理)
sudo tee /etc/cron.d/docker-cleanup > /dev/null <<'EOF'
# 每周清理悬空镜像、停止的容器、未使用的网络
0 3 * * 0 root docker system prune -f --filter "until=168h" > /dev/null 2>&1
# 每天清理超过 7 天的构建缓存
0 4 * * * root docker builder prune -f --filter "until=168h" > /dev/null 2>&1
EOF
```

---

## 6. 文件系统加固

### 6.1 关键文件权限

```bash
# /etc/passwd 所有用户可读,仅 root 可写
sudo chmod 644 /etc/passwd
# /etc/shadow 仅 root 可读
sudo chmod 640 /etc/shadow
sudo chown root:shadow /etc/shadow
# /etc/group 所有用户可读,仅 root 可写
sudo chmod 644 /etc/group
# /etc/gshadow 仅 root 可读
sudo chmod 640 /etc/gshadow
sudo chown root:shadow /etc/gshadow

# SSH 配置目录
sudo chmod 700 ~/.ssh
sudo chmod 600 ~/.ssh/authorized_keys
sudo chown -R canteen:canteen ~/.ssh

# 项目目录
sudo chmod 600 /opt/canteen/.env
sudo chown canteen:canteen /opt/canteen/.env

# crontab
sudo chmod 600 /etc/crontab
sudo chmod 700 /etc/cron.d
sudo chmod 700 /etc/cron.daily
sudo chmod 700 /etc/cron.hourly
sudo chmod 700 /etc/cron.weekly
sudo chmod 700 /etc/cron.monthly
```

### 6.2 挂载选项加固

```bash
# 查看 /tmp 的挂载选项
mount | grep -E "on /tmp "

# 推荐 /tmp 设置 noexec,nosuid,nodev(防止临时目录执行恶意程序)
# 编辑 /etc/fstab,添加:
# tmpfs /tmp tmpfs defaults,noexec,nosuid,nodev 0 0

# /var/tmp 同理
# tmpfs /var/tmp tmpfs defaults,noexec,nosuid,nodev 0 0

# /dev/shm
# tmpfs /dev/shm tmpfs defaults,noexec,nosuid,nodev 0 0

# 应用挂载选项(无需重启)
sudo mount -o remount,noexec,nosuid,nodev /tmp 2>/dev/null || true
sudo mount -o remount,noexec,nosuid,nodev /dev/shm 2>/dev/null || true
```

### 6.3 禁用不必要的 SUID/SGID 文件

```bash
# 查找所有 SUID 文件
sudo find / -xdev -type f -perm -4000 -exec ls -l {} \; 2>/dev/null

# 禁用不常用的 SUID 文件(根据实际需求调整)
# 常见可禁用:
for f in /usr/bin/chsh /usr/bin/chfn /usr/bin/gpasswd /usr/bin/newgrp /usr/bin/pkexec /usr/sbin/pppd; do
    if [[ -f "$f" ]]; then
        sudo chmod u-s "$f"
        echo "已禁用 SUID: $f"
    fi
done

# ⚠️ 不要禁用 sudo / su / passwd / mount / umount,否则系统无法正常使用
```

---

## 7. AIDE 文件完整性监控

### 7.1 安装与初始化

```bash
sudo apt-get install -y aide

# 初始化数据库(首次运行较慢,5-15 分钟)
sudo aideinit

# 将初始化数据库设为基准
sudo cp /var/lib/aide/aide.db.new /var/lib/aide/aide.db

# 测试完整性检查
sudo aide --check
```

### 7.2 配置定期检查

```bash
# 每日凌晨 2 点检查文件完整性,结果发送邮件
sudo tee /etc/cron.daily/aide-check > /dev/null <<'EOF'
#!/bin/bash
REPORT=$(sudo aide --check 2>&1)
if echo "$REPORT" | grep -qE "changed:|added:|removed:"; then
    echo "$REPORT" | mail -s "[AIDE] 文件完整性异常 - $(hostname)" root 2>/dev/null
    logger -t aide "检测到文件完整性变化"
fi
EOF

sudo chmod +x /etc/cron.daily/aide-check
```

### 7.3 更新基准(合法变更后)

```bash
# 当你合法修改了系统文件(如升级软件),需要更新基准
sudo aideinit
sudo cp /var/lib/aide/aide.db.new /var/lib/aide/aide.db
```

---

## 8. auditd 操作审计

### 8.1 安装与配置

```bash
sudo apt-get install -y auditd audispd-plugins

# 配置审计规则
sudo tee /etc/audit/rules.d/hardening.rules > /dev/null <<'EOF'
# 删除现有规则
-D
# 缓冲区
-b 8192
# 失败模式:1=记录,2=panic
-f 1

# ===== 关键文件监控 =====
# 监控 /etc/passwd 修改
-w /etc/passwd -p wa -k identity
-w /etc/shadow -p wa -k identity
-w /etc/group -p wa -k identity
-w /etc/gshadow -p wa -k identity

# 监控 SSH 配置修改
-w /etc/ssh/sshd_config -p wa -k ssh_config

# 监控 sudoers 修改
-w /etc/sudoers -p wa -k sudoers
-w /etc/sudoers.d/ -p wa -k sudoers

# 监控 cron 修改
-w /etc/crontab -p wa -k cron
-w /etc/cron.d/ -p wa -k cron
-w /etc/cron.daily/ -p wa -k cron

# 监控 Docker 配置
-w /etc/docker/daemon.json -p wa -k docker
-w /lib/systemd/system/docker.service -p wa -k docker

# 监控 .env 文件(敏感配置)
-w /opt/canteen/.env -p wa -k canteen-env

# ===== 系统调用监控 =====
# 监控权限提升(非 sudo 提权的 su 调用)
-a always,exit -F arch=b64 -S setuid -S setgid -k privilege

# 监控模块加载(防 rootkit)
-w /sbin/insmod -p x -k modules
-w /sbin/rmmod -p x -k modules
-w /sbin/modprobe -p x -k modules

# 锁定规则(防止篡改,需重启才能修改)
# -e 2
EOF

# 重启审计服务
sudo systemctl enable auditd
sudo systemctl restart auditd

# 验证规则已加载
sudo auditctl -l
```

### 8.2 查看审计日志

```bash
# 查看所有审计事件
sudo ausearch -m ALL --start today

# 查看 SSH 配置修改记录
sudo ausearch -k ssh_config

# 查看 .env 文件修改记录
sudo ausearch -k canteen-env

# 查看权限提升记录
sudo ausearch -k privilege

# 生成审计报告
sudo aureport --summary
sudo aureport --file
```

### 8.3 日志轮转配置

```bash
# 配置审计日志大小与轮转
sudo tee /etc/audit/auditd.conf > /dev/null <<'EOF'
log_file = /var/log/audit/audit.log
log_format = RAW
log_group = adm
priority_boost = 4
flush = INCREMENTAL_ASYNC
freq = 50
max_log_file = 100
num_logs = 5
name_format = NONE
max_log_file_action = ROTATE
space_left = 200
space_left_action = EMAIL
action_mail_acct = root
admin_space_left = 50
admin_space_left_action = HALT
disk_full_action = HALT
disk_error_action = HALT
EOF

sudo systemctl restart auditd
```

---

## 9. 1panel 面板加固

### 9.1 修改面板安全入口

```bash
# 查看当前面板信息
sudo 1pctl user-info

# 修改安全入口(增加 URL 路径,防直接访问)
sudo 1pctl update entrance "/your-secret-path-2026"
# 访问时必须带此路径:https://域名:9999/your-secret-path-2026

# 修改面板端口(避免默认端口被扫描)
sudo 1pctl update port 新端口号
# 记得在 UFW 放行新端口,关闭旧端口:
# sudo ufw allow 新端口/tcp
# sudo ufw delete allow 旧端口/tcp
```

### 9.2 启用面板双因素认证

1. 登录 1panel 面板
2. 进入「面板设置」→「安全」
3. 启用「两步验证」(Google Authenticator / 微信小程序)
4. 扫描二维码绑定,保存恢复码

### 9.3 面板密码加固

```bash
# 修改面板登录密码(强密码,至少 12 位)
sudo 1pctl update password
# 按提示输入新密码
```

### 9.4 限制面板访问 IP(可选,最高安全性)

```bash
# 若你使用固定 IP,可在 UFW 中限制 1panel 端口仅允许你的 IP
# 替换为你的公网 IP
YOUR_IP="1.2.3.4"

# 删除原规则
sudo ufw delete allow 9999/tcp

# 仅允许指定 IP 访问
sudo ufw allow from $YOUR_IP to any port 9999 proto tcp comment '1panel-restricted'
```

### 9.5 1panel 数据库加固

```bash
# 1panel 默认使用 SQLite,确认文件权限
sudo ls -l /opt/1panel/db/
sudo chmod 600 /opt/1panel/db/1Panel.db
sudo chown root:root /opt/1panel/db/1Panel.db
```

### 9.6 关闭 1panel 测试端口

```bash
# 1panel 安装时会开测试端口,确认已关闭
sudo ss -tlnp | grep 1panel
# 仅应监听 9999(或你设置的端口),不应有其他端口
```

---

## 10. 日志与监控

### 10.1 配置日志轮转

```bash
# 确认 logrotate 已安装
sudo apt-get install -y logrotate

# 配置 canteen 应用日志轮转
sudo tee /etc/logrotate.d/canteen > /dev/null <<'EOF'
/var/lib/docker/containers/*/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    size 50M
}
EOF

# 测试轮转配置
sudo logrotate -d /etc/logrotate.d/canteen
```

### 10.2 配置系统日志远程告警(可选)

```bash
# 若有日志服务器,配置 rsyslog 转发
sudo tee /etc/rsyslog.d/99-forward.conf > /dev/null <<'EOF'
# 转发 auth 日志到远程日志服务器
*.* @@log-server.your-domain.com:514
EOF

sudo systemctl restart rsyslog
```

### 10.3 磁盘空间监控

```bash
# 安装监控工具
sudo apt-get install -y monitorix 2>/dev/null || true

# 创建磁盘告警脚本
sudo tee /usr/local/bin/disk-alert.py > /dev/null <<'EOF'
#!/usr/bin/env python3
"""磁盘空间告警:超过 85% 时记录到 syslog"""
import shutil
import syslog
import sys

THRESHOLD = 85

try:
    usage = shutil.disk_usage("/")
    percent = (usage.used / usage.total) * 100
    if percent > THRESHOLD:
        msg = f"磁盘告警:根分区使用率 {percent:.1f}% 超过阈值 {THRESHOLD}%"
        syslog.syslog(syslog.LOG_WARNING, msg)
        print(msg, file=sys.stderr)
except Exception as e:
    syslog.syslog(syslog.LOG_ERR, f"磁盘告警脚本异常: {e}")
EOF

sudo chmod +x /usr/local/bin/disk-alert.py

# 每小时检查一次
sudo tee /etc/cron.hourly/disk-alert > /dev/null <<'EOF'
#!/bin/bash
/usr/local/bin/disk-alert.py
EOF
sudo chmod +x /etc/cron.hourly/disk-alert
```

### 10.4 异常登录监控

```bash
# 创建登录告警脚本(每次登录时触发)
sudo tee /etc/profile.d/login-alert.sh > /dev/null <<'EOF'
# 登录时记录日志(可用于异常登录检测)
if [ -n "$SSH_CONNECTION" ]; then
    logger -t login "用户 $USER 从 $SSH_CONNECTION 登录 $(hostname)"
fi
EOF
```

---

## 11. 网络加固

### 11.1 TCP Wrapper(辅助访问控制)

```bash
# 限制 SSH 仅允许特定 IP(白名单,作为 UFW 的补充)
sudo tee /etc/hosts.allow > /dev/null <<'EOF'
# 允许所有内网
sshd: 192.168.0.0/16 172.16.0.0/12 10.0.0.0/8
# 允许特定公网 IP(替换为你的 IP)
sshd: 1.2.3.4
EOF

sudo tee /etc/hosts.deny > /dev/null <<'EOF'
# 拒绝所有其他 SSH 连接
sshd: ALL
EOF
```

### 11.2 网络时间同步(防重放攻击)

```bash
# 确保时间同步准确(影响 JWT 验证、日志时间戳)
sudo apt-get install -y chrony

# 配置国内 NTP 源
sudo tee /etc/chrony/chrony.conf > /dev/null <<'EOF'
server ntp.aliyun.com iburst
server ntp.tencent.com iburst
server cn.pool.ntp.org iburst

driftfile /var/lib/chrony/chrony.drift
makestep 1.0 3
rtcsync
allow 127.0.0.1
EOF

sudo systemctl enable chrony
sudo systemctl restart chrony

# 验证时间同步
chronyc tracking
```

---

## 12. 备份与灾难恢复

### 12.1 自动化备份策略

```bash
# 参见 SERVER_REDEPLOY.md 第 2 节的备份方法
# 配置每日自动备份 cron

sudo tee /etc/cron.d/canteen-backup > /dev/null <<'EOF'
# 每日凌晨 1 点备份数据库
0 1 * * * canteen /opt/canteen/scripts/backup.sh >> /var/log/canteen-backup.log 2>&1
# 每周日凌晨 2 点备份上传文件
0 2 * * 0 canteen /opt/canteen/scripts/backup-uploads.sh >> /var/log/canteen-backup.log 2>&1
# 每月 1 日清理 30 天前的备份
0 3 1 * * canteen find /opt/canteen-backup-* -type d -mtime +30 -exec rm -rf {} \; 2>/dev/null
EOF
```

### 12.2 备份加密(已在 SECURITY_FIX_PLAN.md 配置)

```bash
# 确认备份加密密钥已安全保存(密码管理器)
# 若密钥丢失,所有加密备份将无法恢复!

# 测试备份恢复(每月执行一次)
# 1. 创建测试目录
# 2. 解密备份
# 3. 恢复到测试环境
# 4. 验证数据完整性
# 5. 清理测试环境
```

### 12.3 异地备份(推荐)

```bash
# 将备份同步到异地(对象存储 / 另一台服务器)
# 示例:使用 rclone 同步到对象存储
sudo apt-get install -y rclone
rclone config  # 配置远程存储

# 每周同步备份到异地
sudo tee /etc/cron.d/canteen-offsite-backup > /dev/null <<'EOF'
0 4 * * 0 canteen rclone sync /opt/canteen-backup-* remote:canteen-backup/ --transfers 4 >> /var/log/rclone.log 2>&1
EOF
```

---

## 13. 加固验证清单

执行完所有加固后,逐项验证:

### 13.1 系统层验证

```bash
# 检查自动更新状态
sudo unattended-upgrade --dry-run -v | head -20

# 检查内核参数
sysctl net.ipv4.tcp_syncookies kernel.kptr_restrict kernel.dmesg_restrict kernel.yama.ptrace_scope
# 预期:全部为 1 或 2
```

### 13.2 SSH 验证

```bash
# 验证 SSH 配置
sudo sshd -T | grep -E "permitrootlogin|passwordauthentication|port|maxauthtries"
# 预期:
# port 22022
# permitrootlogin no
# passwordauthentication no
# maxauthtries 3

# 验证 root 无法直接登录(应失败)
ssh -p 22022 root@localhost
# 预期:Permission denied

# 验证密码登录已禁用(应失败)
ssh -p 22022 canteen@localhost
# 预期:Permission denied (publickey)
```

### 13.3 防火墙验证

```bash
# 查看 UFW 状态
sudo ufw status verbose
# 预期:Status: active,仅放行 22022 与 9999

# 从外部扫描端口(应只看到 22022 与 9999)
nmap -p- 服务器IP
```

### 13.4 Fail2ban 验证

```bash
# 查看 Fail2ban 状态
sudo fail2ban-client status
# 预期:sshd 与 1panel jail 已启用

# 模拟失败登录(在另一台机器)
# ssh -p 22022 wronguser@服务器IP
# 连续失败 3 次后,该 IP 应被封禁 1 小时
```

### 13.5 Docker 验证

```bash
# 验证 Docker 配置
docker info | grep -E "Logging Driver|Live Restore|Userland Proxy"
# 预期:
# Logging Driver: json-file
# Live Restore Enabled: true
# Userland Proxy: false

# 验证容器端口绑定
sudo ss -tlnp | grep -E '18080|18081|18082'
# 预期:全部 127.0.0.1:xxxxx
```

### 13.6 文件完整性验证

```bash
# 运行 AIDE 检查
sudo aide --check | tail -20
# 预期:无变化(首次检查后)

# 查看审计规则
sudo auditctl -l | wc -l
# 预期:至少 15 条规则
```

### 13.7 1panel 验证

```bash
# 验证面板安全入口
sudo 1pctl user-info | grep -i entrance
# 预期:显示自定义安全入口路径

# 验证面板端口
sudo ss -tlnp | grep 1panel
# 预期:仅监听一个端口(你设置的端口)
```

---

## 14. 定期维护任务

### 14.1 日常(自动)

| 任务 | 频率 | 方式 |
|------|------|------|
| 安全更新 | 每日 | unattended-upgrades |
| 系统日志轮转 | 每日 | logrotate |
| 磁盘空间检查 | 每小时 | cron + disk-alert.py |
| 登录告警 | 每次登录 | /etc/profile.d |
| 数据库备份 | 每日 | cron + backup.sh |

### 14.2 周度(自动)

| 任务 | 频率 | 方式 |
|------|------|------|
| 上传文件备份 | 每周日 | cron + backup-uploads.sh |
| 异地备份同步 | 每周日 | cron + rclone |
| Docker 资源清理 | 每周日 | cron + docker prune |
| AIDE 完整性检查 | 每日 | cron + aide-check |

### 14.3 月度(手动)

| 任务 | 说明 |
|------|------|
| 审查 fail2ban 封禁日志 | 检查是否有针对性攻击 |
| 审查 auditd 日志 | 检查异常权限提升 |
| 测试备份恢复 | 验证备份可用性 |
| 更新 AIDE 基准 | 合法变更后更新 |
| 审查用户权限 | 清理离职员工账户 |
| 检查 SSL 证书有效期 | 提前 30 天续期 |

---

## 15. 应急响应

### 15.1 怀疑被入侵时的检查

```bash
# 1. 检查异常登录
last -20
lastb -20  # 失败的登录尝试

# 2. 检查当前登录用户
w
who

# 3. 检查异常进程
ps auxf | grep -vE "root|canteen|mysql|redis|nginx|docker" | head -20

# 4. 检查异常网络连接
sudo ss -tunlp
sudo netstat -tunlp

# 5. 检查异常定时任务
sudo crontab -l
for user in $(cut -d: -f1 /etc/passwd); do
    sudo crontab -l -u "$user" 2>/dev/null | grep -v "^#" | grep -v "^$" && echo "  ↑ 用户: $user"
done
ls -l /etc/cron.*

# 6. 检查异常文件(最近 24 小时修改的 SUID 文件)
sudo find / -xdev -type f -perm -4000 -mtime -1 -exec ls -l {} \; 2>/dev/null

# 7. 检查 SSH authorized_keys 是否被篡改
sudo cat /root/.ssh/authorized_keys 2>/dev/null
cat ~/.ssh/authorized_keys

# 8. 检查 AIDE 报告
sudo aide --check

# 9. 检查 auditd 日志
sudo ausearch -m ANOM_ABEND --start today
sudo ausearch -k privilege --start today
```

### 15.2 被入侵后的处置

```bash
# 1. 立即断网(保留内存证据)
sudo ip link set eth0 down  # 或 ifdown eth0

# 2. 保留现场,不要重启(内存中的恶意进程会消失)

# 3. 备份当前系统状态(取证)
sudo dd if=/dev/sda of=/mnt/forensic/disk-image-$(date +%Y%m%d).img bs=4M status=progress

# 4. 从已知干净的备份恢复
# 参见 SERVER_REDEPLOY.md 第 11 节回滚方案

# 5. 修改所有密码
# - root 密码
# - canteen 用户密码
# - MySQL root 密码
# - Redis 密码
# - JWT 密钥
# - 1panel 面板密码
# - 管理员账号密码

# 6. 重新生成所有 SSH 密钥
ssh-keygen -t ed25519 -f ~/.ssh/canteen_ed25519 -N "" -y

# 7. 审查所有用户账户,删除未知账户
sudo cat /etc/passwd | grep -E "sh$|bash$"

# 8. 审查所有 cron 任务,删除未知任务

# 9. 重装系统(最彻底的方案,若怀疑 rootkit)
```

### 15.3 联系方式

将以下信息填入,应急时快速联系:

```
服务器托管商:_______________  电话:_______________
域名注册商:_______________    电话:_______________
1panel 官方支持:https://1panel.cn/docs
备份存储位置:_______________
异地备份位置:_______________
```

---

## 16. 加固完成检查清单

逐项确认:

- [ ] 自动安全更新已启用(`unattended-upgrades`)
- [ ] 内核参数已加固(`sysctl -a | grep hardening`)
- [ ] SSH 端口已修改(非 22)
- [ ] SSH 禁用 root 登录
- [ ] SSH 禁用密码登录(仅密钥)
- [ ] SSH 限制可登录用户(AllowUsers)
- [ ] 密码复杂度策略已配置(libpam-pwquality)
- [ ] UFW 防火墙已启用,仅放行必需端口
- [ ] UFW 对 SSH 端口限速
- [ ] Fail2ban 已启用,保护 SSH 与 1panel
- [ ] Docker 守护进程已加固(/etc/docker/daemon.json)
- [ ] Docker 日志大小已限制
- [ ] 关键文件权限已设置(.env 600,shadow 640)
- [ ] /tmp 设置 noexec,nosuid,nodev
- [ ] AIDE 已安装并初始化基准
- [ ] auditd 已配置审计规则
- [ ] 1panel 安全入口已修改
- [ ] 1panel 双因素认证已启用
- [ ] 1panel 面板密码已加强
- [ ] 时间同步已配置(chrony)
- [ ] 日志轮转已配置
- [ ] 备份策略已配置(每日数据库,每周文件)
- [ ] 异地备份已配置(可选)
- [ ] 加固验证清单全部通过(第 13 节)
