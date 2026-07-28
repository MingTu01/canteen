-- V9: store 表新增 security_code 字段,用于终端(X86 设备)绑定食堂
-- 管理员账号密码 + 食堂安全码 双重校验,防止终端越权访问其他门店数据
-- 安全码可由超管在"食堂管理"页生成/重置,重置后旧终端绑定自动失效

ALTER TABLE store ADD COLUMN security_code VARCHAR(32);

-- 给现有食堂生成默认安全码(8 位随机字符串)
-- 使用 CONCAT+SUBSTRING+RAND 拼接,H2 与 MySQL 均支持
UPDATE store SET security_code = SUBSTRING(MD5(CONCAT(RAND(), id)), 1, 8) WHERE security_code IS NULL;
