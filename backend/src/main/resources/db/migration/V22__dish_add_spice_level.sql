-- 菜品表新增辣度字段
-- spice_level:0=不辣,1=微辣,2=中辣,3=重辣
ALTER TABLE dish ADD COLUMN spice_level INT DEFAULT 0 AFTER max_per_order;
