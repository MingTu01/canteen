-- H2兼容初始化脚本（MySQL模式）

CREATE TABLE IF NOT EXISTS store (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    address VARCHAR(255),
    phone VARCHAR(20),
    security_code VARCHAR(32),
    status INT DEFAULT 1,
    logo_url VARCHAR(500),
    image_url VARCHAR(500),
    terminal_background_url VARCHAR(500),
    h5_banner_url VARCHAR(500),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS department (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT DEFAULT 0,
    status INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS employee (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    card_no VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(50) NOT NULL,
    avatar VARCHAR(255),
    department_id BIGINT,
    balance DECIMAL(10,2) DEFAULT 0,
    status INT DEFAULT 1,
    password VARCHAR(255),
    phone VARCHAR(20),
    wx_openid VARCHAR(64),
    password_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    must_change_password INT DEFAULT 0,
    is_deleted INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_employee_phone ON employee (phone);

CREATE TABLE IF NOT EXISTS dish (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    image VARCHAR(255),
    category VARCHAR(50),
    meal_types VARCHAR(20) DEFAULT '1,2,3',
    stock INT DEFAULT -1,
    max_per_order INT DEFAULT 5,
    status INT DEFAULT 1,
    is_deleted INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 菜品分类表(按门店隔离)
CREATE TABLE IF NOT EXISTS dish_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    sort INT DEFAULT 0,
    status INT DEFAULT 1,
    is_deleted INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    date DATE NOT NULL,
    meal_type INT NOT NULL,
    published INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_menu_store_date_type UNIQUE (store_id, date, meal_type)
);

CREATE TABLE IF NOT EXISTS menu_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    menu_id BIGINT NOT NULL,
    dish_id BIGINT NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_menu_dish UNIQUE (menu_id, dish_id)
);

CREATE TABLE IF NOT EXISTS `order` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(50) UNIQUE NOT NULL,
    store_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    date DATE NOT NULL,
    meal_type INT NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    status INT DEFAULT 1,
    pickup_code VARCHAR(10),
    order_source INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_order_store_date_status ON `order` (store_id, date, status);
-- V18 取餐码「同店+当天」唯一(与 MySQL Flyway 对齐;NULL 不参与唯一约束)
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_store_date_pickup ON `order` (store_id, date, pickup_code);

CREATE TABLE IF NOT EXISTS order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    quantity INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_order_item_dish ON order_item (dish_id);

CREATE TABLE IF NOT EXISTS recharge_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    balance_before DECIMAL(10,2),
    balance_after DECIMAL(10,2),
    operator VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_recharge_created ON recharge_record (created_at);

CREATE TABLE IF NOT EXISTS notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    image_url TEXT,
    type INT DEFAULT 1,
    status INT DEFAULT 1,
    publish_at TIMESTAMP NULL,
    expire_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dining_time_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    meal_type INT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_time_slot_store_type UNIQUE (store_id, meal_type)
);

CREATE TABLE IF NOT EXISTS admin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(50),
    store_id BIGINT,
    role INT DEFAULT 2,
    status INT DEFAULT 1,
    password_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) UNIQUE NOT NULL,
    config_value TEXT,
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    admin_id BIGINT,
    admin_name VARCHAR(50),
    store_id BIGINT,
    operation VARCHAR(100) NOT NULL,
    method VARCHAR(200),
    params TEXT,
    ip VARCHAR(50),
    status INT DEFAULT 1,
    error_msg TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 数据库迁移版本表(用于追踪已执行的 schema 变更)
CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(50) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    success INT DEFAULT 1,
    error_msg VARCHAR(1000) DEFAULT ''
);

