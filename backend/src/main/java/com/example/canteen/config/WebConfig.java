package com.example.canteen.config;

import com.example.canteen.security.ImageAuthInterceptor;
import com.example.canteen.security.StoreAccessInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private ImageAuthInterceptor imageAuthInterceptor;

    /**
     * 注册拦截器:门店访问拦截器 + 图片签名校验拦截器。
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

        // 图片签名校验:所有 /uploads/** 请求必须带有效签名(sig + exp)
        registry.addInterceptor(imageAuthInterceptor)
                .addPathPatterns("/uploads/**");
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
 * 部署场景:
 * - admin-web / H5 通过 Nginx 反代同源访问,但浏览器仍会带 Origin 头
 * - 浏览器可能通过内网 IP、外网域名、反代域名访问,origin 不固定
 * - X86 终端(Tauri EXE)直接跨域调用后端 API
 *
 * 策略:
 * - 配置白名单:精确 allowedOrigins + allowCredentials(true)
 * - 未配置白名单:allowedOriginPatterns("*")(兼容终端/内网多域名)但 allowCredentials(false)。
 *   终端 token 通过 Authorization 头携带(terminal/src/api/index.ts 拦截器,不依赖 Cookie),
 *   因此 allowCredentials(false) 不影响终端;浏览器跨源 Cookie 场景需配置精确白名单。
 * - 安全性由 JwtAuthenticationFilter 保证,不依赖 CORS 做访问控制
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CorsConfig.class);

    /**
     * P0-5: 可选 CORS 白名单(逗号分隔的精确 origin 列表)。
     * 留空(默认)则放行所有 origin 但不允许携带凭证(向后兼容内网多 origin 部署)。
     * 配置示例:CORS_ALLOWED_ORIGINS=https://dm.canteen.example.com,https://admin.canteen.example.com
     */
    @Value("${cors.allowed-origins:}")
    private String allowedOriginsConfig;

    /**
     * CORS 配置:允许 localhost / 127.0.0.1 端口(admin-web 3000、H5 5174、终端 5175 等),
     * 以及 Tauri 桌面应用的 origin(Windows/Linux: http://tauri.localhost,macOS: tauri://localhost)。
     * 使用 allowedOriginPatterns 以支持端口通配(allowedOrigins 不支持)。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // API 接口 CORS
        // 部署场景:
        // - admin-web / H5 通过 nginx 反代同源访问(浏览器仍带 Origin 头,Spring CORS 需放行)
        // - 浏览器可能通过内网 IP、外网域名、反代域名访问,origin 不固定
        // - X86 终端(Tauri)直接跨域调用后端 API
        // 安全性由 JwtAuthenticationFilter 保证,不依赖 CORS 做访问控制。
        var apiMapping = registry.addMapping("/api/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        // P0-5: 若配置了精确白名单,则收紧为白名单模式 + 允许凭证
        java.util.List<String> originList = new java.util.ArrayList<>();
        if (allowedOriginsConfig != null && !allowedOriginsConfig.isBlank()) {
            for (String o : allowedOriginsConfig.split(",")) {
                String trimmed = o.trim();
                if (!trimmed.isEmpty()) {
                    originList.add(trimmed);
                }
            }
        }
        if (!originList.isEmpty()) {
            // 使用精确 allowedOrigins(非通配),仅放行白名单内的 origin
            apiMapping.allowedOrigins(originList.toArray(new String[0]))
                    .allowCredentials(true);
        } else {
            // 未配置白名单:放行所有 origin(兼容内网多 origin 部署)但不允许携带凭证。
            // 终端走 Authorization 头不受影响;如需跨源 Cookie,请配置精确白名单。
            log.warn("未配置 cors.allowed-origins,CORS 已降级为\"允许任意源但不携带凭证\"模式;生产环境建议配置精确白名单");
            apiMapping.allowedOriginPatterns("*")
                    .allowCredentials(false);
        }

        // 静态资源(头像/菜品图片)CORS
        // 终端前端运行在 http://127.0.0.1:15118,fetch 后端 /uploads/xxx.jpg 需要 CORS 头
        // 否则 imageCache.ts 中 fetch 头像图片会被浏览器拦截,无法缓存到 IndexedDB
        registry.addMapping("/uploads/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
