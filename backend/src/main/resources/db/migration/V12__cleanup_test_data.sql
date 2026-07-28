-- =====================================================
-- V12: 清理 V1 中的测试数据,保持项目纯净
--
-- 背景:
--   V1__init_schema.sql 在建表时插入了测试数据(store/department/employee/
--   dish/menu/menu_item/dining_time_slot/notification/admin)。
--   Flyway 已执行过的迁移不可修改,故新增 V12 反向清理。
--
-- 清理范围:
--   - 所有业务表数据(store/department/employee/dish/menu/order 等)
--   - admin 表(仅保留默认超管 admin/123456,store_id=0)
--   - token_blacklist / sys_operation_log(运行时数据)
--
-- 保留:
--   - sys_config(系统默认配置,由 V2/V7/V10 插入)
--   - flyway_schema_history(Flyway 自身记录)
--   - schema_version(SchemaMigrationRunner 记录)
--
-- 说明:
--   新部署的人拿到项目后,Flyway 执行 V1~V12,最终数据库是:
--     - 表结构完整(V1~V11 的 DDL)
--     - 系统配置就绪(sys_config)
--     - 仅有 1 个默认超管 admin/123456
--   本机开发如需测试数据,执行 scripts/seed-dev.sql
-- =====================================================

-- 关闭外键检查,避免 TRUNCATE 时的引用约束
SET FOREIGN_KEY_CHECKS = 0;

-- 清空所有业务表(按依赖顺序,TRUNCATE 重置自增 ID)
-- 仅 TRUNCATE 已存在的表,避免因表不存在导致迁移失败
TRUNCATE TABLE order_item;
TRUNCATE TABLE `order`;
TRUNCATE TABLE menu_item;
TRUNCATE TABLE menu;
TRUNCATE TABLE recharge_record;
TRUNCATE TABLE notification;
TRUNCATE TABLE dining_time_slot;
TRUNCATE TABLE dish;
TRUNCATE TABLE dish_category;
TRUNCATE TABLE employee;
TRUNCATE TABLE department;
TRUNCATE TABLE store;
TRUNCATE TABLE token_blacklist;
TRUNCATE TABLE sys_operation_log;

-- 重置 admin 表:仅保留默认超管(admin/123456)
TRUNCATE TABLE admin;
INSERT INTO admin (username, password, name, store_id, role, status)
VALUES ('admin', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '超级管理员', 0, 1, 1);

-- 恢复外键检查
SET FOREIGN_KEY_CHECKS = 1;

-- 更新系统版本号为 0.0.1
UPDATE sys_config SET config_value = '0.0.1' WHERE config_key = 'system_version';
