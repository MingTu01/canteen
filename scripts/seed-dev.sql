-- =====================================================
-- 开发环境测试数据(seed-dev.sql)
--
-- 用途:
--   本机开发时手动执行,填充测试数据用于功能验证。
--   新部署的生产环境不要执行此脚本!
--
-- 执行方式(必须用 docker cp + source,避免 PowerShell 管道编码导致中文双重编码):
--   docker cp scripts/seed-dev.sql canteen-mysql:/tmp/seed-dev.sql
--   docker exec canteen-mysql mysql -uroot -pcanteen2026 --default-character-set=utf8mb4 canteen -e "source /tmp/seed-dev.sql"
--
-- 禁止使用以下方式(PowerShell Get-Content 默认非 UTF-8,会导致中文双重编码):
--   Get-Content scripts\seed-dev.sql -Raw | docker exec -i canteen-mysql mysql ...  # 错误!
--
-- 数据内容:
--   - 2 个门店(总部食堂 / 科技园食堂)
--   - 5 个部门
--   - 5 个员工(卡号 CARD001~CARD005)
--   - 13 道菜品
--   - 6 个菜单(今日+明日 各3餐)
--   - 6 个就餐时段
--   - 3 条通知
--   - 2 个门店管理员(store1/store2)
--
-- 密码均为 123456(BCrypt 加密)
-- =====================================================

SET FOREIGN_KEY_CHECKS = 0;

-- 清空旧数据(幂等执行)
TRUNCATE TABLE order_item;
TRUNCATE TABLE `order`;
TRUNCATE TABLE menu_item;
TRUNCATE TABLE menu;
TRUNCATE TABLE group_order_item;
TRUNCATE TABLE group_order;
TRUNCATE TABLE purchase_item;
TRUNCATE TABLE purchase;
TRUNCATE TABLE recharge_record;
TRUNCATE TABLE daily_close;
TRUNCATE TABLE daily_settlement;
TRUNCATE TABLE feedback;
TRUNCATE TABLE notification;
TRUNCATE TABLE dining_time_slot;
TRUNCATE TABLE dish;
TRUNCATE TABLE dish_category;
TRUNCATE TABLE material;
TRUNCATE TABLE supplier;
TRUNCATE TABLE employee;
TRUNCATE TABLE department;
TRUNCATE TABLE store;
TRUNCATE TABLE admin;
SET FOREIGN_KEY_CHECKS = 1;

-- 门店(security_code 用于终端绑定:总部=HQ12345678 / 科技园=TECH12345)
INSERT INTO store (name, code, address, phone, security_code, status) VALUES
('总部食堂', 'HQ001', '北京市朝阳区建国路88号', '010-88888888', 'HQ12345678', 1),
('科技园食堂', 'TECH001', '北京市海淀区中关村科技园', '010-66666666', 'TECH12345', 1);

-- 部门
INSERT INTO department (store_id, name, parent_id) VALUES
(1, '技术部', 0),
(1, '市场部', 0),
(1, '人事部', 0),
(2, '研发部', 0),
(2, '运维部', 0);

-- 员工(密码 123456,BCrypt 加密)
-- phone 基于卡号生成:CARD001 -> 13800000001,用于手机号登录
-- password 为 123456 的 BCrypt 哈希,避免依赖 V4/V8 迁移(seed-dev.sql 在迁移之后执行)
INSERT INTO employee (store_id, card_no, name, avatar, department_id, balance, phone, password) VALUES
(1, 'CARD001', '张明', NULL, 1, 500.00, '13800000001', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC'),
(1, 'CARD002', '李娜', NULL, 1, 300.00, '13800000002', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC'),
(1, 'CARD003', '王磊', NULL, 2, 200.00, '13800000003', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC'),
(1, 'CARD004', '赵敏', NULL, 3, 800.00, '13800000004', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC'),
(2, 'CARD005', '陈静', NULL, 4, 400.00, '13800000005', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC');

-- 菜品
INSERT INTO dish (store_id, name, price, image, category) VALUES
(1, '红烧排骨', 15.00, NULL, '荤菜'),
(1, '宫保鸡丁', 12.00, NULL, '荤菜'),
(1, '青椒肉丝', 11.00, NULL, '荤菜'),
(1, '麻婆豆腐', 10.00, NULL, '素菜'),
(1, '蒜蓉西兰花', 8.00, NULL, '素菜'),
(1, '番茄蛋汤', 4.00, NULL, '汤类'),
(1, '白粥', 2.00, NULL, '主食'),
(1, '煎蛋', 3.00, NULL, '主食'),
(1, '清蒸鲈鱼', 18.00, NULL, '荤菜'),
(1, '蛋炒饭', 8.00, NULL, '主食'),
(1, '绿豆沙', 3.00, NULL, '饮品'),
(1, '凉拌黄瓜', 5.00, NULL, '凉菜'),
(1, '酸梅汤', 4.00, NULL, '饮品');

-- 菜单(今日+明日 各3餐)
INSERT INTO menu (store_id, date, meal_type) VALUES
(1, CURDATE(), 1),
(1, CURDATE(), 2),
(1, CURDATE(), 3),
(1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1),
(1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 2),
(1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 3);

-- 菜单项
INSERT INTO menu_item (menu_id, dish_id, sort_order) VALUES
(1, 6, 1), (1, 7, 2), (1, 8, 3),
(2, 1, 1), (2, 2, 2), (2, 3, 3), (2, 4, 4),
(3, 9, 1), (3, 5, 2), (3, 10, 3),
(4, 6, 1), (4, 7, 2), (4, 8, 3),
(5, 3, 1), (5, 4, 2), (5, 9, 3), (5, 5, 4),
(6, 1, 1), (6, 4, 2), (6, 10, 3);

-- 就餐时段
INSERT INTO dining_time_slot (store_id, meal_type, start_time, end_time) VALUES
(1, 1, '07:00:00', '10:00:00'),
(1, 2, '11:00:00', '13:30:00'),
(1, 3, '17:00:00', '19:30:00'),
(2, 1, '07:00:00', '10:00:00'),
(2, 2, '11:00:00', '13:30:00'),
(2, 3, '17:00:00', '19:30:00');

-- 通知
INSERT INTO notification (store_id, title, content, type) VALUES
(1, '食堂本周五暂停供餐', '因设备维护，本周五食堂暂停供餐一天，敬请谅解。', 1),
(1, '夏季供餐时间调整', '夏季供餐时间调整为：午餐11:00-13:30，晚餐17:00-19:30。', 1),
(1, '端午节特别菜单预告', '端午节当天将提供粽子、咸鸭蛋等特色菜品。', 2);

-- 管理员(密码 123456)
INSERT INTO admin (username, password, name, store_id, role) VALUES
('admin', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '超级管理员', 0, 1),
('store1', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '总部管理员', 1, 2),
('store2', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '科技园管理员', 2, 2);
