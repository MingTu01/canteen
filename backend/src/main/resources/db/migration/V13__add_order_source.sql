-- V13: 订单来源字段
-- 0-正常订餐(默认), 1-未订餐用餐(现场加餐,绕过截止时间和防重复校验)
ALTER TABLE `order` ADD COLUMN order_source TINYINT NOT NULL DEFAULT 0 COMMENT '订单来源: 0-正常订餐, 1-未订餐用餐';
