package com.example.canteen.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 定时任务分布式锁(基于 Redis SETNX + Lua compare-and-delete)。
 *
 * - tryLock(taskKey, token):SETNX "sched:lock:"+taskKey = token, TTL 120 秒,
 *   防止多实例部署时同一任务并发执行;
 * - unlock(taskKey, token):Lua 脚本仅当值等于自己的 token 才删除,防止误删他人锁;
 * - Redis 未装配(dev profile 排除自动装配)或异常时降级为直接执行(返回 true),
 *   单实例部署不受影响。
 */
@Component
public class SchedulerLockHelper {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockHelper.class);

    private static final String KEY_PREFIX = "sched:lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(120);

    /** 仅当锁值等于自己的 token 才删除(CAS 删除,防误删他人锁) */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;

    public SchedulerLockHelper(ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
    }

    /**
     * 尝试获取任务锁。
     * @return true=获得锁(或 Redis 不可用降级放行);false=其他实例持有锁,应跳过本次执行
     */
    public boolean tryLock(String taskKey, String token) {
        try {
            StringRedisTemplate tpl = stringRedisTemplateProvider.getIfAvailable();
            if (tpl == null) {
                // Redis 未装配(如 dev):单实例直接执行
                return true;
            }
            Boolean ok = tpl.opsForValue().setIfAbsent(KEY_PREFIX + taskKey, token, LOCK_TTL);
            return ok == null || ok;
        } catch (Exception e) {
            // Redis 异常时降级为直接执行,避免故障期间任务完全不跑
            log.warn("获取调度锁异常,降级为直接执行 taskKey={}: {}", taskKey, e.getMessage());
            return true;
        }
    }

    /** 释放任务锁:仅当锁值仍为自己的 token 才删除,防止误删他人锁 */
    public void unlock(String taskKey, String token) {
        try {
            StringRedisTemplate tpl = stringRedisTemplateProvider.getIfAvailable();
            if (tpl == null) {
                return;
            }
            tpl.execute(UNLOCK_SCRIPT, List.of(KEY_PREFIX + taskKey), token);
        } catch (Exception e) {
            // 释放失败无碍:锁有 TTL 会自动过期
            log.debug("释放调度锁异常 taskKey={}: {}", taskKey, e.getMessage());
        }
    }
}
