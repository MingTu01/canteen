package com.example.canteen.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Token 提取工具:Cookie(auth_token, HttpOnly) 优先,Authorization: Bearer 兜底。
 *
 * 抽出为工具类便于复用与单测。Cookie 名与 JwtAuthenticationFilter.COOKIE_NAME 保持一致。
 */
public final class TokenExtractor {
    /** auth_token Cookie 名 */
    public static final String COOKIE_NAME = "auth_token";

    private TokenExtractor() {
    }

    /**
     * 取 token:Cookie 优先,Authorization 头兜底。
     *
     * 注意:已移除 query 参数 ?token=xxx 支持。
     * SSE 长连接改用一次性 ticket 机制(GET /api/sse/ticket 获取 ticket,
     * 再用 ticket 建立 EventSource),避免 token 出现在 URL query 中
     * (URL query 会进浏览器历史/Referer/代理日志)。
     *
     * @return token 字符串;无则返回 null
     */
    public static String extractToken(HttpServletRequest request) {
        // Cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE_NAME.equals(c.getName())
                        && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        // Authorization: Bearer
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
