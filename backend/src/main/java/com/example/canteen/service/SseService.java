package com.example.canteen.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Server-Sent Events 推送服务。
 *
 * 支持两个维度的长连接管理:
 * 1. storeId 维度(终端):管理员修改菜品/菜单后调用 broadcast 通知所有终端
 * 2. employeeId 维度(员工 H5):支付码核销后调用 sendToEmployee 通知员工刷新二维码
 *
 * 连接清理:SSE 默认超时 30 分钟,客户端断开后下次发送会触发 IOException 自动清理。
 */
@Slf4j
@Service
public class SseService {

    /** 心跳间隔(毫秒),每 25 秒发送一条注释行,防止 Nginx/代理超时断开 */
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    /** 连接超时时间(毫秒),默认 30 分钟 */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    /** storeId -> 该门店所有 SSE 连接集合(终端用) */
    private final Map<Long, Set<SseEmitter>> storeEmitters = new ConcurrentHashMap<>();

    /** employeeId -> 该员工所有 SSE 连接集合(H5 用,同一员工可能多设备登录) */
    private final Map<Long, Set<SseEmitter>> employeeEmitters = new ConcurrentHashMap<>();

    /** 心beat 定时器(延迟启动,避免容器未就绪) */
    private volatile boolean heartbeatStarted = false;

    /**
     * 创建一个 SSE 连接并注册到指定门店。
     * 客户端断开或超时后自动从集合移除。
     */
    public SseEmitter subscribe(Long storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId 不能为空");
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        Set<SseEmitter> set = storeEmitters.computeIfAbsent(storeId, k -> new CopyOnWriteArraySet<>());
        set.add(emitter);

        emitter.onCompletion(() -> {
            set.remove(emitter);
            log.debug("SSE 连接完成:storeId={}, 当前连接数={}", storeId, set.size());
        });
        emitter.onTimeout(() -> {
            set.remove(emitter);
            emitter.complete();
            log.debug("SSE 连接超时:storeId={}, 当前连接数={}", storeId, set.size());
        });
        emitter.onError((e) -> {
            set.remove(emitter);
            log.debug("SSE 连接异常:storeId={}, err={}", storeId, e.getMessage());
        });

        // 立即发一条 open 事件,让客户端知道连接已建立
        try {
            emitter.send(SseEmitter.event().name("open").data("connected"));
        } catch (IOException e) {
            set.remove(emitter);
        }

        ensureHeartbeat();
        log.debug("SSE 新订阅:storeId={}, 当前连接数={}", storeId, set.size());
        return emitter;
    }

