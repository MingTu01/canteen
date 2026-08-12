package com.example.canteen.security;

import com.example.canteen.exception.SecurityException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 门店数据隔离拦截器。
 *
 * 设计意图:
 * - 路径中显式带 storeId 的接口(/api/xxx/store/{storeId}、/api/store/{id})自动校验权限,
 *   Controller 内可省去重复的 checkStoreAccess 调用(向后兼容,Controller 内的显式调用仍生效)。
 * - 仅在 token 已通过 JwtAuthenticationFilter 校验后生效,不会绕过登录。
 * - 超管不受限制;门店管理员仅能访问本门店;员工 token 通常不访问这些管理端点,
 *   若误访问则由 SecurityContext.checkStoreAccess 抛 403。
 *
 * 白名单接口(如 /api/system/order-config)免登录,本拦截器直接放行,
 * 避免从查询参数提取 storeId 后因无登录信息而误拒(返回 403)。
 *
 * 启用方式:在 WebMvcConfigurer 中注册到 /api/**(排除白名单)。
 */
@Component
public class StoreAccessInterceptor implements HandlerInterceptor {

    /** 路径中 /store/{数字} 形式的门店 id 提取 */
    private static final Pattern STORE_PATH_PATTERN = Pattern.compile("/store/(\\d+)");
    /** 路径中 /stores/{数字} 形式的门店 id 提取 */
    private static final Pattern STORES_PATH_PATTERN = Pattern.compile("/stores/(\\d+)");

    private final WhitelistMatcher whitelistMatcher;

    public StoreAccessInterceptor(WhitelistMatcher whitelistMatcher) {
        this.whitelistMatcher = whitelistMatcher;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检请求(OPTIONS)直接放行:预检无认证信息,无法做门店权限校验,
        // 且预检不携带业务语义,实际请求仍会经过完整校验。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();

        // 白名单接口(公开接口)直接放行:这些接口免登录(如 /api/system/order-config),
        // 若从查询参数提取到 storeId 后执行 checkStoreAccess 会因无登录信息误拒 403。
        if (whitelistMatcher.isWhitelisted(path)) {
            return true;
        }

        Long targetStoreId = extractStoreId(path, request);
        if (targetStoreId == null) {
            return true; // 无门店参数,放行给 Controller 内的显式校验
        }

        // 终端角色(role=3):必须与 token 中的 storeId 匹配,防止跨店越权
        Integer role = SecurityContext.currentRole();
        if (role != null && role == 3) {
            Long terminalStoreId = SecurityContext.currentStoreId();
            if (terminalStoreId == null || !terminalStoreId.equals(targetStoreId)) {
                throw new SecurityException("终端无权访问其他门店");
            }
            return true;
        }

        // 调用 SecurityContext 校验,失败抛 SecurityException,由全局异常处理器转为 403
        SecurityContext.checkStoreAccess(targetStoreId);
        return true;
    }

    /** 从路径或查询参数中提取 storeId */
    private Long extractStoreId(String path, HttpServletRequest request) {
        Matcher m = STORE_PATH_PATTERN.matcher(path);
        if (m.find()) {
            return parseLong(m.group(1));
        }
        m = STORES_PATH_PATTERN.matcher(path);
        if (m.find()) {
            return parseLong(m.group(1));
        }
        // 查询参数兜底:storeId / store_id
        String sp = request.getParameter("storeId");
        if (sp == null || sp.isBlank()) sp = request.getParameter("store_id");
        return parseLong(sp);
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
