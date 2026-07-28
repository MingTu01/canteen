package com.example.canteen.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 安全响应头过滤器。
 *
 * 添加项目通用的安全响应头:
 * - X-Frame-Options: SAMEORIGIN  防 clickjacking
 * - X-Content-Type-Options: nosniff  防 MIME 嗅探
 * - Referrer-Policy: strict-origin-when-cross-origin  防 referrer 泄漏
 * - Cache-Control: no-store  对 API 响应不缓存(防敏感数据缓存)
 * - Permissions-Policy: 限制摄像头/麦克风/地理
 *
 * 注意:HSTS 头只在 HTTPS 下生效,生产环境 nginx 反代会自动加,这里不重复。
 * CSP 头因前后端混合复杂,由 nginx 在前端响应中添加更合适。
 *
 * 例外:GET /api/store/{id}/branding 是公开品牌信息接口,需要 ETag + 304 缓存,
 * 因此该接口跳过 Cache-Control: no-store(由 ShallowEtagHeaderFilter 添加 ETag)。
 * 公开品牌接口的路径匹配规则统一复用 WhitelistMatcher,避免与 JwtAuthenticationFilter 不一致。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter implements Filter {

    private final WhitelistMatcher whitelistMatcher;

    public SecurityHeadersFilter(WhitelistMatcher whitelistMatcher) {
        this.whitelistMatcher = whitelistMatcher;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        httpResponse.setHeader("X-Frame-Options", "SAMEORIGIN");
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        httpResponse.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        // 隐藏技术栈
        httpResponse.setHeader("X-Powered-By", "");

        // 公开品牌信息接口允许 ETag 缓存,不设置 no-store(否则 ShallowEtagHeaderFilter 会跳过 ETag 生成)
        if (!isPublicBrandingRequest(httpRequest)) {
            httpResponse.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        }

        chain.doFilter(request, response);
    }

    /**
     * 判断是否为公开品牌信息 GET 请求(路径形如 /api/store/{id}/branding)。
     * 路径匹配复用 WhitelistMatcher,与 JwtAuthenticationFilter 保持一致。
     */
    private boolean isPublicBrandingRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return whitelistMatcher.isPublicBranding(request.getRequestURI());
    }
}
