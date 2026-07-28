-- =====================================================
-- V1 初始版本：创建所有基础表和初始数据
-- =====================================================

CREATE TABLE IF NOT EXISTS store (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '门店名称',
    code VARCHAR(50) UNIQUE NOT NULL COMMENT '门店编码',
    address VARCHAR(255) COMMENT '门店地址',
    phone VARCHAR(20) COMMENT '联系电话',
    status TINYINT DEFAULT 1 COMMENT '状态：0禁用，1启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_store_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店表';

CREATE TABLE IF NOT EXISTS department (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL COMMENT '门店ID',
    name VARCHAR(100) NOT NULL COMMENT '部门名称',
    parent_id BIGINT DEFAULT 0 COMMENT '父部门ID',
    status TINYINT DEFAULT 1 COMMENT '状态：0禁用，1启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_dept_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

CREATE TABLE IF NOT EXISTS employee (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL COMMENT '门店ID',
    card_no VARCHAR(50) UNIQUE NOT NULL COMMENT '员工卡号',
    name VARCHAR(50) NOT NULL COMMENT '员工姓名',
    avatar VARCHAR(255) COMMENT '头像URL',
    department_id BIGINT COMMENT '部门ID',
    balance DECIMAL(10,2) DEFAULT 0 COMMENT '余额',
    status TINYINT DEFAULT 1 COMMENT '状态：0禁用，1启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_emp_store (store_id),
    INDEX idx_emp_card (card_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工表';

CREATE TABLE IF NOT EXISTS dish (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL COMMENT '门店ID',
    name VARCHAR(100) NOT NULL COMMENT '菜品名称',
    price DECIMAL(10,2) NOT NULL COMMENT '价格',
    image VARCHAR(255) COMMENT '菜品图片',
    category VARCHAR(50) COMMENT '菜品分类',
    is_new TINYINT DEFAULT 0 COMMENT '是否新品：0否，1是',
    status TINYINT DEFAULT 1 COMMENT '状态：0禁用，1启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_dish_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品表';

CREATE TABLE IF NOT EXISTS menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL COMMENT '门店ID',
    date DATE NOT NULL COMMENT '菜单日期',
    meal_type TINYINT NOT NULL COMMENT '餐次：1早餐，2午餐，3晚餐',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_menu_store_date_type (store_id, date, meal_type),
    INDEX idx_menu_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单表';

CREATE TABLE IF NOT EXISTS menu_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    dish_id BIGINT NOT NULL COMMENT '菜品ID',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_menu_item_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单菜品关联表';

CREATE TABLE IF NOT EXISTS `order` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(50) UNIQUE NOT NULL COMMENT '订单号',
    store_id BIGINT NOT NULL COMMENT '门店ID',
    employee_id BIGINT NOT NULL COMMENT '员工ID',
    date DATE NOT NULL COMMENT '订餐日期',
    meal_type TINYINT NOT NULL COMMENT '餐次：1早餐，2午餐，3晚餐',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单金额',
    status TINYINT DEFAULT 1 COMMENT '状态：1待取餐，2已完成，3已取消',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_order_store (store_id),
    INDEX idx_order_employee (employee_id),
    INDEX idx_order_date (date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '订单ID',
    dish_id BIGINT NOT NULL COMMENT '菜品ID',
    dish_name VARCHAR(100) NOT NULL COMMENT '菜品名称',
    price DECIMAL(10,2) NOT NULL COMMENT '单价',
    quantity INT DEFAULT 1 COMMENT '数量',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单菜品关联表';

CREATE TABLE IF NOT EXISTS recharge_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL COMMENT '门店ID',
    employee_id BIGINT NOT NULL COMMENT '员工ID',
    amount DECIMAL(10,2) NOT NULL COMMENT '充值金额',
    balance_before DECIMAL(10,2) COMMENT '充值前余额',
    balance_after DECIMAL(10,2) COMMENT '充值后余额',
    operator VARCHAR(50) COMMENT '操作人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_recharge_store (store_id),
    INDEX idx_recharge_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值记录表';

CREATE TABLE IF NOT EXISTS notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL COMMENT '门店ID',
    title VARCHAR(200) NOT NULL COMMENT '通知标题',
    content TEXT COMMENT '通知内容',
    type TINYINT DEFAULT 1 COMMENT '类型：1滚动通知，2公告',
    status TINYINT DEFAULT 1 COMMENT '状态：0禁用，1启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_notification_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知表';

CREATE TABLE IF NOT EXISTS dining_time_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL COMMENT '门店ID',
    meal_type TINYINT NOT NULL COMMENT '餐次：1早餐，2午餐，3晚餐',
    start_time TIME NOT NULL COMMENT '开始时间',
    end_time TIME NOT NULL COMMENT '结束时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_time_slot_store_type (store_id, meal_type),
    INDEX idx_time_slot_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='就餐时段配置表';

CREATE TABLE IF NOT EXISTS admin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码',
    name VARCHAR(50) COMMENT '姓名',
    store_id BIGINT COMMENT '门店ID（管理员所属门店，0为超级管理员）',
    role TINYINT DEFAULT 2 COMMENT '角色：1超级管理员，2门店管理员',
    status TINYINT DEFAULT 1 COMMENT '状态：0禁用，1启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员表';

-- 初始数据
INSERT INTO store (name, code, address, phone) VALUES 
('总部食堂', 'HQ001', '北京市朝阳区建国路88号', '010-88888888'),
('科技园食堂', 'TECH001', '北京市海淀区中关村科技园', '010-66666666');

INSERT INTO department (store_id, name, parent_id) VALUES 
(1, '技术部', 0),
(1, '市场部', 0),
(1, '人事部', 0),
(2, '研发部', 0),
(2, '运维部', 0);

INSERT INTO employee (store_id, card_no, name, avatar, department_id, balance) VALUES 
(1, 'CARD001', '张明', NULL, 1, 500.00),
(1, 'CARD002', '李娜', NULL, 1, 300.00),
(1, 'CARD003', '王磊', NULL, 2, 200.00),
(1, 'CARD004', '赵敏', NULL, 3, 800.00),
(2, 'CARD005', '陈静', NULL, 4, 400.00);

INSERT INTO dish (store_id, name, price, image, category, is_new) VALUES 
(1, '红烧排骨', 15.00, NULL, '荤菜', 0),
(1, '宫保鸡丁', 12.00, NULL, '荤菜', 0),
(1, '青椒肉丝', 11.00, NULL, '荤菜', 0),
(1, '麻婆豆腐', 10.00, NULL, '素菜', 0),
(1, '蒜蓉西兰花', 8.00, NULL, '素菜', 0),
(1, '番茄蛋汤', 4.00, NULL, '汤类', 0),
(1, '白粥', 2.00, NULL, '主食', 0),
(1, '煎蛋', 3.00, NULL, '主食', 0),
(1, '清蒸鲈鱼', 18.00, NULL, '荤菜', 0),
(1, '蛋炒饭', 8.00, NULL, '主食', 0),
(1, '绿豆沙', 3.00, NULL, '饮品', 1),
(1, '凉拌黄瓜', 5.00, NULL, '凉菜', 1),
(1, '酸梅汤', 4.00, NULL, '饮品', 1);

INSERT INTO menu (store_id, date, meal_type) VALUES 
(1, CURDATE(), 1),
(1, CURDATE(), 2),
(1, CURDATE(), 3),
(1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1),
(1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 2),
(1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 3);

INSERT INTO menu_item (menu_id, dish_id, sort_order) VALUES 
(1, 6, 1), (1, 7, 2), (1, 8, 3),
(2, 1, 1), (2, 2, 2), (2, 3, 3), (2, 4, 4),
(3, 9, 1), (3, 5, 2), (3, 10, 3),
(4, 6, 1), (4, 7, 2), (4, 8, 3),
(5, 3, 1), (5, 4, 2), (5, 9, 3), (5, 5, 4),
(6, 1, 1), (6, 4, 2), (6, 10, 3);

INSERT INTO dining_time_slot (store_id, meal_type, start_time, end_time) VALUES 
(1, 1, '07:00:00', '10:00:00'),
(1, 2, '11:00:00', '13:30:00'),
(1, 3, '17:00:00', '19:30:00'),
(2, 1, '07:00:00', '10:00:00'),
(2, 2, '11:00:00', '13:30:00'),
(2, 3, '17:00:00', '19:30:00');

INSERT INTO notification (store_id, title, content, type) VALUES 
(1, '食堂本周五暂停供餐', '因设备维护，本周五（7月18日）食堂暂停供餐一天，敬请谅解。', 1),
(1, '夏季供餐时间调整', '夏季供餐时间调整为：午餐11:00-13:30，晚餐17:00-19:30。', 1),
(1, '端午节特别菜单预告', '端午节当天将提供粽子、咸鸭蛋等特色菜品。', 2);

INSERT INTO admin (username, password, name, store_id, role) VALUES
('admin', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '超级管理员', 0, 1),
('store1', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '总部管理员', 1, 2),
('store2', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '科技园管理员', 2, 2);
