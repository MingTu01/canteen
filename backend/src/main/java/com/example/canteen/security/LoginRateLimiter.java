package com.example.canteen.security;

import com.example.canteen.exception.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录限流:同一账号多次失败后临时锁定。
 *
 * 内网部署放宽:默认 10 次失败 → 锁 5 分钟(可通过 sys_config 配置)。
 * 内存实现,单实例足够;多实例需替换为 Redis 版本。
 *
 * 配置项(每 60 秒刷新一次):
 * - login_rate_limit_max_fail:最大失败次数,默认 10
 * - login_rate_limit_lock_minutes:锁定分钟数,默认 5
 */
@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final long CONFIG_REFRESH_MS = 60_000L;

    private final JdbcTemplate jdbcTemplate;

    private volatile int maxFail = 10;
    private volatile long lockMs = 5L * 60 * 1000;
    private volatile long lastConfigRefresh = 0L;
    private final Map<String, FailInfo> map = new ConcurrentHashMap<>();

    public LoginRateLimiter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 刷新配置(每 60 秒最多一次)。配置读取失败用默认值。 */
    void refreshConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigRefresh < CONFIG_REFRESH_MS) return;
        lastConfigRefresh = now;
        try {
            maxFail = getIntConfig("login_rate_limit_max_fail", 10);
            int lockMinutes = getIntConfig("login_rate_limit_lock_minutes", 5);
            lockMs = lockMinutes * 60_000L;
        } catch (Exception e) {
            // 配置读取失败用默认值
            log.warn("读取登录限流配置失败,使用默认值:{}", e.getMessage());
        }
    }

    /** 从 sys_config 读取 int 配置;读取失败返回默认值。 */
    public int getIntConfig(String key, int def) {
        try {
            Integer v = jdbcTemplate.queryForObject(
                    "SELECT config_value FROM sys_config WHERE config_key = ?",
                    Integer.class, key);
            return v == null ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    /** 校验是否已被锁定;锁定则抛 SecurityException(403)。 */
    public void checkLocked(String key) {
        refreshConfig();
        FailInfo info = map.get(key);
        if (info != null && info.isLocked(lockMs, maxFail)) {
            long remainMs = lockMs - (System.currentTimeMillis() - info.firstFailAt);
            long remainMin = Math.max(1, (remainMs + 59_000) / 60_000);
            log.warn("账号 '{}' 已锁定(失败 {}/{} 次),剩余锁定约 {} 分钟", key, info.count.get(), maxFail, remainMin);
            throw new SecurityException(SecurityException.FORBIDDEN,
                    "账号已锁定,请约 " + remainMin + " 分钟后重试");
        }
    }

    /** 记录一次失败。 */
    public void recordFail(String key) {
        refreshConfig();
        map.compute(key, (k, info) -> {
            if (info == null || info.shouldReset(lockMs)) return new FailInfo();
            int newCount = info.count.incrementAndGet();
            if (newCount >= maxFail) {
                log.warn("账号 '{}' 登录失败达到 {}/{} 次,即将锁定 {} 分钟", key, newCount, maxFail, lockMs / 60_000);
            }
            return info;
        });
    }

    /** 记录成功(清空失败计数)。 */
    public void recordSuccess(String key) {
        map.remove(key);
    }

    public int getMaxFail() {
        refreshConfig();
        return maxFail;
    }

    public long getLockMs() {
        refreshConfig();
        return lockMs;
    }

    /** 单个 key 的失败信息。 */
    private static class FailInfo {
        final AtomicInteger count = new AtomicInteger(1);
        final long firstFailAt = System.currentTimeMillis();

        boolean isLocked(long lockMs, int maxFail) {
            return count.get() >= maxFail
                    && System.currentTimeMillis() - firstFailAt < lockMs
                    && count.get() > 0;
        }

        boolean shouldReset(long lockMs) {
            return System.currentTimeMillis() - firstFailAt >= lockMs;
        }
    }
}
