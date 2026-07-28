package com.example.canteen.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 401 响应写入器:统一未认证响应格式,并可选清除 auth_token Cookie。
 *
 * 用 ObjectMapper 替代手写 JSON 拼接,避免转义错误。
 * Cookie 清除逻辑复用 AuthCookieUtil。
 */
@Component
public class UnauthorizedResponseWriter {
    private final ObjectMapper objectMapper;
    private final AuthCookieUtil authCookieUtil;

    public UnauthorizedResponseWriter(ObjectMapper objectMapper, AuthCookieUtil authCookieUtil) {
        this.objectMapper = objectMapper;
        this.authCookieUtil = authCookieUtil;
    }

    /**
     * 写入未认证响应。
     *
     * @param response    HTTP 响应
     * @param request     HTTP 请求(用于 Cookie 路径推导)
     * @param status      HTTP 状态码(通常 401)
     * @param message     错误消息
     * @param clearCookie 是否清除 auth_token Cookie
     */
    public void write(HttpServletResponse response, HttpServletRequest request,
                      int status, String message, boolean clearCookie) throws IOException {
        if (clearCookie) {
            // token 无效/过期/黑名单时清除客户端 Cookie,避免客户端持有无效 token 反复重试
            authCookieUtil.clearAuthCookie(response, request);
        }
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("message", message == null ? "" : message);
        body.put("data", null);
        body.put("timestamp", System.currentTimeMillis());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
