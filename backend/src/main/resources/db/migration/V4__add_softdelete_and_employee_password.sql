-- =====================================================
-- V4 升级版本:补齐软删除、员工密码、菜品/员工软删除字段
-- =====================================================

-- 菜品表增加软删除字段
ALTER TABLE dish ADD COLUMN is_deleted TINYINT DEFAULT 0 COMMENT '软删除:0=正常,1=已删除';

-- 员工表增加密码字段(BCrypt)和软删除字段
ALTER TABLE employee ADD COLUMN password VARCHAR(255) COMMENT '消费密码(BCrypt)' AFTER card_no;
ALTER TABLE employee ADD COLUMN is_deleted TINYINT DEFAULT 0 COMMENT '软删除:0=正常,1=已删除';

-- 为所有现有员工设置默认密码 123456 (BCrypt 哈希)
UPDATE employee SET password = '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC' WHERE password IS NULL;

-- 菜品库存默认值修正:NULL 表示不限(stock=-1 旧逻辑保留)
UPDATE dish SET stock = 100 WHERE stock = -1;

-- menu_item 唯一约束(防止重复菜品)
-- 注意:如已存在重复数据需先清理,此处假设无重复
ALTER TABLE menu_item ADD UNIQUE KEY uk_menu_dish (menu_id, dish_id);

-- 订单表复合索引(优化 dashboard 查询)
CREATE INDEX idx_order_store_date_status ON `order` (store_id, date, status);

-- order_item 菜品索引(优化销量统计)
CREATE INDEX idx_order_item_dish ON order_item (dish_id);

-- 充值记录创建时间索引(优化报表)
CREATE INDEX idx_recharge_created ON recharge_record (created_at);
