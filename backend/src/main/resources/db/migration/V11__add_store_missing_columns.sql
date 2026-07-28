-- V11: 补齐 store 表缺失的字段(与 Store 实体对齐)
-- 这些字段在 Store.java 实体中已存在,但未在任何迁移脚本中添加,
-- 导致 MyBatis Plus 生成 SELECT logo_url ... 时报 Unknown column 错误

ALTER TABLE store ADD COLUMN logo_url VARCHAR(500) COMMENT '企业 Logo URL' AFTER status;
ALTER TABLE store ADD COLUMN image_url VARCHAR(500) COMMENT '食堂展示图片 URL' AFTER logo_url;
ALTER TABLE store ADD COLUMN terminal_background_url VARCHAR(500) COMMENT '取餐终端主图/背景图 URL' AFTER image_url;
ALTER TABLE store ADD COLUMN h5_banner_url VARCHAR(500) COMMENT 'H5 顶部 banner URL' AFTER terminal_background_url;
ALTER TABLE store ADD COLUMN description TEXT COMMENT '食堂简介' AFTER h5_banner_url;
ALTER TABLE store ADD COLUMN breakfast_start VARCHAR(10) COMMENT '早餐开始时间 HH:mm' AFTER description;
ALTER TABLE store ADD COLUMN breakfast_end VARCHAR(10) COMMENT '早餐结束时间 HH:mm' AFTER breakfast_start;
ALTER TABLE store ADD COLUMN lunch_start VARCHAR(10) COMMENT '午餐开始时间 HH:mm' AFTER breakfast_end;
ALTER TABLE store ADD COLUMN lunch_end VARCHAR(10) COMMENT '午餐结束时间 HH:mm' AFTER lunch_start;
ALTER TABLE store ADD COLUMN dinner_start VARCHAR(10) COMMENT '晚餐开始时间 HH:mm' AFTER lunch_end;
ALTER TABLE store ADD COLUMN dinner_end VARCHAR(10) COMMENT '晚餐结束时间 HH:mm' AFTER dinner_start;
