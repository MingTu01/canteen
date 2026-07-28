package com.example.canteen.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * 使用 StringRedisSerializer 作 key,JSON 作 value(支持 Java 8 时间类型)。
 * 开发环境通过 application-dev.yml 排除 Redis 自动装配,本类不生效。
 */
@Configuration
@ConditionalOnClass(RedisConnectionFactory.class)
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory,
            @Autowired(required = false) ObjectMapper objectMapper) {
        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(factory);

        // 复用 Spring Boot 默认 ObjectMapper,补注册 JavaTimeModule 支持日期
        ObjectMapper om = objectMapper != null ? objectMapper.copy() : new ObjectMapper();
        om.registerModule(new JavaTimeModule());

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(om);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        tpl.setKeySerializer(stringSerializer);
        tpl.setHashKeySerializer(stringSerializer);
        tpl.setValueSerializer(jsonSerializer);
        tpl.setHashValueSerializer(jsonSerializer);
        tpl.afterPropertiesSet();
        return tpl;
    }
}
