-- =====================================================
-- V30: order 表固化部门快照
-- 背景: 订单列表/导出的部门名称此前为"实时查 employee->department",
--       员工被删除后历史订单部门被清空。现补部门快照列,与 V29 姓名/卡号快照配套,
--       删除/禁用/换卡都不影响历史订单展示。
-- =====================================================

ALTER TABLE `order` ADD COLUMN department_name VARCHAR(100) COMMENT '部门名称快照(下单时固化)' AFTER employee_card_no;

-- 回填历史订单:用现存员工+部门补上快照(物理删除的员工查不到,维持空)
UPDATE `order` o
JOIN employee e ON o.employee_id = e.id
LEFT JOIN department d ON e.department_id = d.id
SET o.department_name = d.name;