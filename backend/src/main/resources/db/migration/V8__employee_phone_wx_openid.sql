-- V8: 员工表新增手机号、微信 openid 字段(支持手机号登录和微信绑定)
-- phone 用于手机号登录(H5/小程序),作为员工跨设备稳定标识
-- wx_openid 用于微信绑定后一键登录(小程序预留,本期 H5 暂不使用)

ALTER TABLE employee ADD COLUMN phone VARCHAR(20);
ALTER TABLE employee ADD COLUMN wx_openid VARCHAR(64);

-- 给现有员工补默认手机号(基于卡号生成,避免登录失败):CARD001 -> 13800000001
UPDATE employee SET phone = CONCAT('1380000', LPAD(SUBSTRING(card_no, 5), 4, '0')) WHERE phone IS NULL;

-- 手机号唯一索引(同店内唯一,H2 与 MySQL 均不支持 WHERE 条件的 CREATE INDEX,故全字段索引,允许 NULL)
CREATE INDEX idx_employee_store_phone ON employee (store_id, phone);
