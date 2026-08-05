-- V17: 对账/结算幂等性 - 唯一索引兜底
-- 背景:DailyCloseService.confirm / DailySettlementService.generateSettlement 采用"先查后插",
--   在并发双击/重复提交时可能出现同一 (store_id, date) 重复记录,导致对账数据被污染。
--   DailySettlementService 的状态机(1待对账→2已对账→3已关店)也依赖单条记录,
--   重复插入会让状态机失真。
-- 修复:在 daily_close(store_id, close_date) 与 daily_settlement(store_id, settle_date)
--   上建立唯一索引,从数据库层面保证幂等,重复插入由唯一约束拦截。
-- 幂等性:通过 information_schema.statistics 检查索引是否已存在,可重复执行。
-- (MySQL 8 不支持 CREATE INDEX IF NOT EXISTS)

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'daily_close' AND index_name = 'uk_daily_close_store_date');
SET @sql = IF(@idx_exists = 0, 'CREATE UNIQUE INDEX uk_daily_close_store_date ON daily_close(store_id, close_date)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'daily_settlement' AND index_name = 'uk_daily_settlement_store_date');
SET @sql = IF(@idx_exists = 0, 'CREATE UNIQUE INDEX uk_daily_settlement_store_date ON daily_settlement(store_id, settle_date)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;