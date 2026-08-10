-- V20: 门店级订餐配置表
-- 把原全局 sys_config 中的订餐配置(order_deadline_time 等)下沉到每个门店独立配置
-- 读取顺序:store_config(按门店) → sys_config(全局) → 代码默认值
CREATE TABLE IF NOT EXISTS store_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    config_key VARCHAR(100) NOT NULL,
    config_value TEXT,
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_store_config UNIQUE (store_id, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 索引:按门店查询
CREATE INDEX idx_store_config_store ON store_config(store_id);
