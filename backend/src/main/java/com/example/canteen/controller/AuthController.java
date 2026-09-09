package com.example.canteen.controller;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Employee;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.AuthCookieUtil;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.TokenBlacklistService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.util.Map;

/**
 * 认证相关接口(注销)。
 *
 * 注销流程:
 * 1. 从 Cookie 或 Authorization 头读取 token
 * 2. 将 token 加入黑名单(在自然过期前不可再用)
 * 3. 清除客户端 Cookie
 * 4. 员工退出:递增该员工会话代数,使其他端(token 持有的旧 sg)立即失效 → 跨端同步注销
 *
 * 注:此路径需加入 JwtAuthenticationFilter 的白名单(无需 token 即可调用),
 * 因为注销时 token 可能已过期。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenBlacklistService tokenBlacklistService;
    private final AuthCookieUtil authCookieUtil;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmployeeMapper employeeMapper;

    public AuthController(TokenBlacklistService tokenBlacklistService, AuthCookieUtil authCookieUtil,
                          JwtTokenProvider jwtTokenProvider, EmployeeMapper employeeMapper) {
        this.tokenBlacklistService = tokenBlacklistService;
        this.authCookieUtil = authCookieUtil;
        this.jwtTokenProvider = jwtTokenProvider;
        this.employeeMapper = employeeMapper;
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        // 1. 取 token:Cookie 优先,Authorization 头兜底
        String token = extractToken(request);
        if (token != null) {
            tokenBlacklistService.blacklist(token);
            // 2. 员工退出:递增会话代数,使该员工其他端的 token 立即失效(跨端同步注销)
            //    仅对仍可解析出员工身份的 token 生效;已过期/无效 token 本就不可用,跳过即可。
            try {
                Map<String, Object> claims = jwtTokenProvider.validateToken(token);
                Object roleObj = claims.get("role");
                if (roleObj instanceof Number role && role.intValue() == 0) {
                    Object idObj = claims.get("id");
                    if (idObj instanceof Number id) {
                        employeeMapper.update(null, new LambdaUpdateWrapper<Employee>()
                                .eq(Employee::getId, id.longValue())
                                .setSql("session_generation = session_generation + 1"));
                    }
                }
            } catch (Exception ignore) {
                // token 已失效/不可解析:无需递增代数
            }
        }

        // 3. 清除客户端 Cookie
        authCookieUtil.clearAuthCookie(response, request);

        return ApiResponse.success(Map.of("loggedOut", true));
    }

    private String extractToken(HttpServletRequest request) {
        // 使用 TokenExtractor 统一提取(检查所有端的 Cookie + Authorization 头)
        return com.example.canteen.security.TokenExtractor.extractToken(request);
    }
}
