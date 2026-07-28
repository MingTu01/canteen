-- V5: 菜品餐次标记(meal_types) + 菜品分类管理表(dish_category)
-- 菜品增加适用餐次字段(逗号分隔:1=早餐,2=午餐,3=晚餐)
ALTER TABLE dish ADD COLUMN meal_types VARCHAR(20) DEFAULT '1,2,3';

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
