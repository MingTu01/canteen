-- V7: 扩展系统配置项,支持多Tab分组管理
-- 使用 UPSERT 语义:存在则跳过,不存在则插入
-- MySQL 兼容写法:INSERT IGNORE(主键/唯一键冲突时跳过)

-- 订餐配置
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('order_advance_days', '7', '可提前预订天数');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('order_deadline_time', '15:00', '次日订餐截止时间(前一天 HH:mm,过后不可订次日)');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('cancel_deadline_time', '15:00', '次日取消截止时间(前一天 HH:mm,过后不可取消次日)');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('max_order_quantity', '10', '单次最大订餐数量');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('allow_cross_day_order', 'true', '是否允许跨日订餐');

-- 支付与余额
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('balance_min_warning', '50.00', '余额最低预警值');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('allow_negative_balance', 'false', '是否允许负余额消费');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('recharge_min_amount', '1', '最小充值金额');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('recharge_max_amount', '5000', '最大充值金额');

-- 通知配置
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('notification_default_duration_days', '30', '默认通知有效天数');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('notification_auto_expire', 'true', '通知到期自动下架');

-- 备份配置
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('backup_auto_enabled', 'true', '是否启用定时备份');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('backup_keep_copies', '30', '备份保留份数');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('backup_cron', '0 0 2 * * ?', '定时备份 Cron 表达式');
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES ('backup_keep_days', '30', '备份保留天数(兼容旧配置)');
