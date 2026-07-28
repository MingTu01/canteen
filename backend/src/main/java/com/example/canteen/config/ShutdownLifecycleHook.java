package com.example.canteen.config;

import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Spring 容器停机时,优先把 GracefulShutdownHandler 标记为"停机中"。
 *
 * SmartLifecycle 的 phase 越小越早停,我们用 Ordered.HIGHEST_PRECEDENCE 让此 hook 先于
 * Tomcat connector 关闭执行,从而让 ShutdownFilter 拒绝新请求,已有请求继续处理完。
 */
@Configuration
public class ShutdownLifecycleHook implements SmartLifecycle {

    private volatile boolean running = false;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        // 标记进入停机状态:ShutdownFilter 会拒绝新请求
        GracefulShutdownHandler.markShuttingDown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
