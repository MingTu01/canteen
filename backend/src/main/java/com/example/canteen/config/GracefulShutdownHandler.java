package com.example.canteen.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优雅停机处理器。
 *
 * - 接收到 SIGTERM 后(由 Spring Boot Actuator 的 graceful shutdown 触发),
 *   进入"停机中"状态,新请求立即返回 503,正在处理的请求继续完成。
 * - /api/system/health 在停机期间返回 OUT_OF_SERVICE,以便负载均衡器摘除节点。
 *
 * 启用方式:application.yml 设置
 *   server.shutdown: graceful
 *   spring.lifecycle.timeout-per-shutdown-phase: 30s
 */
@Configuration
public class GracefulShutdownHandler {

    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);

    public static boolean isShuttingDown() {
        return SHUTTING_DOWN.get();
    }

    public static void markShuttingDown() {
        SHUTTING_DOWN.set(true);
    }

    /**
     * 注册停机拦截 Filter,优先级低于 JwtAuthenticationFilter(让登录/健康检查仍可放行)。
     */
    @Bean
    public FilterRegistrationBean<Filter> shutdownFilter() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ShutdownFilter());
        bean.addUrlPatterns("/api/*");
        bean.setOrder(2); // JwtAuthenticationFilter order=1,这里 order=2
        return bean;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    static class ShutdownFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (isShuttingDown()) {
                HttpServletRequest req = (HttpServletRequest) request;
                String path = req.getRequestURI();
                // 健康检查放行,但返回 503,以便负载均衡器摘除节点
                if (path.startsWith("/api/system/health")) {
                    HttpServletResponse hr = (HttpServletResponse) response;
                    hr.setStatus(503);
                    hr.setContentType("application/json;charset=UTF-8");
                    hr.getWriter().write("{\"code\":503,\"message\":\"服务停机中\",\"data\":{\"status\":\"OUT_OF_SERVICE\"},\"timestamp\":" + System.currentTimeMillis() + "}");
                    return;
                }
                // 其他业务接口直接 503 拒绝新请求
                HttpServletResponse hr = (HttpServletResponse) response;
                hr.setStatus(503);
                hr.setContentType("application/json;charset=UTF-8");
                hr.getWriter().write("{\"code\":503,\"message\":\"服务停机中,请稍后再试\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
