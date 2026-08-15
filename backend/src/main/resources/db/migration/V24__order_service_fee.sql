-- 订单表新增未订餐用餐手续费字段(包含在 total_amount 中,正常订餐为 0)
ALTER TABLE `order` ADD COLUMN service_fee DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '未订餐用餐手续费';
