-- V6: 通知增加配图、上架时间、下架时间字段,支持定时上下架与到期自动下架
ALTER TABLE notification ADD COLUMN image_url TEXT NULL COMMENT '配图URL或dataURL';
ALTER TABLE notification ADD COLUMN publish_at DATETIME NULL COMMENT '上架时间,NULL表示立即上架';
ALTER TABLE notification ADD COLUMN expire_at DATETIME NULL COMMENT '下架时间,NULL表示不下架;到期自动下架';

-- 历史数据:已存在的通知视为立即上架、不下架
UPDATE notification SET publish_at = created_at WHERE publish_at IS NULL;
