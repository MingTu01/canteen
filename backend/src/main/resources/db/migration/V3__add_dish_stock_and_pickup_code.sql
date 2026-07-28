-- =====================================================
-- V3 升级版本：添加菜品库存和限购字段
-- =====================================================

-- 菜品表增加库存和限购字段
ALTER TABLE dish ADD COLUMN stock INT DEFAULT -1 COMMENT '库存（-1为不限）' AFTER is_new;
ALTER TABLE dish ADD COLUMN max_per_order INT DEFAULT 5 COMMENT '每单最大购买数量' AFTER stock;

-- 订单表增加取餐码字段
ALTER TABLE `order` ADD COLUMN pickup_code VARCHAR(10) COMMENT '取餐码' AFTER status;
