package com.example.canteen.config;

import com.example.canteen.security.StoreAccessInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
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
 * CORS 配置(全 profile 生效)。
 *
 * - admin-web / H5 通过 Nginx 反代同源访问,CORS 不会触发
 * - X86 终端(Tauri EXE)直接跨域调用后端 API,需要允许 Tauri origin
 * - dev 环境的 localhost:* 端口用于 vite dev server 调试
 *
 * Tauri origin 说明:
 * - Windows / Linux: http://tauri.localhost
 * - macOS: tauri://localhost
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

    /**
     * CORS 配置:允许 localhost / 127.0.0.1 端口(admin-web 3000、H5 5174、终端 5175 等),
     * 以及 Tauri 桌面应用的 origin(Windows/Linux: http://tauri.localhost,macOS: tauri://localhost)。
     * 使用 allowedOriginPatterns 以支持端口通配(allowedOrigins 不支持)。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // API 接口 CORS
        // 内网部署场景:浏览器可能通过内网 IP(如 http://172.19.171.4:18080)访问,
        // nginx 反代到后端虽然同源,但浏览器仍会带 Origin 头,Spring CORS 需放行。
        // 生产环境前端经 nginx 反代同源访问,跨域仅来自 X86 终端(Tauri)和 dev 调试。
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://localhost:*",
                        "https://127.0.0.1:*",
                        // 兜底:浏览器对默认端口(80/443)会省略端口号,
                        // 导致 origin 为 http://127.0.0.1(不带端口),
                        // 与 http://127.0.0.1:* 不匹配(后者要求带 :port)
                        "http://localhost",
                        "http://127.0.0.1",
                        "https://localhost",
                        "https://127.0.0.1",
                        // 内网 IP 访问(浏览器通过 http://172.19.x.x:18080 访问 admin-web)
                        "http://*:*",
                        "https://*:*",
                        // Tauri 桌面应用 origin(X86 终端 EXE)
                        "http://tauri.localhost",
                        "https://tauri.localhost",
                        "tauri://localhost"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
        // 静态资源(头像/菜品图片)CORS
        // 终端前端运行在 http://127.0.0.1:1287,fetch 后端 /uploads/xxx.jpg 需要 CORS 头
        // 否则 imageCache.ts 中 fetch 头像图片会被浏览器拦截,无法缓存到 IndexedDB
        registry.addMapping("/uploads/**")
                .allowedOriginPatterns(
                        "http://*:*",
                        "https://*:*",
                        "http://tauri.localhost",
                        "https://tauri.localhost",
                        "tauri://localhost"
                )
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
