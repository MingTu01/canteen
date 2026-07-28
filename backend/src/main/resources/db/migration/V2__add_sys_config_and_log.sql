-- =====================================================
-- V2 升级版本：添加系统配置表和操作日志表
-- =====================================================

-- 系统配置表（存储全局配置项）
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) UNIQUE NOT NULL COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    description VARCHAR(255) COMMENT '配置描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 操作日志表
CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    admin_id BIGINT COMMENT '操作人ID',
    admin_name VARCHAR(50) COMMENT '操作人姓名',
    store_id BIGINT COMMENT '门店ID',
    operation VARCHAR(100) NOT NULL COMMENT '操作类型',
    method VARCHAR(200) COMMENT '请求方法',
    params TEXT COMMENT '请求参数',
    ip VARCHAR(50) COMMENT 'IP地址',
    status TINYINT DEFAULT 1 COMMENT '状态：1成功，0失败',
    error_msg TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_log_admin (admin_id),
    INDEX idx_log_store (store_id),
    INDEX idx_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- 初始配置数据
INSERT INTO sys_config (config_key, config_value, description) VALUES
('system_version', '1.0.0', '系统版本号'),
('order_advance_days', '7', '可提前预订天数'),
('cancel_deadline_minutes', '30', '取消订单截止时间（分钟）'),
('balance_min_warning', '50.00', '余额最低预警值'),
('backup_auto_enabled', 'true', '是否启用自动备份'),
('backup_keep_days', '30', '备份保留天数');
