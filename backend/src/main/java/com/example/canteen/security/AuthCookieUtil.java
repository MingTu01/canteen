package com.example.canteen.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一管理 auth_token Cookie 的写入与清除。
 *
 * 安全策略:
 * - HttpOnly:禁止 JS 读取,防 XSS 窃取
 * - SameSite=Strict:防 CSRF
 * - Secure:仅 HTTPS 下传输(prod 强制要求)
 * - Path=/:全站可见
 * - maxAge:与 JWT 过期时间对齐(从 jwt.expiration 配置推导)
 */
@Component
public class AuthCookieUtil {
    public static final String COOKIE_NAME = JwtAuthenticationFilter.COOKIE_NAME;

    private final int maxAgeSeconds;

    public AuthCookieUtil(@Value("${jwt.expiration}") long expirationMs) {
        this.maxAgeSeconds = (int) (expirationMs / 1000);
    }

    /** 登录成功时写入 auth_token Cookie。 */
    public void setAuthCookie(HttpServletResponse response, String token, HttpServletRequest request) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        // SameSite=Strict 防 CSRF;Servlet 6 的 Cookie 支持 setAttribute
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    /** 注销时清除 auth_token Cookie。 */
    public void clearAuthCookie(HttpServletResponse response, HttpServletRequest request) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }
}
