-- V31: 清洗 store 品牌图 URL 中遗留的签名参数(?sig=..&exp=..)
-- 根因:上传返回带签名的完整 URL,早期版本原样入库。JacksonConfig 因 URL 已含 sig=
-- 而跳过重新签名,签名过期(默认7天)后图片访问返回 403 无法显示。
-- 方案:只保留纯 /uploads/ 相对路径,序列化时再统一实时签名。
-- 仅对含 '?' 的行执行 SUBSTRING_INDEX(url,'?',1),幂等。
UPDATE store SET logo_url             = SUBSTRING_INDEX(logo_url, '?', 1)              WHERE logo_url             LIKE '%?%';
UPDATE store SET image_url            = SUBSTRING_INDEX(image_url, '?', 1)             WHERE image_url            LIKE '%?%';
UPDATE store SET terminal_background_url = SUBSTRING_INDEX(terminal_background_url, '?', 1) WHERE terminal_background_url LIKE '%?%';
UPDATE store SET h5_banner_url        = SUBSTRING_INDEX(h5_banner_url, '?', 1)         WHERE h5_banner_url        LIKE '%?%';