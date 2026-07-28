package com.example.canteen.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.crypto.SecretKey;

@Configuration
public class JwtConfig {
    /** application.yml 中的默认密钥,仅用于开发环境。 */
    static final String DEFAULT_SECRET =
            "enterprise-canteen-secret-key-2026-change-me-min-32-bytes-please";

    @Value("${jwt.secret}")
    private String secret;

    private final Environment environment;

    public JwtConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public SecretKey jwtSecretKey() {
        // 启动校验:生产环境(prod profile)必须通过 JWT_SECRET 环境变量设置自定义密钥,
        // 不允许使用默认值,否则攻击者可伪造任意 token。
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) && DEFAULT_SECRET.equals(secret)) {
                throw new IllegalStateException(
                        "生产环境(prod)必须设置 JWT_SECRET 环境变量,禁止使用默认密钥");
            }
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    @Bean
    public io.jsonwebtoken.JwtParser jwtParser() {
        return Jwts.parser().verifyWith(jwtSecretKey()).build();
    }
}
