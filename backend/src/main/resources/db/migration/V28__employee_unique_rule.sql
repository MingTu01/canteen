-- V28: 员工标识唯一性规则调整
-- 背景: 旧版本 employee.card_no 为全局 UNIQUE,删除食堂后员工仅软删除(is_deleted=1),
--    旧卡号残留导致新食堂无法复用相同卡号(添加员工报 "卡号已存在: 8629313")。
-- 需求: ①卡号同一门店内唯一、跨门店可重复(刷卡消费在同店内识别员工);
--       ②手机号作为 H5 登录全系统唯一凭证,登录时后端按手机号自动定位所属门店(无需用户选店)。
-- 方案: 唯一性在应用层按活跃员工(is_deleted=0,符合逻辑删除语义)校验,
--    允许"删除后复用卡号/手机号",故此处数据库索引为非唯一索引,仅用于查询加速。
--    同时一次性物理清理孤儿员工/菜品(其所属食堂已被删除而软删除残留)。

-- 1) 清理孤儿员工/菜品:所属食堂已删除(is_deleted=1 或 store_id 对应食堂不存在)
DELETE FROM employee WHERE is_deleted = 1 OR store_id NOT IN (SELECT id FROM store);
DELETE FROM dish WHERE is_deleted = 1 OR store_id NOT IN (SELECT id FROM store);

-- 2) 卡号: 移除全局唯一索引,新增 (store_id, card_no) 复合索引(同店查询 selectByCardNoAndStore 加速)
--    V1 中 `card_no VARCHAR(50) UNIQUE NOT NULL` 隐式创建了名为 `card_no` 的唯一索引
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'employee' AND index_name = 'card_no'
);
SET @sql = IF(@idx_exists = 1, 'ALTER TABLE employee DROP INDEX card_no', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'employee' AND index_name = 'idx_employee_store_card'
);
SET @sql = IF(@idx_exists = 0, 'ALTER TABLE employee ADD INDEX idx_employee_store_card (store_id, card_no)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 手机号: 新增全局索引(H5 登录 selectByPhone 全表按 phone 定位门店)
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'employee' AND index_name = 'idx_employee_phone'
);
SET @sql = IF(@idx_exists = 0, 'ALTER TABLE employee ADD INDEX idx_employee_phone (phone)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;