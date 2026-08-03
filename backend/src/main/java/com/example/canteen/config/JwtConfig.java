package com.example.canteen.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Configuration
public class JwtConfig {
    /** application.yml 中的默认密钥,仅用于开发环境。 */
    static final String DEFAULT_SECRET =
            "enterprise-canteen-secret-key-2026-change-me-min-32-bytes-please";

    /**
     * 弱密钥黑名单:即使通过环境变量传入,若命中黑名单仍拒绝启动。
     * 防止运维直接复制示例密钥或使用已知弱密钥。
     */
    private static final Set<String> WEAK_SECRETS = Set.of(
            DEFAULT_SECRET,
            "canteen-jwt-secret-key-2026-please-change-in-production",
            "change-me",
            "secret",
            "jwt-secret",
            "canteen2026",
            "please-change-in-production",
            "your-secret-key-here"
    );

    @Value("${jwt.secret}")
    private String secret;

    private final Environment environment;

    public JwtConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public SecretKey jwtSecretKey() {
        // 启动校验:生产环境(prod profile)必须通过 JWT_SECRET 环境变量设置自定义密钥,
        // 不允许使用默认值或弱密钥,否则攻击者可伪造任意 token。
        boolean isProd = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                isProd = true;
                break;
            }
        }

        if (isProd) {
            // 1. 禁止使用默认密钥
            if (DEFAULT_SECRET.equals(secret)) {
                throw new IllegalStateException(
                        "生产环境(prod)必须设置 JWT_SECRET 环境变量,禁止使用默认密钥");
            }
            // 2. 禁止使用弱密钥黑名单中的值
            if (WEAK_SECRETS.contains(secret)) {
                throw new IllegalStateException(
                        "生产环境(prod)禁止使用弱 JWT 密钥,请生成 64 字节随机字符串:openssl rand -hex 64");
            }
            // 3. 密钥长度至少 32 字节(HS256 安全要求)
            int secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
            if (secretBytes < 32) {
                throw new IllegalStateException(
                        "生产环境(prod)JWT 密钥至少 32 字节,当前 " + secretBytes + " 字节");
            }
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    @Bean
    public io.jsonwebtoken.JwtParser jwtParser() {
        return Jwts.parser().verifyWith(jwtSecretKey()).build();
    }
}
