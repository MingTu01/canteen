package com.example.canteen.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一管理 Cookie 的写入与清除。
 *
 * 关键设计:admin / employee / terminal 使用不同的 Cookie 名称,
 * 避免同一浏览器同时使用多端时 Cookie 互相覆盖导致 403。
 *
 * 安全策略:
 * - HttpOnly:禁止 JS 读取,防 XSS 窃取
 * - SameSite=Strict:防 CSRF
 * - Secure:仅 HTTPS 下传输(prod 强制要求,不依赖 request.isSecure())
 * - Path=/:全站可见
 * - maxAge:与 JWT 过期时间对齐
 */
@Component
public class AuthCookieUtil {
    /** 各端独立 Cookie 名称,避免同浏览器多端互相覆盖 */
    public static final String ADMIN_COOKIE_NAME = "admin_token";
    public static final String EMPLOYEE_COOKIE_NAME = "employee_token";
    public static final String TERMINAL_COOKIE_NAME = "terminal_token";
    /** 旧版兼容 Cookie 名称(TokenExtractor 兜底检查) */
    public static final String LEGACY_COOKIE_NAME = "auth_token";

    /** admin:24 小时持久 cookie(避免浏览器重启/崩溃后会话 cookie 丢失导致 403) */
    private static final int ADMIN_COOKIE_MAXAGE = 24 * 3600;
    /** employee:30 天 */
    private static final int EMPLOYEE_COOKIE_MAXAGE = 30 * 24 * 3600;
    /** terminal:365 天 */
    private static final int TERMINAL_COOKIE_MAXAGE = 365 * 24 * 3600;

    /**
     * P0-4: 注入当前 profile,生产环境强制 Cookie Secure=true,
     * 不再依赖 request.isSecure()(反代后可能返回 false)
     */
    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /** 登录成功时写入 Cookie(管理端,会话级)。 */
    public void setAuthCookie(HttpServletResponse response, String token, HttpServletRequest request) {
        setCookie(response, ADMIN_COOKIE_NAME, token, request, ADMIN_COOKIE_MAXAGE);
    }

    /** 管理员登录:会话 cookie(关闭浏览器即失效) */
    public void setAdminCookie(HttpServletResponse response, String token, HttpServletRequest request) {
        setCookie(response, ADMIN_COOKIE_NAME, token, request, ADMIN_COOKIE_MAXAGE);
    }

    /** 员工登录:30 天长期 cookie(H5 永不失效) */
    public void setEmployeeCookie(HttpServletResponse response, String token, HttpServletRequest request) {
        setCookie(response, EMPLOYEE_COOKIE_NAME, token, request, EMPLOYEE_COOKIE_MAXAGE);
    }

    /** 终端登录:365 天长期 cookie */
    public void setTerminalCookie(HttpServletResponse response, String token, HttpServletRequest request) {
        setCookie(response, TERMINAL_COOKIE_NAME, token, request, TERMINAL_COOKIE_MAXAGE);
    }

    /** 按 role 选择对应的 cookie 策略(供 Filter 滑动续期使用) */
    public void setCookieByRole(HttpServletResponse response, String token, HttpServletRequest request, Integer role) {
        int maxAge;
        String cookieName;
        switch (role == null ? -1 : role) {
            case 0 -> { maxAge = EMPLOYEE_COOKIE_MAXAGE; cookieName = EMPLOYEE_COOKIE_NAME; }
            case 3 -> { maxAge = TERMINAL_COOKIE_MAXAGE; cookieName = TERMINAL_COOKIE_NAME; }
            default -> { maxAge = ADMIN_COOKIE_MAXAGE; cookieName = ADMIN_COOKIE_NAME; }
        }
        setCookie(response, cookieName, token, request, maxAge);
    }

    private void setCookie(HttpServletResponse response, String name, String token, HttpServletRequest request, int maxAge) {
        Cookie cookie = new Cookie(name, token);
        cookie.setHttpOnly(true);
        // P0-4: 生产环境强制 Secure=true,不依赖 request.isSecure()
        // 原因:nginx 反代若未透传 X-Forwarded-Proto,request.isSecure() 返回 false,
        // 导致 Cookie 的 Secure 标志不生效,可在 HTTP 明文传输被中间人窃取。
        if (isProdProfile()) {
            cookie.setSecure(true);
        } else {
            cookie.setSecure(request.isSecure());
        }
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    /** 注销时清除所有端的 Cookie(包括旧版兼容)。 */
    public void clearAuthCookie(HttpServletResponse response, HttpServletRequest request) {
        clearCookie(response, ADMIN_COOKIE_NAME, request);
        clearCookie(response, EMPLOYEE_COOKIE_NAME, request);
        clearCookie(response, TERMINAL_COOKIE_NAME, request);
        clearCookie(response, LEGACY_COOKIE_NAME, request);
    }

    private void clearCookie(HttpServletResponse response, String name, HttpServletRequest request) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        // P0-4: 生产环境强制 Secure,与写入时保持一致
        if (isProdProfile()) {
            cookie.setSecure(true);
        } else {
            cookie.setSecure(request.isSecure());
        }
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    /** 判断是否生产 profile */
    private boolean isProdProfile() {
        return "prod".equalsIgnoreCase(activeProfile);
    }
}

