package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.canteen.entity.Notification;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.NotificationMapper;
import com.example.canteen.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String EXPIRE_TASK_KEY = "notification:autoExpire";
    private final NotificationMapper notificationMapper;
    private final WechatNotifyService wechatNotifyService;
    private final SchedulerLockHelper schedulerLockHelper;

    public NotificationService(NotificationMapper notificationMapper,
                               WechatNotifyService wechatNotifyService,
                               SchedulerLockHelper schedulerLockHelper) {
        this.notificationMapper = notificationMapper;
        this.wechatNotifyService = wechatNotifyService;
        this.schedulerLockHelper = schedulerLockHelper;
    }

    /**
     * 管理端查询:返回门店所有通知(包括待发布、已下架),并计算 displayStatus。
     */
    public List<Notification> getNotificationsByStore(Long storeId) {
        List<Notification> list = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getStoreId, storeId)
                .orderByDesc(Notification::getCreatedAt));
        LocalDateTime now = LocalDateTime.now();
        for (Notification n : list) {
            n.setDisplayStatus(computeDisplayStatus(n, now));
        }
        return list;
    }

    /**
     * 员工端可见通知:status=1 且 已到上架时间 且 未到下架时间。
     */
    public List<Notification> getVisibleNotifications(Long storeId) {
        LocalDateTime now = LocalDateTime.now();
        List<Notification> list = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getStoreId, storeId)
                .eq(Notification::getStatus, 1)
                .and(w -> w.isNull(Notification::getPublishAt).or().le(Notification::getPublishAt, now))
                .and(w -> w.isNull(Notification::getExpireAt).or().gt(Notification::getExpireAt, now))
                .orderByDesc(Notification::getCreatedAt));
        for (Notification n : list) {
            n.setDisplayStatus("active");
        }
        return list;
    }

    public Notification getNotificationById(Long id) {
        return notificationMapper.selectById(id);
    }

    public Notification createNotification(Notification notification) {
        validateSchedule(notification);
        normalizeDefaultStatus(notification);
        notificationMapper.insert(notification);
        // 微信推送(status=1 且已到上架时间才推送,内部已校验)
        // WechatNotifyService.notifyNotificationPublished 为 @Async,不阻塞主流程
        try {
            wechatNotifyService.notifyNotificationPublished(notification);
        } catch (Exception e) {
            log.warn("微信通知推送异常: notificationId={}, error={}",
                    notification.getId(), e.getMessage());
        }
        return notification;
    }

    public Notification updateNotification(Notification notification) {
        Notification existing = notificationMapper.selectById(notification.getId());
        if (existing == null) {
            throw new BusinessException("通知不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        validateSchedule(notification);
        // 防止越权修改 storeId
        notification.setStoreId(existing.getStoreId());
        // 判断是否为"上架"操作(从 status=0 变为 status=1),需要推送微信
        boolean publishTransition = existing.getStatus() != null && existing.getStatus() == 0
                && notification.getStatus() != null && notification.getStatus() == 1;
        notificationMapper.updateById(notification);
        // 上架操作触发微信推送(避免编辑已上架通知时重复推送)
        if (publishTransition) {
            try {
                wechatNotifyService.notifyNotificationPublished(notification);
            } catch (Exception e) {
                log.warn("微信通知推送异常: notificationId={}, error={}",
                        notification.getId(), e.getMessage());
            }
        }
        return notification;
    }

    public void deleteNotification(Long id) {
        Notification existing = notificationMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("通知不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        notificationMapper.deleteById(id);
    }

    /** 校验上下架时间:publishAt < expireAt(两者都存在时) */
    private void validateSchedule(Notification n) {
        if (n.getPublishAt() != null && n.getExpireAt() != null
                && !n.getExpireAt().isAfter(n.getPublishAt())) {
            throw new BusinessException("下架时间必须晚于上架时间");
        }
    }

    private void normalizeDefaultStatus(Notification n) {
        if (n.getStatus() == null) n.setStatus(1);
        if (n.getType() == null) n.setType(1);
    }

    /**
     * 计算展示状态:
     * - pending: status=1 且 publishAt > now(待发布)
     * - active: status=1 且 已到上架时间 且 未到下架时间(已发布)
     * - expired: status=0 且 expireAt != null 且 expireAt <= now(到期自动下架)
     * - offline: status=0 且 (expireAt 为空或未到期) = 手动下架
     */
    public static String computeDisplayStatus(Notification n, LocalDateTime now) {
        if (n.getStatus() != null && n.getStatus() == 1) {
            boolean notYetPublished = n.getPublishAt() != null && n.getPublishAt().isAfter(now);
            if (notYetPublished) return "pending";
            return "active";
        }
        // status == 0
        if (n.getExpireAt() != null && !n.getExpireAt().isAfter(now)) {
            return "expired";
        }
        return "offline";
    }

    /**
     * 定时任务:每分钟扫描到期通知,自动下架(status -> 0)。
     * 兼容多租户,不依赖请求上下文。
     * 分布式锁:多实例部署时同一时刻仅一个实例执行(Redis 异常降级为直接执行)。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void autoExpireNotifications() {
        String token = java.util.UUID.randomUUID().toString();
        if (!schedulerLockHelper.tryLock(EXPIRE_TASK_KEY, token)) {
            log.debug("未获取到调度锁,跳过本次通知下架扫描");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            int affected = notificationMapper.update(new LambdaUpdateWrapper<Notification>()
                    .eq(Notification::getStatus, 1)
                    .isNotNull(Notification::getExpireAt)
                    .le(Notification::getExpireAt, now)
                    .set(Notification::getStatus, 0));
            if (affected > 0) {
                log.info("通知自动下架: 共 {} 条 (扫描时间 {})", affected, now);
            }
        } catch (Exception e) {
            log.warn("通知自动下架扫描失败: {}", e.getMessage());
        } finally {
            schedulerLockHelper.unlock(EXPIRE_TASK_KEY, token);
        }
    }
}
