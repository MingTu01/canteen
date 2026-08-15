package com.example.canteen.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 订单状态定时任务:把超过就餐时段未核销的订单自动标记为"未就餐"(status=4)。
 *
 * 触发规则:每分钟扫描一次 status=1 且日期<=今天的订单,
 * 若该订单对应餐次的就餐时段(dining_time_slot.endTime)已过,则标记为 MISSED(4)。
 *
 * 设计要点:
 * - 每分钟执行:轻量(订单量不大),且能在餐次结束后 1 分钟内完成标记
 * - 幂等:仅 status=1 才更新,重复执行无副作用
 * - 容错:单次扫描异常不影响下次执行
 * - 分布式锁:多实例部署时同一时刻仅一个实例执行(Redis 异常降级为直接执行)
 */
@Slf4j
@Service
public class OrderStatusScheduler {
    private static final String TASK_KEY = "orderStatus:markExpired";

    private final OrderService orderService;
    private final SchedulerLockHelper schedulerLockHelper;

    public OrderStatusScheduler(OrderService orderService, SchedulerLockHelper schedulerLockHelper) {
        this.orderService = orderService;
        this.schedulerLockHelper = schedulerLockHelper;
    }

    /**
     * 每分钟扫描过期未核销订单,标记为未就餐。
     * fixedDelay:上次执行结束后等 60 秒再执行下一次,避免任务堆积
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void markExpiredOrders() {
        String token = UUID.randomUUID().toString();
        if (!schedulerLockHelper.tryLock(TASK_KEY, token)) {
            log.debug("[OrderStatusScheduler] 未获取到调度锁,跳过本次执行");
            return;
        }
        try {
            int marked = orderService.markExpiredOrdersAsMissed();
            if (marked > 0) {
                log.info("[OrderStatusScheduler] 标记 {} 单订单为未就餐", marked);
            }
        } catch (Exception e) {
            log.error("[OrderStatusScheduler] 标记未就餐订单异常: {}", e.getMessage(), e);
        } finally {
            schedulerLockHelper.unlock(TASK_KEY, token);
        }
    }
}
