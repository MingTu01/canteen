-- markExpiredOrdersAsMissed 查询条件为 status + date,现有 (store_id,date,status) 索引无法命中前缀
CREATE INDEX idx_order_status_date ON `order` (status, date);
