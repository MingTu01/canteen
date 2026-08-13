-- 取消订单取餐码功能,删除 pickup_code 列及相关唯一索引
DROP INDEX IF EXISTS uk_order_store_date_pickup ON `order`;
ALTER TABLE `order` DROP COLUMN IF EXISTS pickup_code;
