-- V26: 下线 daily_close 日终对账模块
-- 背景:系统曾并存两套日终对账(DailyCloseService/daily_close 与 DailySettlementService/daily_settlement),
-- 财务口径易漂移。管理后台已统一使用 /api/settlement(daily_settlement),
-- DailyCloseController/DailyCloseService/DailyClose/DailyCloseMapper 已删除,
-- 本迁移删除遗留的 daily_close 表(历史对账数据以 daily_settlement 为准)。
-- 注:V17 迁移中 daily_close 的唯一索引随表一并删除,历史迁移文件不做改动。
DROP TABLE IF EXISTS daily_close;
