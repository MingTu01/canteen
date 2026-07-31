-- V14: 菜单增加发布状态字段
-- published: 0=未发布(草稿,点菜端不可见), 1=已发布(点菜端可见)
ALTER TABLE menu ADD COLUMN published TINYINT NOT NULL DEFAULT 0 COMMENT '发布状态:0=未发布,1=已发布';

-- 已有菜单默认标记为已发布(兼容历史数据)
UPDATE menu SET published = 1;
