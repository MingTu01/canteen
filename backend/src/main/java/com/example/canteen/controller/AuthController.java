package com.example.canteen.controller;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.security.AuthCookieUtil;
import com.example.canteen.security.TokenBlacklistService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证相关接口(注销)。
 *
 * 注销流程:
 * 1. 从 Cookie 或 Authorization 头读取 token
 * 2. 将 token 加入黑名单(在自然过期前不可再用)
 * 3. 清除客户端 Cookie
 *
 * 注:此路径需加入 JwtAuthenticationFilter 的白名单(无需 token 即可调用),
 * 因为注销时 token 可能已过期。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenBlacklistService tokenBlacklistService;
    private final AuthCookieUtil authCookieUtil;

    public AuthController(TokenBlacklistService tokenBlacklistService, AuthCookieUtil authCookieUtil) {
        this.tokenBlacklistService = tokenBlacklistService;
        this.authCookieUtil = authCookieUtil;
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        // 1. 取 token:Cookie 优先,Authorization 头兜底
        String token = extractToken(request);
        if (token != null) {
            tokenBlacklistService.blacklist(token);
        }

        // 2. 清除客户端 Cookie
        authCookieUtil.clearAuthCookie(response, request);

        return ApiResponse.success(Map.of("loggedOut", true));
    }

    private String extractToken(HttpServletRequest request) {
        // Cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (AuthCookieUtil.COOKIE_NAME.equals(c.getName())
                        && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        // Authorization 头
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
