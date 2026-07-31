package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Notification;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<List<Notification>> getNotificationsByStore(@PathVariable Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return ApiResponse.success(notificationService.getNotificationsByStore(storeId));
    }

    @OperationLog("创建通知")
    @PostMapping
    public ApiResponse<Notification> createNotification(@RequestBody Notification notification) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        SecurityContext.checkStoreAccess(notification.getStoreId());
        return ApiResponse.success(notificationService.createNotification(notification));
    }

    @OperationLog("更新通知")
    @PutMapping("/{id}")
    public ApiResponse<Notification> updateNotification(@PathVariable Long id, @RequestBody Notification notification) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        notification.setId(id);
        return ApiResponse.success(notificationService.updateNotification(notification));
    }

    /**
     * 上架/下架通知(仅修改 status 字段)
     * PUT /api/notification/{id}/status
     */
    @OperationLog("通知上下架")
    @PutMapping("/{id}/status")
    public ApiResponse<Notification> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        if (SecurityContext.currentRole() == null) {
            throw new com.example.canteen.exception.SecurityException("请先登录");
        }
        Notification notification = notificationService.getNotificationById(id);
        if (notification == null) {
            throw new com.example.canteen.exception.SecurityException("通知不存在");
        }
        SecurityContext.checkStoreAccess(notification.getStoreId());
        notification.setStatus(status);
        return ApiResponse.success(notificationService.updateNotification(notification));
    }

    @OperationLog("删除通知")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteNotification(@PathVariable Long id) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        notificationService.deleteNotification(id);
        return ApiResponse.success(null);
    }
}
