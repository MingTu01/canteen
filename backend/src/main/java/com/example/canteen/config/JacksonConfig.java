package com.example.canteen.config;

import com.example.canteen.service.ImageSignService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Jackson 全局配置:自动给所有 JSON 响应中的 /uploads/ 字符串加签名。
 *
 * 原理:注册一个 String 类型的自定义序列化器,在序列化时检查字符串是否包含 /uploads/,
 * 如果包含则调 ImageSignService.sign() 附加 sig + exp 参数。
 *
 * 优势:
 * - 一处配置,全局生效:所有 Controller 返回的 image/avatar/logoUrl 等字段自动签名
 * - 无需修改任何 Service/DTO/Controller
 * - 前端直接用返回的 URL,<img src> 和 fetch 都能访问
 * - 性能开销极小:仅对包含 /uploads/ 的字符串做签名计算
 *
 * 注意:签名只影响 HTTP JSON 响应,不影响数据库存储(数据库存的是原始 URL)。
 */
@Configuration
public class JacksonConfig {

    @Autowired
    private ImageSignService imageSignService;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer imageSignCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(String.class, new ImageSignStringSerializer(imageSignService));
            // 用 modulesToInstall 追加模块,而非 modules(后者会覆盖默认模块列表,
            // 导致 JavaTimeModule 等默认模块被移除,LocalDateTime 序列化失败)
            builder.modulesToInstall(module);
        };
    }

    /**
     * 自定义 String 序列化器:包含 /uploads/ 的字符串自动加签名参数。
     */
    private static class ImageSignStringSerializer extends StdSerializer<String> {

        private final ImageSignService signService;

        protected ImageSignStringSerializer(ImageSignService signService) {
            super(String.class);
            this.signService = signService;
        }

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (value != null && value.contains("/uploads/")) {
                // 已带签名的跳过(避免重复签名)
                if (!value.contains("sig=") || !value.contains("exp=")) {
                    value = signService.sign(value);
                }
            }
            gen.writeString(value);
        }
    }
}
