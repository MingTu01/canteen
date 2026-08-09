package com.example.canteen.security;

import com.example.canteen.service.ImageSignService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 图片访问拦截器:校验 /uploads/** 请求的签名参数(sig + exp)。
 *
 * 配合 ImageSignService 使用:
 * - 后端返回的图片 URL 已带签名参数,<img src> 可直接访问
 * - 外部拿到链接(无签名参数)访问会被 403 拒绝
 * - 签名有效期 7 天,过期后需重新签名
 *
 * 注意:拦截器只校验签名,不校验登录态(签名本身就是访问凭证)。
 * 这样 <img src="/uploads/xxx.jpg?sig=...&exp=..."> 无需带 Authorization header。
 */
@Component
public class ImageAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private ImageSignService imageSignService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();  // 纯路径,不含 query
        String sig = request.getParameter("sig");
        String exp = request.getParameter("exp");

        if (imageSignService.verify(uri, sig, exp)) {
            return true;
        }

        // 签名无效或过期 → 403
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"图片访问签名无效或已过期\"}");
        return false;
    }
}
