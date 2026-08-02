package com.example.canteen;

import jakarta.annotation.PostConstruct;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

@SpringBootApplication
@MapperScan("com.example.canteen.mapper")
@EnableScheduling
@EnableAsync
public class CanteenApplication {

    /**
     * A8 统一时区:JVM 默认时区设为 Asia/Shanghai,
     * 保证 Date / LocalDateTime.now() / Jackson 默认行为一致。
     */
    @PostConstruct
    public void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        ZoneId systemDefault = ZoneId.systemDefault();
        TimeZone tz = TimeZone.getTimeZone(systemDefault);
        TimeZone.setDefault(tz);
    }

    public static void main(String[] args) {
        SpringApplication.run(CanteenApplication.class, args);
    }
}
