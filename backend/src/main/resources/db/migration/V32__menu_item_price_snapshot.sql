-- V32: menu_item 新增价格/辣度快照列(菜单保存时固化,菜品改价不影响已存菜单与历史订单)
-- 幂等性:通过 INFORMATION_SCHEMA.COLUMNS 检查列是否已存在,避免重复执行报错
-- (MySQL 8 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS 语法)

SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'menu_item' AND column_name = 'price');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE menu_item ADD COLUMN price DECIMAL(10,2) NULL COMMENT ''菜品价格快照(菜单保存时固化,菜品改价不影响已存菜单)'' AFTER dish_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'menu_item' AND column_name = 'spice_level');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE menu_item ADD COLUMN spice_level TINYINT NULL COMMENT ''辣度快照(0不辣1微辣2中辣3重辣,菜单保存时固化)'' AFTER price', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量回填:历史菜单项按当前 dish 价格/辣度固化,保证升级后价格与升级前展示一致(此后菜品改价不影响)
UPDATE menu_item mi
INNER JOIN dish d ON mi.dish_id = d.id
SET mi.price = d.price,
    mi.spice_level = d.spice_level
WHERE mi.price IS NULL OR mi.spice_level IS NULL;
