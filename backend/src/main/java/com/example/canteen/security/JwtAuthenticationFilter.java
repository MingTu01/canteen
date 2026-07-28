package com.example.canteen.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.Map;

/**
 * JWT 认证过滤器。
 *
 * 升级要点:
 * 1. 多源读 token:Cookie(auth_token, HttpOnly) 优先 → Authorization: Bearer 兜底
 * 2. 黑名单校验:注销后的 token 在过期前不可再用
 * 3. 密码修改后旧 token 失效:iat < passwordUpdatedAt 则拒绝
 * 4. 登录限流可配置:从 sys_config 读取 login_rate_limit_max_fail / lock_minutes
 *
 * 白名单接口(login/health/logout/version/actuator)直接放行。
 *
 * 职责拆分:本类只保留 Filter 主流程,具体逻辑下沉到
 * WhitelistMatcher / TokenExtractor / PasswordFreshnessValidator /
 * UserContextBinder / UnauthorizedResponseWriter / LoginRateLimiter 六个 Helper。
 */
@Component
public class JwtAuthenticationFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final WhitelistMatcher whitelistMatcher;
    private final PasswordFreshnessValidator passwordFreshnessValidator;
    private final UnauthorizedResponseWriter unauthorizedResponseWriter;
    private final LoginRateLimiter rateLimiter;

    public static final String COOKIE_NAME = "auth_token";

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   TokenBlacklistService tokenBlacklistService,
                                   WhitelistMatcher whitelistMatcher,
                                   PasswordFreshnessValidator passwordFreshnessValidator,
                                   UnauthorizedResponseWriter unauthorizedResponseWriter,
                                   LoginRateLimiter rateLimiter) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        this.whitelistMatcher = whitelistMatcher;
        this.passwordFreshnessValidator = passwordFreshnessValidator;
        this.unauthorizedResponseWriter = unauthorizedResponseWriter;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();
        // 白名单(login/health/logout/version/actuator/uploads 等)直接放行
        if (whitelistMatcher.isWhitelisted(path)) {
            chain.doFilter(request, response);
            return;
        }
        // 公开接口:GET /api/store/{id}/branding(H5/terminal 免登录获取品牌信息)
        if ("GET".equalsIgnoreCase(httpRequest.getMethod()) && whitelistMatcher.isPublicBranding(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 1. 多源取 token:Cookie 优先,Authorization 头兜底
        String token = TokenExtractor.extractToken(httpRequest);
        if (token == null) {
            unauthorizedResponseWriter.write(httpResponse, httpRequest,
                    HttpServletResponse.SC_UNAUTHORIZED, "未登录或缺少Token", true);
            return;
        }

        try {
            Map<String, Object> claims = jwtTokenProvider.validateToken(token);
            String jti = (String) claims.get("jti");

            // 2. 黑名单校验
            if (tokenBlacklistService.isBlacklisted(jti)) {
                unauthorizedResponseWriter.write(httpResponse, httpRequest,
                        HttpServletResponse.SC_UNAUTHORIZED, "Token已注销,请重新登录", true);
                return;
            }

            // 3. 密码修改后旧 token 失效校验
            Integer role = toInt(claims.get("role"));
            Long userId = toLong(claims.get("id"));
            Long iatEpoch = toIatEpoch(claims.get("iat"));
            String invalidReason = passwordFreshnessValidator.checkPasswordFreshness(userId, role, iatEpoch);
            if (invalidReason != null) {
                unauthorizedResponseWriter.write(httpResponse, httpRequest,
                        HttpServletResponse.SC_UNAUTHORIZED, invalidReason, true);
                return;
            }

            // 4. 写入 request attribute(按角色只写对应属性,避免角色混淆)
            UserContextBinder.bind(httpRequest, userId, userId,
                    toLong(claims.get("storeId")), role);
        } catch (Exception e) {
            log.warn("JWT 认证失败:path={}, msg={}", path, e.getMessage());
            unauthorizedResponseWriter.write(httpResponse, httpRequest,
                    HttpServletResponse.SC_UNAUTHORIZED, "Token无效或已过期", true);
            return;
        }

        chain.doFilter(request, response);
    }

    /** 暴露限流器给 AdminService / Controller 等业务层使用 */
    public LoginRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    private static Integer toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    private static Long toLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }

    private static Long toIatEpoch(Object o) {
        if (o == null) return null;
        if (o instanceof Date d) return d.toInstant().getEpochSecond();
        if (o instanceof Number n) return n.longValue();
        return null;
    }
}
