package com.example.canteen.config;

import com.example.canteen.security.StoreAccessInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 - 拦截器与静态资源映射(全 profile 生效)。
 *
 * StoreAccessInterceptor 是多租户隔离的纵深防线,必须在所有环境注册。
 *
 * /uploads/** 资源映射说明:
 * 之前依赖 application.yml 的 spring.web.resources.static-locations: file:./uploads/
 * 但 static-locations 只是给默认 /** 资源处理器添加搜索目录,不会创建 /uploads/** URL 前缀映射,
 * 请求 /uploads/foo.jpg 时 Spring Boot 会在 ./uploads/ 下找 uploads/foo.jpg,导致 404。
 * 这里显式注册 /uploads/** → file:./uploads/ 资源处理器,直接映射 URL 前缀到磁盘目录。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private StoreAccessInterceptor storeAccessInterceptor;

    /**
     * 注册门店访问拦截器:自动校验路径中带 storeId 的接口。
     * 排除登录/健康检查/系统版本/注销等公共端点。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(storeAccessInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/admin/login",
                        "/api/employee/login",
                        "/api/employee/phone-login",
                        "/api/terminal/**",
                        "/api/auth/**",
                        "/api/system/health",
                        "/api/system/version",
                        "/api/store/public-list",
                        // SSE subscribe 改用 ticket 认证,不经过 JwtAuthenticationFilter
                        "/api/sse/**",
                        // 公开接口:H5/terminal 免登录获取品牌信息
                        "/api/store/*/branding",
                        // 测试员工列表(所有环境可用,用于登录页模拟刷卡)
                        "/api/test/employees",
                        "/actuator/**"
                );
    }

    /**
     * 显式注册 /uploads/** 静态资源映射:
     * URL 前缀 /uploads/ 直接映射到工作目录下的 ./uploads/ 磁盘目录,
     * FileController 上传的图片通过 /uploads/xxx.jpg 即可访问。
     *
     * 注意:必须在 WebMvcConfigurer 中注册,而不是依赖 static-locations,
     * 因为后者不会创建 URL 前缀映射(详见类注释)。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:./uploads/")
                .setCachePeriod(60 * 60 * 24 * 365); // 1 年强缓存(文件名 UUID + ?v=mtime 保证失效)
    }
}

/**
 * CORS 配置(仅 dev profile 生效)。
 *
 * 生产环境通过 Nginx 反代同源访问,无需后端 CORS。
 */
@Configuration
@Profile("dev")
class DevCorsConfig implements WebMvcConfigurer {

    /**
     * CORS 配置:开发环境允许所有 localhost / 127.0.0.1 端口(admin-web 3000、H5 5174、终端 5175 等)。
     * 使用 allowedOriginPatterns 以支持端口通配(allowedOrigins 不支持)。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://localhost:*",
                        "https://127.0.0.1:*"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
