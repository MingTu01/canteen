-- V16: 业务扩展表(从 SchemaMigrationRunner 迁移,统一由 Flyway 用 root 执行 DDL)
-- 背景:SchemaMigrationRunner 原先用运行时 DML 用户(canteen_app)执行 CREATE TABLE,
--   缺少 CREATE 权限导致 1142 错误(被误报为 bad SQL grammar),业务表无法创建。
--   现将所有独有 DDL 迁移到 Flyway(用 root 执行),SchemaMigrationRunner 仅保留 DML。
-- 包含:供应商/采购/物料、反馈/团购、日结、日结算、盘点表及其索引。
-- 幂等性:使用 IF NOT EXISTS,兼容之前可能已由 SchemaMigrationRunner(root 模式)创建过的情况。

-- ========== 1. 供应商/采购/物料表(原 SchemaMigrationRunner 1.3.0) ==========
CREATE TABLE IF NOT EXISTS supplier (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    name VARCHAR(100) COMMENT '供应商名称',
    contact_person VARCHAR(50) COMMENT '联系人',
    phone VARCHAR(30) COMMENT '联系电话',
    address VARCHAR(200) COMMENT '地址',
    category VARCHAR(50) COMMENT '分类',
    status INT DEFAULT 1 COMMENT '状态:1启用0禁用',
    remark VARCHAR(500) COMMENT '备注',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商表';

CREATE TABLE IF NOT EXISTS purchase (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    purchase_no VARCHAR(50) COMMENT '采购单号',
    supplier_id BIGINT COMMENT '供应商ID',
    total_amount DECIMAL(10,2) COMMENT '总金额',
    purchase_date DATE COMMENT '采购日期',
    status INT DEFAULT 1 COMMENT '状态',
    remark VARCHAR(500) COMMENT '备注',
    operator_id BIGINT COMMENT '操作人ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单表';

CREATE TABLE IF NOT EXISTS purchase_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    purchase_id BIGINT COMMENT '采购单ID',
    material_name VARCHAR(100) COMMENT '物料名称',
    unit VARCHAR(20) COMMENT '单位',
    quantity DECIMAL(10,2) COMMENT '数量',
    price DECIMAL(10,2) COMMENT '单价',
    amount DECIMAL(10,2) COMMENT '金额',
    material_id BIGINT COMMENT '关联物料ID(用于库存联动,原 SchemaMigrationRunner 1.5.0)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购明细表';

CREATE TABLE IF NOT EXISTS material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    name VARCHAR(100) COMMENT '物料名称',
    unit VARCHAR(20) COMMENT '单位',
    stock_qty DECIMAL(10,2) DEFAULT 0 COMMENT '库存数量',
    min_stock DECIMAL(10,2) DEFAULT 0 COMMENT '最低库存',
    category VARCHAR(50) COMMENT '分类',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料表';

-- 采购/物料相关索引(幂等创建,兼容 MySQL 8 不支持 CREATE INDEX IF NOT EXISTS)
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'supplier' AND index_name = 'idx_supplier_store');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_supplier_store ON supplier(store_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'purchase' AND index_name = 'idx_purchase_store_created');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_purchase_store_created ON purchase(store_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'purchase' AND index_name = 'idx_purchase_store_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_purchase_store_status ON purchase(store_id, status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'purchase_item' AND index_name = 'idx_purchase_item_purchase_id');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_purchase_item_purchase_id ON purchase_item(purchase_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'material' AND index_name = 'idx_material_store');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_material_store ON material(store_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 2. 反馈/团购表(原 SchemaMigrationRunner 1.3.1) ==========
CREATE TABLE IF NOT EXISTS feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    employee_id BIGINT COMMENT '员工ID',
    order_id BIGINT COMMENT '订单ID',
    dish_id BIGINT COMMENT '菜品ID',
    rating INT COMMENT '评分',
    content TEXT COMMENT '反馈内容',
    category INT DEFAULT 1 COMMENT '分类',
    status INT DEFAULT 1 COMMENT '状态',
    reply TEXT COMMENT '回复内容',
    reply_admin_id BIGINT COMMENT '回复管理员ID',
    replied_at TIMESTAMP COMMENT '回复时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反馈表';

CREATE TABLE IF NOT EXISTS group_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    order_no VARCHAR(50) COMMENT '团购单号',
    title VARCHAR(200) COMMENT '标题',
    organizer_id BIGINT COMMENT '组织者ID',
    headcount INT COMMENT '人数',
    meal_date DATE COMMENT '就餐日期',
    meal_type INT COMMENT '餐次',
    location VARCHAR(200) COMMENT '地点',
    total_amount DECIMAL(10,2) COMMENT '总金额',
    status INT DEFAULT 1 COMMENT '状态',
    remark VARCHAR(500) COMMENT '备注',
    operator_id BIGINT COMMENT '操作人ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团购订单表';

CREATE TABLE IF NOT EXISTS group_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_order_id BIGINT COMMENT '团购订单ID',
    dish_id BIGINT COMMENT '菜品ID',
    dish_name VARCHAR(100) COMMENT '菜品名称',
    price DECIMAL(10,2) COMMENT '单价',
    quantity INT COMMENT '数量',
    amount DECIMAL(10,2) COMMENT '金额'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团购明细表';

-- 反馈/团购相关索引(幂等创建)
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'feedback' AND index_name = 'idx_feedback_store_created');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_feedback_store_created ON feedback(store_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'feedback' AND index_name = 'idx_feedback_store_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_feedback_store_status ON feedback(store_id, status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'group_order' AND index_name = 'idx_group_order_store_created');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_group_order_store_created ON group_order(store_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'group_order' AND index_name = 'idx_group_order_store_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_group_order_store_status ON group_order(store_id, status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'group_order_item' AND index_name = 'idx_group_order_item_order_id');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_group_order_item_order_id ON group_order_item(group_order_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 3. 日结表(原 SchemaMigrationRunner 1.3.2) ==========
CREATE TABLE IF NOT EXISTS daily_close (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    close_date DATE COMMENT '日结日期',
    order_count INT COMMENT '订单数',
    total_revenue DECIMAL(10,2) COMMENT '总营收',
    total_refund DECIMAL(10,2) COMMENT '总退款',
    recharge_amount DECIMAL(10,2) COMMENT '充值金额',
    status INT DEFAULT 1 COMMENT '状态',
    operator_id BIGINT COMMENT '操作人ID',
    remark VARCHAR(500) COMMENT '备注',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日结表';

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'daily_close' AND index_name = 'idx_daily_close_store_date');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_daily_close_store_date ON daily_close(store_id, close_date)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 4. 日结算表(原 SchemaMigrationRunner 1.3.3) ==========
CREATE TABLE IF NOT EXISTS daily_settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    settle_date DATE COMMENT '结算日期',
    total_orders INT DEFAULT 0 COMMENT '总订单数',
    total_revenue DECIMAL(10,2) DEFAULT 0 COMMENT '总营收',
    total_refund DECIMAL(10,2) DEFAULT 0 COMMENT '总退款',
    total_recharge DECIMAL(10,2) DEFAULT 0 COMMENT '总充值',
    total_consumption DECIMAL(10,2) DEFAULT 0 COMMENT '总消费',
    cash_revenue DECIMAL(10,2) DEFAULT 0 COMMENT '现金营收',
    online_revenue DECIMAL(10,2) DEFAULT 0 COMMENT '在线营收',
    order_count INT DEFAULT 0 COMMENT '订单数',
    completed_count INT DEFAULT 0 COMMENT '完成数',
    cancelled_count INT DEFAULT 0 COMMENT '取消数',
    served_count INT DEFAULT 0 COMMENT '已取餐数',
    operator_id BIGINT COMMENT '操作人ID',
    status INT DEFAULT 1 COMMENT '状态',
    remark VARCHAR(500) COMMENT '备注',
    settled_at TIMESTAMP COMMENT '结算时间',
    closed_at TIMESTAMP COMMENT '关闭时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日结算表';

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'daily_settlement' AND index_name = 'idx_daily_settlement_store_date');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_daily_settlement_store_date ON daily_settlement(store_id, settle_date)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'daily_settlement' AND index_name = 'idx_daily_settlement_store_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_daily_settlement_store_status ON daily_settlement(store_id, status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 5. 盘点表(原 SchemaMigrationRunner 1.5.1) ==========
CREATE TABLE IF NOT EXISTS stock_count (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_id BIGINT COMMENT '门店ID',
    material_id BIGINT COMMENT '物料ID',
    material_name VARCHAR(100) COMMENT '物料名称',
    system_qty DECIMAL(10,2) COMMENT '系统数量',
    counted_qty DECIMAL(10,2) COMMENT '盘点数量',
    difference DECIMAL(10,2) COMMENT '差异',
    status INT DEFAULT 1 COMMENT '状态',
    operator_id BIGINT COMMENT '操作人ID',
    remark VARCHAR(500) COMMENT '备注',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP COMMENT '处理时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点表';

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'stock_count' AND index_name = 'idx_stock_count_store_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_stock_count_store_status ON stock_count(store_id, status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'stock_count' AND index_name = 'idx_stock_count_material');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_stock_count_material ON stock_count(material_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
