-- =====================================================
-- V29: order 表固化员工姓名/卡号快照
-- 背景: 订单为固定已消费数据。此前订单的员工姓名/卡号为"实时查 employee 表"，
--       员工被删除后历史订单显示 #员工id,更换卡号后历史订单显示新卡号,均不符预期。
--       现改为下单时固化快照,删除/禁用/换卡都不影响历史订单展示。
-- =====================================================

ALTER TABLE `order` ADD COLUMN employee_name VARCHAR(50) COMMENT '员工姓名快照(下单时固化)' AFTER employee_id;
ALTER TABLE `order` ADD COLUMN employee_card_no VARCHAR(50) COMMENT '员工卡号快照(下单时固化)' AFTER employee_name;

-- 回填历史订单:用现存员工补上快照(已物理删除的员工查不到,维持空)
UPDATE `order` o JOIN employee e ON o.employee_id = e.id
SET o.employee_name = e.name, o.employee_card_no = e.card_no;