-- Token 黑名单表(注销后的 token 在过期前不可再用)
CREATE TABLE IF NOT EXISTS token_blacklist (
    token_jti VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires ON token_blacklist (expires_at);

-- ========== V16 业务扩展表(与 Flyway V16 对齐,补齐 dev/H2 环境缺失) ==========
-- 供应商表
CREATE TABLE IF NOT EXISTS supplier (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    name VARCHAR(100),
    contact_person VARCHAR(50),
    phone VARCHAR(30),
    address VARCHAR(200),
    category VARCHAR(50),
    status INT DEFAULT 1,
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_supplier_store ON supplier (store_id);

-- 采购单表
CREATE TABLE IF NOT EXISTS purchase (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    purchase_no VARCHAR(50),
    supplier_id BIGINT,
    total_amount DECIMAL(10,2),
    purchase_date DATE,
    status INT DEFAULT 1,
    remark VARCHAR(500),
    operator_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_purchase_store_created ON purchase (store_id, created_at);
CREATE INDEX IF NOT EXISTS idx_purchase_store_status ON purchase (store_id, status);

-- 采购明细表
CREATE TABLE IF NOT EXISTS purchase_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_id BIGINT,
    material_name VARCHAR(100),
    unit VARCHAR(20),
    quantity DECIMAL(10,2),
    price DECIMAL(10,2),
    amount DECIMAL(10,2),
    material_id BIGINT
);
CREATE INDEX IF NOT EXISTS idx_purchase_item_purchase_id ON purchase_item (purchase_id);

-- 物料表
CREATE TABLE IF NOT EXISTS material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    name VARCHAR(100),
    unit VARCHAR(20),
    stock_qty DECIMAL(10,2) DEFAULT 0,
    min_stock DECIMAL(10,2) DEFAULT 0,
    category VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_material_store ON material (store_id);

-- 反馈表
CREATE TABLE IF NOT EXISTS feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    employee_id BIGINT,
    order_id BIGINT,
    dish_id BIGINT,
    rating INT,
    content TEXT,
    category INT DEFAULT 1,
    status INT DEFAULT 1,
    reply TEXT,
    reply_admin_id BIGINT,
    replied_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_feedback_store_created ON feedback (store_id, created_at);
CREATE INDEX IF NOT EXISTS idx_feedback_store_status ON feedback (store_id, status);

-- 团购订单表
CREATE TABLE IF NOT EXISTS group_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    order_no VARCHAR(50),
    title VARCHAR(200),
    organizer_id BIGINT,
    headcount INT,
    meal_date DATE,
    meal_type INT,
    location VARCHAR(200),
    total_amount DECIMAL(10,2),
    status INT DEFAULT 1,
    remark VARCHAR(500),
    operator_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_group_order_store_created ON group_order (store_id, created_at);
CREATE INDEX IF NOT EXISTS idx_group_order_store_status ON group_order (store_id, status);

-- 团购明细表
CREATE TABLE IF NOT EXISTS group_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_order_id BIGINT,
    dish_id BIGINT,
    dish_name VARCHAR(100),
    price DECIMAL(10,2),
    quantity INT,
    amount DECIMAL(10,2)
);
CREATE INDEX IF NOT EXISTS idx_group_order_item_order_id ON group_order_item (group_order_id);

-- 日结表
CREATE TABLE IF NOT EXISTS daily_close (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    close_date DATE,
    order_count INT,
    total_revenue DECIMAL(10,2),
    total_refund DECIMAL(10,2),
    recharge_amount DECIMAL(10,2),
    status INT DEFAULT 1,
    operator_id BIGINT,
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_daily_close_store_date ON daily_close (store_id, close_date);
-- V17 对账幂等唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_daily_close_store_date ON daily_close (store_id, close_date);

-- 日结算表
CREATE TABLE IF NOT EXISTS daily_settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    settle_date DATE,
    total_orders INT DEFAULT 0,
    total_revenue DECIMAL(10,2) DEFAULT 0,
    total_refund DECIMAL(10,2) DEFAULT 0,
    total_recharge DECIMAL(10,2) DEFAULT 0,
    total_consumption DECIMAL(10,2) DEFAULT 0,
    cash_revenue DECIMAL(10,2) DEFAULT 0,
    online_revenue DECIMAL(10,2) DEFAULT 0,
    order_count INT DEFAULT 0,
    completed_count INT DEFAULT 0,
    cancelled_count INT DEFAULT 0,
    served_count INT DEFAULT 0,
    operator_id BIGINT,
    status INT DEFAULT 1,
    remark VARCHAR(500),
    settled_at TIMESTAMP,
    closed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_daily_settlement_store_date ON daily_settlement (store_id, settle_date);
CREATE INDEX IF NOT EXISTS idx_daily_settlement_store_status ON daily_settlement (store_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_daily_settlement_store_date ON daily_settlement (store_id, settle_date);

-- 盘点表(difference 为 SQL 函数名,用反引号转义)
CREATE TABLE IF NOT EXISTS stock_count (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT,
    material_id BIGINT,
    material_name VARCHAR(100),
    system_qty DECIMAL(10,2),
    counted_qty DECIMAL(10,2),
    `difference` DECIMAL(10,2),
    status INT DEFAULT 1,
    operator_id BIGINT,
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_stock_count_store_status ON stock_count (store_id, status);
CREATE INDEX IF NOT EXISTS idx_stock_count_material ON stock_count (material_id);

-- 初始数据
INSERT INTO store (name, code, address, phone, security_code) VALUES
('总部食堂', 'HQ001', '北京市朝阳区建国路88号', '010-88888888', 'HQ2026AB'),
('科技园食堂', 'TECH001', '北京市海淀区中关村科技园', '010-66666666', 'TECH2026');

INSERT INTO department (store_id, name, parent_id) VALUES
(1, '技术部', 0), (1, '市场部', 0), (1, '人事部', 0),
(2, '研发部', 0), (2, '运维部', 0);

-- D7 密码哈希统一为 123456 的 BCrypt 哈希
-- 密码: 123456 (BCrypt加密)
-- phone 字段:基于卡号生成,用于 H5 手机号登录测试(CARD001 -> 13800000001)
INSERT INTO employee (store_id, card_no, phone, name, department_id, balance, password, is_deleted) VALUES
(1, 'CARD001', '13800000001', '张明', 1, 500.00, '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', 0),
(1, 'CARD002', '13800000002', '李娜', 1, 300.00, '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', 0),
(1, 'CARD003', '13800000003', '王磊', 2, 200.00, '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', 0),
(1, 'CARD004', '13800000004', '赵敏', 3, 800.00, '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', 0),
(2, 'CARD005', '13800000005', '陈静', 4, 400.00, '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', 0);

INSERT INTO dish (store_id, name, price, image, category, meal_types, stock, max_per_order, is_deleted) VALUES
(1, '红烧排骨', 15.00, 'https://images.unsplash.com/photo-1525755662778-989d0524087e?w=400&q=80', '荤菜', '2,3', 100, 5, 0),
(1, '宫保鸡丁', 12.00, 'https://images.unsplash.com/photo-1525755662778-989d0524087e?w=400&q=80', '荤菜', '2,3', 100, 5, 0),
(1, '青椒肉丝', 11.00, 'https://images.unsplash.com/photo-1606756790138-261d2b21cd75?w=400&q=80', '荤菜', '2,3', 100, 5, 0),
(1, '麻婆豆腐', 10.00, 'https://images.unsplash.com/photo-1582878826629-29b7ad1cdc43?w=400&q=80', '素菜', '2,3', 100, 5, 0),
(1, '蒜蓉西兰花', 8.00, 'https://images.unsplash.com/photo-1583663848850-46af132dc08e?w=400&q=80', '素菜', '2,3', 100, 5, 0),
(1, '番茄蛋汤', 4.00, 'https://images.unsplash.com/photo-1547592180-85f173990554?w=400&q=80', '汤类', '1,2,3', 100, 5, 0),
(1, '白粥', 2.00, 'https://images.unsplash.com/photo-1582452459900-7e0bb4d46937?w=400&q=80', '主食', '1', 100, 5, 0),
(1, '煎蛋', 3.00, 'https://images.unsplash.com/photo-1567652731832-5ce5e6d8e1c5?w=400&q=80', '主食', '1', 100, 5, 0),
(1, '清蒸鲈鱼', 18.00, 'https://images.unsplash.com/photo-1535140728325-a4d3707eee96?w=400&q=80', '荤菜', '3', 100, 5, 0),
(1, '蛋炒饭', 8.00, 'https://images.unsplash.com/photo-1603133872878-684f208fb84b?w=400&q=80', '主食', '2,3', 100, 5, 0),
(1, '绿豆沙', 3.00, 'https://images.unsplash.com/photo-1571212515416-fef07fee64b9?w=400&q=80', '饮品', '1,2,3', 100, 5, 0),
(1, '凉拌黄瓜', 5.00, 'https://images.unsplash.com/photo-1604152135912-04a022e23696?w=400&q=80', '凉菜', '2,3', 100, 5, 0),
(1, '酸梅汤', 4.00, 'https://images.unsplash.com/photo-1626202373052-9d3e4e2c5e7f?w=400&q=80', '饮品', '1,2,3', 100, 5, 0),
(1, '小笼包', 6.00, 'https://images.unsplash.com/photo-1496116218417-1a781b1c376c?w=400&q=80', '主食', '1', 100, 5, 0),
(1, '豆浆', 2.50, 'https://images.unsplash.com/photo-1547592180-85f173990554?w=400&q=80', '饮品', '1', 100, 5, 0),
(1, '油条', 2.00, 'https://images.unsplash.com/photo-1606756790138-261d2b21cd75?w=400&q=80', '主食', '1', 100, 5, 0),
(1, '糖醋里脊', 16.00, 'https://images.unsplash.com/photo-1582452459900-7e0bb4d46937?w=400&q=80', '荤菜', '2,3', 100, 5, 0),
(1, '紫菜汤', 4.00, 'https://images.unsplash.com/photo-1547592180-85f173990554?w=400&q=80', '汤类', '1,2,3', 100, 5, 0),
(1, '小米粥', 3.00, 'https://images.unsplash.com/photo-1582452459900-7e0bb4d46937?w=400&q=80', '主食', '1', 100, 5, 0),
(1, '鱼香肉丝', 13.00, 'https://images.unsplash.com/photo-1606756790138-261d2b21cd75?w=400&q=80', '荤菜', '2,3', 100, 5, 0),
(1, '酸辣土豆丝', 7.00, 'https://images.unsplash.com/photo-1583663848850-46af132dc08e?w=400&q=80', '素菜', '2,3', 100, 5, 0);

-- 7天菜单:今天 + 6 天,每天 3 餐(共 21 个菜单,菜单ID 1-21)
-- 菜单ID 1-3:今天早/午/晚;4-6:明天;7-9:后天;10-12:第4天;13-15:第5天;16-18:第6天;19-21:第7天
INSERT INTO menu (store_id, date, meal_type) VALUES
-- 今天
(1, CURRENT_DATE, 1), (1, CURRENT_DATE, 2), (1, CURRENT_DATE, 3),
-- 明天
(1, DATEADD('DAY', 1, CURRENT_DATE), 1),
(1, DATEADD('DAY', 1, CURRENT_DATE), 2),
(1, DATEADD('DAY', 1, CURRENT_DATE), 3),
-- 后天
(1, DATEADD('DAY', 2, CURRENT_DATE), 1),
(1, DATEADD('DAY', 2, CURRENT_DATE), 2),
(1, DATEADD('DAY', 2, CURRENT_DATE), 3),
-- 第4天
(1, DATEADD('DAY', 3, CURRENT_DATE), 1),
(1, DATEADD('DAY', 3, CURRENT_DATE), 2),
(1, DATEADD('DAY', 3, CURRENT_DATE), 3),
-- 第5天
(1, DATEADD('DAY', 4, CURRENT_DATE), 1),
(1, DATEADD('DAY', 4, CURRENT_DATE), 2),
(1, DATEADD('DAY', 4, CURRENT_DATE), 3),
-- 第6天
(1, DATEADD('DAY', 5, CURRENT_DATE), 1),
(1, DATEADD('DAY', 5, CURRENT_DATE), 2),
(1, DATEADD('DAY', 5, CURRENT_DATE), 3),
-- 第7天
(1, DATEADD('DAY', 6, CURRENT_DATE), 1),
(1, DATEADD('DAY', 6, CURRENT_DATE), 2),
(1, DATEADD('DAY', 6, CURRENT_DATE), 3);

-- 菜品 ID 对照:
-- 1 红烧排骨 2 宫保鸡丁 3 青椒肉丝 4 麻婆豆腐 5 蒜蓉西兰花 6 番茄蛋汤
-- 7 白粥 8 煎蛋 9 清蒸鲈鱼 10 蛋炒饭 11 绿豆沙 12 凉拌黄瓜 13 酸梅汤
-- 14 小笼包 15 豆浆 16 油条 17 糖醋里脊 18 紫菜汤 19 小米粥 20 鱼香肉丝 21 酸辣土豆丝
INSERT INTO menu_item (menu_id, dish_id, sort_order) VALUES
-- 今天
-- 早(menu 1):白粥+煎蛋+小笼包+豆浆+番茄蛋汤
(1, 7, 1), (1, 8, 2), (1, 14, 3), (1, 15, 4), (1, 6, 5),
-- 午(menu 2):红烧排骨+宫保鸡丁+麻婆豆腐+蒜蓉西兰花+蛋炒饭+绿豆沙
(2, 1, 1), (2, 2, 2), (2, 4, 3), (2, 5, 4), (2, 10, 5), (2, 11, 6),
-- 晚(menu 3):清蒸鲈鱼+糖醋里脊+酸辣土豆丝+凉拌黄瓜+酸梅汤
(3, 9, 1), (3, 17, 2), (3, 21, 3), (3, 12, 4), (3, 13, 5),
-- 明天
-- 早(menu 4):小米粥+油条+小笼包+豆浆+紫菜汤
(4, 19, 1), (4, 16, 2), (4, 14, 3), (4, 15, 4), (4, 18, 5),
-- 午(menu 5):鱼香肉丝+青椒肉丝+麻婆豆腐+蒜蓉西兰花+蛋炒饭
(5, 20, 1), (5, 3, 2), (5, 4, 3), (5, 5, 4), (5, 10, 5),
-- 晚(menu 6):红烧排骨+清蒸鲈鱼+酸辣土豆丝+凉拌黄瓜+绿豆沙
(6, 1, 1), (6, 9, 2), (6, 21, 3), (6, 12, 4), (6, 11, 5),
-- 后天
-- 早(menu 7):白粥+煎蛋+油条+豆浆+番茄蛋汤
(7, 7, 1), (7, 8, 2), (7, 16, 3), (7, 15, 4), (7, 6, 5),
-- 午(menu 8):宫保鸡丁+糖醋里脊+酸辣土豆丝+蒜蓉西兰花+酸梅汤
(8, 2, 1), (8, 17, 2), (8, 21, 3), (8, 5, 4), (8, 13, 5),
-- 晚(menu 9):清蒸鲈鱼+鱼香肉丝+麻婆豆腐+凉拌黄瓜+蛋炒饭
(9, 9, 1), (9, 20, 2), (9, 4, 3), (9, 12, 4), (9, 10, 5),
-- 第4天
-- 早(menu 10):小米粥+小笼包+煎蛋+豆浆+紫菜汤
(10, 19, 1), (10, 14, 2), (10, 8, 3), (10, 15, 4), (10, 18, 5),
-- 午(menu 11):红烧排骨+青椒肉丝+酸辣土豆丝+蛋炒饭+绿豆沙
(11, 1, 1), (11, 3, 2), (11, 21, 3), (11, 10, 4), (11, 11, 5),
-- 晚(menu 12):糖醋里脊+清蒸鲈鱼+麻婆豆腐+凉拌黄瓜+酸梅汤
(12, 17, 1), (12, 9, 2), (12, 4, 3), (12, 12, 4), (12, 13, 5),
-- 第5天
-- 早(menu 13):白粥+油条+小笼包+豆浆+番茄蛋汤
(13, 7, 1), (13, 16, 2), (13, 14, 3), (13, 15, 4), (13, 6, 5),
-- 午(menu 14):鱼香肉丝+宫保鸡丁+蒜蓉西兰花+蛋炒饭+酸梅汤
(14, 20, 1), (14, 2, 2), (14, 5, 3), (14, 10, 4), (14, 13, 5),
-- 晚(menu 15):红烧排骨+糖醋里脊+酸辣土豆丝+凉拌黄瓜+绿豆沙
(15, 1, 1), (15, 17, 2), (15, 21, 3), (15, 12, 4), (15, 11, 5),
-- 第6天
-- 早(menu 16):小米粥+煎蛋+油条+豆浆+紫菜汤
(16, 19, 1), (16, 8, 2), (16, 16, 3), (16, 15, 4), (16, 18, 5),
-- 午(menu 17):青椒肉丝+宫保鸡丁+麻婆豆腐+蛋炒饭+绿豆沙
(17, 3, 1), (17, 2, 2), (17, 4, 3), (17, 10, 4), (17, 11, 5),
-- 晚(menu 18):清蒸鲈鱼+鱼香肉丝+酸辣土豆丝+凉拌黄瓜+酸梅汤
(18, 9, 1), (18, 20, 2), (18, 21, 3), (18, 12, 4), (18, 13, 5),
-- 第7天
-- 早(menu 19):白粥+小笼包+煎蛋+豆浆+番茄蛋汤
(19, 7, 1), (19, 14, 2), (19, 8, 3), (19, 15, 4), (19, 6, 5),
-- 午(menu 20):红烧排骨+糖醋里脊+蒜蓉西兰花+蛋炒饭+酸梅汤
(20, 1, 1), (20, 17, 2), (20, 5, 3), (20, 10, 4), (20, 13, 5),
-- 晚(menu 21):清蒸鲈鱼+宫保鸡丁+麻婆豆腐+凉拌黄瓜+绿豆沙
(21, 9, 1), (21, 2, 2), (21, 4, 3), (21, 12, 4), (21, 11, 5);

INSERT INTO dining_time_slot (store_id, meal_type, start_time, end_time) VALUES
(1, 1, '07:00:00', '10:00:00'),
(1, 2, '11:00:00', '13:30:00'),
(1, 3, '17:00:00', '19:30:00'),
(2, 1, '07:00:00', '10:00:00'),
(2, 2, '11:00:00', '13:30:00'),
(2, 3, '17:00:00', '19:30:00');

INSERT INTO notification (store_id, title, content, image_url, type, status) VALUES
-- 滚动通知(type=1)
(1, '食堂本周五暂停供餐', '因设备维护，本周五食堂暂停供餐一天，敬请谅解。次日恢复正常供餐，请大家提前安排。', 'https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=600&q=80', 1, 1),
(1, '夏季供餐时间调整', '夏季供餐时间调整为：早餐07:00-10:00，午餐11:00-13:30，晚餐17:00-19:30。请合理安排就餐时间。', 'https://images.unsplash.com/photo-1551218808-94e220e084d2?w=600&q=80', 1, 1),
(1, '夏季新品上市', '夏季新品绿豆沙、酸梅汤、凉拌黄瓜现已上架，消暑解渴，欢迎品尝！', 'https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=600&q=80', 1, 1),
(1, '订餐优惠活动', '即日起预订次日午餐满20元减2元，多订多减，活动持续至月底。', 'https://images.unsplash.com/photo-1504674900247-0877df9cc836?w=600&q=80', 1, 1),
(1, '健康饮食倡议', '建议每日摄入蔬菜水果不少于500g，食堂新增多款素菜，营养均衡更健康。', 'https://images.unsplash.com/photo-1490645935967-10de6ba17061?w=600&q=80', 1, 1),
-- 公告(type=2)
(1, '端午节特别菜单预告', '端午节当天将提供粽子、咸鸭蛋等特色菜品，并推出端午套餐优惠，敬请期待。', 'https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=600&q=80', 2, 1),
(1, '中秋节活动预告', '中秋节食堂将推出月饼DIY活动，欢迎大家参与。当日午餐赠送月饼一个。', 'https://images.unsplash.com/photo-1606312619070-d48b4c652a52?w=600&q=80', 2, 1),
(1, '食堂满意度调查', '为提升服务质量，请大家参与食堂满意度调查，您的意见对我们很重要。', 'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=600&q=80', 2, 1);

-- D7 密码哈希统一为 123456 的 BCrypt 哈希
INSERT INTO admin (username, password, name, store_id, role) VALUES
('admin', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '超级管理员', 0, 1),
('store1', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '总部管理员', 1, 2),
('store2', '$2b$10$ZYbFA3pByvcxS1/wrlSDP.i8ElgaAIyviU5Pp26Ev8PVJcM2GOMaC', '科技园管理员', 2, 2);

INSERT INTO sys_config (config_key, config_value, description) VALUES
('system_version', '1.1.0', '系统版本号'),
-- 订餐配置
('order_advance_days', '7', '可提前预订天数'),
('order_deadline_time', '15:00', '次日订餐截止时间(前一天 HH:mm,过后不可订次日)'),
('cancel_deadline_time', '15:00', '次日取消截止时间(前一天 HH:mm,过后不可取消次日)'),
('max_order_quantity', '10', '单次最大订餐数量'),
('allow_cross_day_order', 'true', '是否允许跨日订餐'),
-- 支付与余额
('balance_min_warning', '50.00', '余额最低预警值'),
('allow_negative_balance', 'false', '是否允许负余额消费'),
('recharge_min_amount', '1', '最小充值金额'),
('recharge_max_amount', '5000', '最大充值金额'),
-- 通知配置
('notification_default_duration_days', '30', '默认通知有效天数'),
('notification_auto_expire', 'true', '通知到期自动下架'),
-- 备份配置
('backup_auto_enabled', 'true', '是否启用定时备份'),
('backup_keep_copies', '30', '备份保留份数'),
('backup_cron', '0 0 2 * * ?', '定时备份 Cron 表达式'),
('backup_keep_days', '30', '备份保留天数(兼容旧配置)'),
-- 登录限流配置(内网放宽:失败 10 次锁定 5 分钟)
('login_rate_limit_max_fail', '10', '登录失败次数上限'),
('login_rate_limit_lock_minutes', '5', '账号锁定分钟数');