    /**
     * 创建一个 SSE 连接并注册到指定员工。
     * 用于 H5 端接收员工维度的事件(如支付码核销通知)。
     */
    public SseEmitter subscribeByEmployee(Long employeeId) {
        if (employeeId == null) {
            throw new IllegalArgumentException("employeeId 不能为空");
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        Set<SseEmitter> set = employeeEmitters.computeIfAbsent(employeeId, k -> new CopyOnWriteArraySet<>());
        set.add(emitter);

        emitter.onCompletion(() -> {
            set.remove(emitter);
            log.debug("SSE 员工连接完成:employeeId={}, 当前连接数={}", employeeId, set.size());
        });
        emitter.onTimeout(() -> {
            set.remove(emitter);
            emitter.complete();
            log.debug("SSE 员工连接超时:employeeId={}, 当前连接数={}", employeeId, set.size());
        });
        emitter.onError((e) -> {
            set.remove(emitter);
            log.debug("SSE 员工连接异常:employeeId={}, err={}", employeeId, e.getMessage());
        });

        try {
            emitter.send(SseEmitter.event().name("open").data("connected"));
        } catch (IOException e) {
            set.remove(emitter);
        }

        ensureHeartbeat();
        log.debug("SSE 员工新订阅:employeeId={}, 当前连接数={}", employeeId, set.size());
        return emitter;
    }

    /**
     * 向指定门店的所有终端广播事件。
     * 单个终端发送失败不影响其他终端。
     *
     * @param storeId 门店 ID
     * @param eventName 事件名:dish_changed / menu_changed / dish_batch_changed
     * @param data 事件数据(会被 JSON 序列化)
     */
    public void broadcast(Long storeId, String eventName, Object data) {
        Set<SseEmitter> set = storeEmitters.get(storeId);
        if (set == null || set.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventName).data(data);
        for (SseEmitter emitter : set) {
            try {
                emitter.send(builder);
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
                log.debug("广播时清理失效连接:storeId={}, err={}", storeId, e.getMessage());
            }
        }
        log.debug("SSE 广播:storeId={}, event={}, 连接数={}", storeId, eventName, set.size());
    }

    /**
     * 向指定员工推送事件(H5 端接收)。
     * 单个连接发送失败不影响其他连接。
     *
     * @param employeeId 员工 ID
     * @param eventName 事件名:paycode_used
     * @param data 事件数据(会被 JSON 序列化)
     */
    public void sendToEmployee(Long employeeId, String eventName, Object data) {
        Set<SseEmitter> set = employeeEmitters.get(employeeId);
        if (set == null || set.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventName).data(data);
        for (SseEmitter emitter : set) {
            try {
                emitter.send(builder);
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
                log.debug("员工推送时清理失效连接:employeeId={}, err={}", employeeId, e.getMessage());
            }
        }
        log.debug("SSE 员工推送:employeeId={}, event={}, 连接数={}", employeeId, eventName, set.size());
    }

    /**
     * 向指定门店广播菜品变更(单个菜品增/改/删/上下架)。
     */
    public void broadcastDishChanged(Long storeId, Long dishId, String action) {
        broadcast(storeId, "dish_changed", Map.of(
                "dishId", dishId,
                "action", action,
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 向指定门店广播菜品批量变更(批量上下架/分类/删除)。
     */
    public void broadcastDishBatchChanged(Long storeId) {
        broadcast(storeId, "dish_batch_changed", Map.of(
                "action", "refresh",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 向指定门店广播菜单变更(新增/复制/删除菜单)。
     */
    public void broadcastMenuChanged(Long storeId, String date, Integer mealType) {
        broadcast(storeId, "menu_changed", Map.of(
                "date", date,
                "mealType", mealType == null ? 0 : mealType,
                "action", "refresh",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 启动心跳定时器:每 25 秒向所有连接发送注释行,防止代理超时断开。
     * 使用 synchronized + volatile flag 保证只启动一次。
     */
    private synchronized void ensureHeartbeat() {
        if (heartbeatStarted) return;
        heartbeatStarted = true;

        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                for (Map.Entry<Long, Set<SseEmitter>> entry : storeEmitters.entrySet()) {
                    Set<SseEmitter> set = entry.getValue();
                    for (SseEmitter emitter : set) {
                        try {
                            // 注释行:不会触发客户端 onmessage,仅保活
                            emitter.send(SseEmitter.event().comment("heartbeat"));
                        } catch (IOException | IllegalStateException e) {
                            set.remove(emitter);
                        }
                    }
                }
                // 员工维度连接也需心跳保活
                for (Map.Entry<Long, Set<SseEmitter>> entry : employeeEmitters.entrySet()) {
                    Set<SseEmitter> set = entry.getValue();
                    for (SseEmitter emitter : set) {
                        try {
                            emitter.send(SseEmitter.event().comment("heartbeat"));
                        } catch (IOException | IllegalStateException e) {
                            set.remove(emitter);
                        }
                    }
                }
            }
        }, "sse-heartbeat");
        t.setDaemon(true);
        t.start();
        log.info("SSE 心跳线程已启动,间隔 {}ms", HEARTBEAT_INTERVAL_MS);
    }

    /** 获取当前所有门店和员工的连接总数(监控用) */
    public int totalConnections() {
        int storeCount = storeEmitters.values().stream().mapToInt(Set::size).sum();
        int employeeCount = employeeEmitters.values().stream().mapToInt(Set::size).sum();
        return storeCount + employeeCount;
    }
}
