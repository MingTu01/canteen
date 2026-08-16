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

    /**
     * 管理端查询:返回门店全部通知(含待发布/已下架),供通知管理页使用。
     * 收紧为管理级:员工若可访问该接口,将看到已下架通知(B12 越权泄露)。
     */
    @GetMapping("/store/{storeId}")
    public ApiResponse<List<Notification>> getNotificationsByStore(@PathVariable Long storeId) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权访问通知管理");
        }
        SecurityContext.checkStoreAccess(storeId);
        return ApiResponse.success(notificationService.getNotificationsByStore(storeId));
    }

    /**
     * 员工端(H5)查询:仅返回上架中且在上下架时间窗口内的通知。
     * 修复:此前 H5 复用管理端 /store/{storeId} 接口,下架通知仍在 H5 首页展示。
     */
    @GetMapping("/store/{storeId}/visible")
    public ApiResponse<List<Notification>> getVisibleNotifications(@PathVariable Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return ApiResponse.success(notificationService.getVisibleNotifications(storeId));
    }

    @OperationLog(value = "创建通知", detail = "'标题 ' + #notification.title")
    @PostMapping
    public ApiResponse<Notification> createNotification(@RequestBody Notification notification) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        SecurityContext.checkStoreAccess(notification.getStoreId());
        return ApiResponse.success(notificationService.createNotification(notification));
    }

    @OperationLog(value = "更新通知", detail = "'标题 ' + #notification.title")
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
    @OperationLog(value = "通知上下架", detail = "'标题 ' + #resolver.notificationTitle(#id) + ' 状态 ' + (#status == 1 ? '上架' : '下架')")
    @PutMapping("/{id}/status")
    public ApiResponse<Notification> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        // 与创建/更新/删除对齐为管理级权限:员工若可上下架通知,
        // 重新上架(status 0→1)会触发微信群发模板消息,可被用来消息轰炸全员
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new com.example.canteen.exception.BusinessException("非法状态值");
        }
        Notification notification = notificationService.getNotificationById(id);
        if (notification == null) {
            throw new com.example.canteen.exception.SecurityException("通知不存在");
        }
        SecurityContext.checkStoreAccess(notification.getStoreId());
        notification.setStatus(status);
        return ApiResponse.success(notificationService.updateNotification(notification));
    }

    @OperationLog(value = "删除通知", detail = "'标题 ' + #resolver.notificationTitle(#id)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteNotification(@PathVariable Long id) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("无权操作通知");
        }
        notificationService.deleteNotification(id);
        return ApiResponse.success(null);
    }
}
