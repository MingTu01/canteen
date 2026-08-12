package com.example.canteen.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 一次性 Ticket 管理。
 *
 * 终端用 Bearer token 调用 /api/sse/ticket 获取一次性 ticket(30s 有效),
 * 然后用 ticket 建立 EventSource 连接,避免 token 出现在 URL query 中
 * (URL query 会进浏览器历史/Referer/代理日志)。
 *
 * ticket 一次性使用,校验后立即删除。懒清理过期 ticket,无需 @EnableScheduling。
 */
@Service
public class SseTicketService {
    private static final long TICKET_TTL_MS = 30_000; // 30 秒

    private final SecureRandom random = new SecureRandom();
    private final Map<String, TicketEntry> tickets = new ConcurrentHashMap<>();

    private static class TicketEntry {
        final Long storeId;
        final Long employeeId; // H5 员工订阅时非空,终端订阅时为 null
        final long expireAt;
        TicketEntry(Long storeId, Long employeeId, long expireAt) {
            this.storeId = storeId;
            this.employeeId = employeeId;
            this.expireAt = expireAt;
        }
    }

    /** ticket 校验结果(包含 storeId 和 employeeId) */
    public static class TicketResult {
        public final Long storeId;
        public final Long employeeId;
        TicketResult(Long storeId, Long employeeId) {
            this.storeId = storeId;
            this.employeeId = employeeId;
        }
    }

    /** 创建一次性 ticket,绑定 storeId(终端用),30 秒过期 */
    public String createTicket(Long storeId) {
        return createTicketInternal(storeId, null);
    }

    /** 创建一次性 ticket,绑定 employeeId + storeId(H5 员工用),30 秒过期 */
    public String createEmployeeTicket(Long employeeId, Long storeId) {
        return createTicketInternal(storeId, employeeId);
    }

    private String createTicketInternal(Long storeId, Long employeeId) {
        cleanupExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        String ticket = sb.toString();
        tickets.put(ticket, new TicketEntry(storeId, employeeId, System.currentTimeMillis() + TICKET_TTL_MS));
        return ticket;
    }

    /** 校验并消费 ticket(一次性,校验后立即删除)。返回 storeId,无效返回 null(终端用) */
    public Long validateAndConsume(String ticket) {
        TicketResult r = validateAndConsumeFull(ticket);
        return r == null ? null : r.storeId;
    }

    /** 校验并消费 ticket,返回完整结果(含 storeId + employeeId)。H5 员工订阅用 */
    public TicketResult validateAndConsumeFull(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        TicketEntry entry = tickets.remove(ticket);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expireAt) return null;
        return new TicketResult(entry.storeId, entry.employeeId);
    }

    /** 懒清理过期 ticket(在 createTicket 时触发) */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(e -> now > e.getValue().expireAt);
    }
}
