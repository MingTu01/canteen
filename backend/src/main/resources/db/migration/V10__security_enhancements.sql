-- V10: 安全增强 - password_updated_at + token_blacklist + schema_version
-- 配合 JwtAuthenticationFilter 实现密码修改后旧 token 失效、注销 token 黑名单、版本化迁移追踪

-- 1. employee 表新增 password_updated_at:用于 JWT 失效校验(iat < passwordUpdatedAt 则旧 token 失效)
ALTER TABLE employee ADD COLUMN password_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- 2. admin 表新增 password_updated_at
ALTER TABLE admin ADD COLUMN password_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- 3. token_blacklist 表:注销 token 黑名单(按 jti 维度,定时清理过期条目)
CREATE TABLE IF NOT EXISTS token_blacklist (
    token_jti VARCHAR(128) PRIMARY KEY COMMENT 'JWT jti',
    expires_at TIMESTAMP COMMENT 'Token 过期时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '加入黑名单时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JWT Token 黑名单';

CREATE INDEX idx_token_blacklist_expires ON token_blacklist(expires_at);

-- 4. schema_version 表:版本化迁移追踪(SchemaMigrationRunner 使用)
CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(64) PRIMARY KEY COMMENT '版本号',
    name VARCHAR(255) COMMENT '迁移名称',
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '应用时间',
    success BOOLEAN DEFAULT TRUE COMMENT '是否成功',
    error_msg VARCHAR(2000) COMMENT '错误信息'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Schema 版本化迁移历史';

-- 5. sys_config 新增登录限流相关配置 + 系统版本号升级
INSERT INTO sys_config (config_key, config_value, description) VALUES
    ('login_rate_limit_max_fail', '10', '登录失败最大次数,超过则锁定')
    ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

INSERT INTO sys_config (config_key, config_value, description) VALUES
    ('login_rate_limit_lock_minutes', '5', '登录失败锁定分钟数')
    ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

INSERT INTO sys_config (config_key, config_value, description) VALUES
    ('system_version', '1.1.0', '系统版本号')
    ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- 6. 初始化已有用户/管理员的 password_updated_at
UPDATE employee SET password_updated_at = CURRENT_TIMESTAMP WHERE password_updated_at IS NULL;
UPDATE admin SET password_updated_at = CURRENT_TIMESTAMP WHERE password_updated_at IS NULL;
