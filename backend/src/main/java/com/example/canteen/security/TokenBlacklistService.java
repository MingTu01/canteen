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

/**
 * Token 黑名单服务。
 *
 * 用途:用户注销时将 token 的 jti 加入黑名单,在 token 自然过期前不可再用。
 *
 * 存储:数据库表 token_blacklist(单实例 + 多实例皆可)。
 * 清理:每 5 分钟清理已过期的黑名单条目。
 *
 * 注意:本实现按 jti 维度黑名单(而非完整 token),节省存储;但要求所有 token 都有 jti。
 */
@Service
public class TokenBlacklistService {
    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    private final JdbcTemplate jdbcTemplate;
    private final SecretKey secretKey;

    public TokenBlacklistService(JdbcTemplate jdbcTemplate, SecretKey jwtSecretKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretKey = jwtSecretKey;
    }

    /**
     * 注销时调用:将 token 加入黑名单。
     *
     * 失败语义:
     * - token 已损坏/过期:静默返回(无需加黑名单,原 token 本就不可用)
     * - DB 写入失败:抛出 RuntimeException,调用方需感知(否则用户以为注销成功,实际 token 仍可用)
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
            // token 已损坏/过期,无需加入黑名单,记日志便于排查
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
            // DB 写入失败:抛出异常,让调用方感知,否则用户以为注销成功但 token 实际仍可用
            log.error("加入黑名单失败 jti={}", jti, e);
            throw new RuntimeException("注销失败:token 黑名单写入异常", e);
        }
    }

    /** 检查 token jti 是否在黑名单中。 */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM token_blacklist WHERE token_jti = ?",
                    Integer.class, jti);
            return count != null && count > 0;
        } catch (Exception e) {
            // 失败开放:查询异常时视为未注销,放行请求。
            // DB 抖动不应导致全站用户被踢登录;已注销 token 的 jti 在 DB 恢复后仍会被拦截。
            log.warn("黑名单查询失败,放行请求(避免 DB 抖动踢出全站用户): jti={}", jti);
            return false;
        }
    }

    /** 定时清理已过期的黑名单条目(每 5 分钟一次)。 */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void cleanupExpired() {
        try {
            jdbcTemplate.update(
                    "DELETE FROM token_blacklist WHERE expires_at < ?",
                    LocalDateTime.now());
        } catch (Exception e) {
            log.warn("清理过期条目失败: {}", e.getMessage());
        }
    }
}
