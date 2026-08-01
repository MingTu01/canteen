package com.example.canteen.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Token 提取工具:Authorization: Bearer 优先,Cookie 兜底。
 *
 * Cookie 按端隔离(admin_token / employee_token / terminal_token),
 * 避免同一浏览器多端 Cookie 互相覆盖。
 * LEGACY_COOKIE_NAME(auth_token)为旧版兼容,兜底检查。
 */
public final class TokenExtractor {
    /** 所有需要检查的 Cookie 名称,按优先级排列 */
    private static final String[] COOKIE_NAMES = {
            AuthCookieUtil.ADMIN_COOKIE_NAME,
            AuthCookieUtil.EMPLOYEE_COOKIE_NAME,
            AuthCookieUtil.TERMINAL_COOKIE_NAME,
            AuthCookieUtil.LEGACY_COOKIE_NAME,
    };

    private TokenExtractor() {
    }

    /**
     * 取 token:Authorization 头优先,Cookie 兜底。
     *
     * @return token 字符串;无则返回 null
     */
    public static String extractToken(HttpServletRequest request) {
        // Authorization: Bearer 优先
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // Cookie 兜底:依次检查各端 Cookie 名称
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (String name : COOKIE_NAMES) {
                for (Cookie c : cookies) {
                    if (name.equals(c.getName())
                            && c.getValue() != null && !c.getValue().isBlank()) {
                        return c.getValue();
                    }
                }
            }
        }
        return null;
    }
}
