-- 会话代数(session generation):用于「退出一个端,其他端同步注销」。
-- 每个员工一个代数,签发员工 token 时写入 claim sg;
-- 任一端退出 / 换号重绑时该员工代数+1,其他端旧 token(sg 与当前值不匹配)立即失效。
-- 兼容:旧 token 无 sg claim 时视为 1;上线后未发生退出的员工不受影响,避免全员强制重登。
ALTER TABLE employee ADD COLUMN session_generation BIGINT NOT NULL DEFAULT 1;