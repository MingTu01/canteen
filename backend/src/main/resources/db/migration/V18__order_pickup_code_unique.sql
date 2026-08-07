-- V18: 取餐码唯一性兜底 - (store_id, date, pickup_code) 唯一索引
-- 背景:取餐码为 6 位随机数,原实现生成时不查重、核销查询也不带门店/日期过滤,
--   随数据量增长会出现碰撞,导致无法核销或错核销他人订单。
-- 修复:生成端在「同店+当天」范围查重重试(OrderService.generatePickupCode),
--   核销查询收口到「同店+当天」(selectByStoreDatePickupCode),
--   并在数据库层建立唯一索引兜底,从根上杜绝碰撞。
-- 幂等性:通过 information_schema.statistics 检查索引是否已存在,可重复执行。
-- (MySQL 8 不支持 CREATE INDEX IF NOT EXISTS)

-- 1. 存量数据去重:同店同日同码保留最早一单,其余置 NULL(历史已完成/已取消订单的码已无业务意义)
--    MySQL 唯一索引允许多个 NULL,置 NULL 不影响历史数据查询
UPDATE `order` o
JOIN (
    SELECT store_id, date, pickup_code, MIN(id) AS keep_id
    FROM `order`
    WHERE pickup_code IS NOT NULL
    GROUP BY store_id, date, pickup_code
    HAVING COUNT(*) > 1
) d ON o.store_id = d.store_id AND o.date = d.date AND o.pickup_code = d.pickup_code
SET o.pickup_code = NULL
WHERE o.id <> d.keep_id;

-- 2. 建立唯一索引(幂等)
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'order' AND index_name = 'uk_order_store_date_pickup');
SET @sql = IF(@idx_exists = 0, 'CREATE UNIQUE INDEX uk_order_store_date_pickup ON `order`(store_id, date, pickup_code)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
