-- V19: daily_settlement 表新增 missed_count 列(未就餐订单数)
-- 用途:OrderStatus.MISSED(4)状态上线后,日终对账需单独统计未就餐订单数,
--       让财务看清营业额构成(已完成收入 + 未就餐收入),且订单总数 = 已完成 + 已取消 + 未就餐 能对上。
-- DEFAULT 0 保证历史对账记录不会因加列而报错。
ALTER TABLE daily_settlement
    ADD COLUMN missed_count INT DEFAULT 0 COMMENT '未就餐数(超时未核销,已付款未退款)' AFTER served_count;
