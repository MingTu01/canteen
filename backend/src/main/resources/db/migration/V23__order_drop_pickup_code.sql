-- 取消订单取餐码功能,删除 pickup_code 列及相关唯一索引
-- 注意:MySQL 8.0 不支持 DROP INDEX/COLUMN IF EXISTS(MariaDB 扩展语法),
-- 用条件 SQL 兼容索引/列已不存在的情况(如之前失败迁移已部分执行)

-- 删除唯一索引 uk_order_store_date_pickup(由 V18 创建)
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'order' AND index_name = 'uk_order_store_date_pickup');
SET @sql = IF(@idx_exists > 0, 'ALTER TABLE `order` DROP INDEX uk_order_store_date_pickup', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 删除 pickup_code 列(由 V3 创建)
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'order' AND column_name = 'pickup_code');
SET @sql = IF(@col_exists > 0, 'ALTER TABLE `order` DROP COLUMN pickup_code', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
