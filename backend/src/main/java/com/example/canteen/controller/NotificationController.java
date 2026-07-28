package com.example.canteen.controller;

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

    @PostMapping
    public ApiResponse<Notification> createNotification(@RequestBody Notification notification) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        SecurityContext.checkStoreAccess(notification.getStoreId());
        return ApiResponse.success(notificationService.createNotification(notification));
    }

    @PutMapping("/{id}")
    public ApiResponse<Notification> updateNotification(@PathVariable Long id, @RequestBody Notification notification) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        notification.setId(id);
        return ApiResponse.success(notificationService.updateNotification(notification));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteNotification(@PathVariable Long id) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        notificationService.deleteNotification(id);
        return ApiResponse.success(null);
    }
}
