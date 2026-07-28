package com.example.canteen.controller;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.SseService;
import com.example.canteen.service.SseTicketService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * SSE 长连接接口。
 *
 * 终端启动后建立连接,接收菜品/菜单变更推送。
 *
 * 认证流程(避免 token 出现在 URL query 中):
 * 1. 终端用 Bearer token 调用 GET /api/sse/ticket 获取一次性 ticket(30s 有效)
 * 2. 终端用 ticket 建立 EventSource: /api/sse/subscribe?ticket=xxx
 * 3. SseController 校验 ticket(一次性消费),获取 storeId 后建立 SSE 连接
 *
 * /api/sse/subscribe 在 JwtAuthenticationFilter 白名单中,不要求 Bearer token,
 * 由 SseController 内部校验 ticket。
 */
@RestController
@RequestMapping("/api/sse")
public class SseController {

    private final SseService sseService;
    private final SseTicketService sseTicketService;

    public SseController(SseService sseService, SseTicketService sseTicketService) {
        this.sseService = sseService;
        this.sseTicketService = sseTicketService;
    }

    /**
     * 获取一次性 SSE ticket(需终端 Bearer token)。
     * 返回:{ ticket: "xxx", expiresIn: 30 }
     * ticket 30 秒内有效,仅可使用一次。
     */
    @GetMapping("/ticket")
    public ApiResponse<Map<String, Object>> getTicket() {
        Long storeId = SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new SecurityException("终端未绑定食堂");
        }
        String ticket = sseTicketService.createTicket(storeId);
        Map<String, Object> result = new HashMap<>();
        result.put("ticket", ticket);
        result.put("expiresIn", 30);
        return ApiResponse.success(result);
    }

    /**
     * 订阅门店变更事件流。
     * 路径:/api/sse/subscribe?ticket=xxx
     * 返回:text/event-stream
     *
     * ticket 为 GET /api/sse/ticket 返回的一次性凭证,30 秒内有效。
     * 校验通过后从 ticket 中取出 storeId,自动隔离到对应门店的事件流。
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam String ticket) {
        Long storeId = sseTicketService.validateAndConsume(ticket);
        if (storeId == null) {
            throw new SecurityException("ticket 无效或已过期");
        }
        return sseService.subscribe(storeId);
    }

    /** 监控接口:当前 SSE 连接总数(仅管理员) */
    @GetMapping("/stats")
    public ApiResponse<Integer> stats() {
        if (!SecurityContext.hasAdminLevel()) {
            return ApiResponse.error(403, "仅管理员可查看");
        }
        return ApiResponse.success(sseService.totalConnections());
    }
}
