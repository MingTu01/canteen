package com.example.canteen.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Token 提取工具:Authorization: Bearer 优先,Cookie(auth_token, HttpOnly) 兜底。
 *
 * 优先级说明:同一浏览器中 admin-web(Cookie)与终端/H5(Authorization 头)可能共存,
 * Cookie 不区分端口,会导致终端请求误用 admin-web 的 Cookie token。
 * Authorization 头由前端主动设置,语义更明确,应优先使用。
 *
 * 抽出为工具类便于复用与单测。Cookie 名与 JwtAuthenticationFilter.COOKIE_NAME 保持一致。
 */
public final class TokenExtractor {
    /** auth_token Cookie 名 */
    public static final String COOKIE_NAME = "auth_token";

    private TokenExtractor() {
    }

    /**
     * 取 token:Authorization 头优先,Cookie 兜底。
     *
     * 注意:已移除 query 参数 ?token=xxx 支持。
     * SSE 长连接改用一次性 ticket 机制(GET /api/sse/ticket 获取 ticket,
     * 再用 ticket 建立 EventSource),避免 token 出现在 URL query 中
     * (URL query 会进浏览器历史/Referer/代理日志)。
     *
     * @return token 字符串;无则返回 null
     */
    public static String extractToken(HttpServletRequest request) {
        // Authorization: Bearer 优先(终端/H5 通过 header 主动设置 token)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // Cookie 兜底(admin-web 使用 HttpOnly Cookie,不通过 header 发送)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE_NAME.equals(c.getName())
                        && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
