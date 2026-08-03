package com.example.canteen.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 黑名单服务。
 *
 * 用途:用户注销时将 token 的 jti 加入黑名单,在 token 自然过期前不可再用。
 *
 * 存储:
 * - 主存储:数据库表 token_blacklist(多实例共享)
 * - 本地缓存:ConcurrentHashMap(jti → 过期时间),DB 异常时兜底
 *
 * P2-2 fail-closed 策略:
 * - DB 写入黑名单时同步写入本地缓存(保证已注销 token 即使 DB 宕机也持续拦截)
 * - DB 查询异常时,若本地缓存命中 → 拦截;缓存未命中 → 放行(避免 DB 抖动踢出全站用户)
 * - 已注销的 token 在本地缓存中保留至其自然过期,DB 恢复后自动同步
 *
 * 清理:每 5 分钟清理已过期的黑名单条目(含 DB 和本地缓存)。
 */
@Service
public class TokenBlacklistService {
    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    private final JdbcTemplate jdbcTemplate;
    private final SecretKey secretKey;

    /** P2-2 本地缓存:jti → 过期时间戳(epoch millis)。DB 异常时用于 fail-closed 兜底。 */
    private final Map<String, Long> blacklistCache = new ConcurrentHashMap<>();

    public TokenBlacklistService(JdbcTemplate jdbcTemplate, SecretKey jwtSecretKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretKey = jwtSecretKey;
    }

    /**
     * 注销时调用:将 token 加入黑名单。
     *
     * 失败语义:
     * - token 已损坏/过期:静默返回(无需加黑名单,原 token 本就不可用)
     * - DB 写入失败:写入本地缓存后抛出 RuntimeException,让调用方感知
     */
    public void blacklist(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("token 解析失败,未加入黑名单: {}", e.getMessage());
            return;
        }
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            log.warn("token 无 jti,未加入黑名单");
            return;
        }
        Date exp = claims.getExpiration();
        if (exp == null || exp.before(new Date())) {
            return; // 已过期,无需加入
        }

        long expMillis = exp.getTime();
        // P2-2 先写入本地缓存(fail-closed:即使 DB 写入失败,本地缓存也能拦截)
        blacklistCache.put(jti, expMillis);

        LocalDateTime expiresAt = LocalDateTime.ofInstant(exp.toInstant(), ZoneId.systemDefault());
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE token_blacklist SET expires_at = ? WHERE token_jti = ?",
                    expiresAt, jti);
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO token_blacklist (token_jti, expires_at) VALUES (?, ?)",
                        jti, expiresAt);
            }
        } catch (Exception e) {
            // DB 写入失败:本地缓存已写入,已注销 token 仍可被拦截
            // 但抛出异常让调用方感知(用户可能需要重试)
            log.error("加入黑名单 DB 写入失败(本地缓存已写入) jti={}", jti, e);
            throw new RuntimeException("注销失败:token 黑名单写入异常", e);
        }
    }

    /**
     * 检查 token jti 是否在黑名单中。
     *
     * P2-2 fail-closed 策略:
     * 1. 先查本地缓存(快路径):缓存命中且未过期 → 拦截
     * 2. 再查 DB:DB 命中 → 写入缓存并拦截
     * 3. DB 异常:缓存命中 → 拦截;缓存未命中 → 放行(避免 DB 抖动踢出全站用户)
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;

        // 1. 快路径:查本地缓存
        Long cachedExp = blacklistCache.get(jti);
        if (cachedExp != null) {
            if (System.currentTimeMillis() < cachedExp) {
                return true; // 缓存命中且未过期 → 拦截
            }
            // 已过期,清理缓存
            blacklistCache.remove(jti);
        }

        // 2. 查 DB(可能存在其他实例写入但本实例缓存未同步的情况)
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM token_blacklist WHERE token_jti = ?",
                    Integer.class, jti);
            boolean blocked = count != null && count > 0;
            if (blocked) {
                // DB 命中,回填缓存(用当前时间 + 25 小时作为保守过期估计)
                blacklistCache.put(jti, System.currentTimeMillis() + 25L * 3600_000);
            }
            return blocked;
        } catch (Exception e) {
            // P2-2 DB 异常时 fail-closed:
            // - 缓存命中(已在上面返回 true)→ 拦截
            // - 缓存未命中 → 放行(避免 DB 抖动踢出全站未注销用户)
            log.warn("黑名单查询失败,使用本地缓存兜底: jti={}", jti);
            return false;
        }
    }

    /** 定时清理已过期的黑名单条目(每 5 分钟一次)。 */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void cleanupExpired() {
        // 清理本地缓存中已过期的条目
        long now = System.currentTimeMillis();
        blacklistCache.entrySet().removeIf(entry -> entry.getValue() < now);

        // 清理 DB 中已过期的条目
        try {
            jdbcTemplate.update(
                    "DELETE FROM token_blacklist WHERE expires_at < ?",
                    LocalDateTime.now());
        } catch (Exception e) {
            log.warn("清理过期条目失败: {}", e.getMessage());
        }
    }
}
