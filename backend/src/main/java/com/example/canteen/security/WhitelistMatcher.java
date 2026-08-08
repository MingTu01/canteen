package com.example.canteen.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 白名单与公开接口路径匹配器。
 *
 * 统一 JwtAuthenticationFilter 与 SecurityHeadersFilter 的白名单/公开接口判断逻辑,
 * 避免规则散落多处导致不一致。
 *
 * - WHITELIST_EXACT:精确匹配(login/health/logout/version/terminal/bind/store/public-list)
 * - WHITELIST_PREFIX:前缀匹配(actuator/h2-console/uploads/)
 * - PUBLIC_BRANDING_PATTERN:GET /api/store/{id}/branding(H5/terminal 免登录获取品牌信息)
 */
@Component
public class WhitelistMatcher {
    /** 精确匹配的白名单路径 */
    public static final String[] WHITELIST_EXACT = {
            "/api/admin/login",
            "/api/employee/login",
            "/api/employee/phone-login",
            "/api/terminal/bind",
            "/api/store/public-list",
            "/api/system/health",
            "/api/system/version",
            "/api/system/time",
            "/api/system/order-config",
            "/api/auth/logout",
            // SSE subscribe 改用一次性 ticket 认证(由 SseController 内部校验 ticket),
            // 不再需要 Bearer token,避免 token 出现在 URL query 中
            "/api/sse/subscribe",
    };

    /** 前缀匹配的白名单路径 */
    public static final String[] WHITELIST_PREFIX = {
            "/actuator",
            "/h2-console",
            // 上传的静态图片资源:/uploads/xxx.jpg
            "/uploads/",
            // 微信登录三步接口(auth-url / login / bind),未登录态调用
            "/api/employee/wechat/",
            // 微信公众号消息/事件回调(微信服务器推送,不带JWT),GET接入校验+POST事件
            "/api/wechat/callback",
    };

    /** 公开接口正则:GET /api/store/{id}/branding */
    public static final Pattern PUBLIC_BRANDING_PATTERN =
            Pattern.compile("^/api/store/\\d+/branding$");

    /** 判断是否命中白名单(精确或前缀) */
    public boolean isWhitelisted(String path) {
        if (path == null) return false;
        for (String p : WHITELIST_EXACT) {
            if (path.equals(p)) return true;
        }
        for (String p : WHITELIST_PREFIX) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    /** 判断是否为公开品牌信息接口路径(不限方法,方法由调用方自行校验) */
    public boolean isPublicBranding(String path) {
        return path != null && PUBLIC_BRANDING_PATTERN.matcher(path).matches();
    }
}
