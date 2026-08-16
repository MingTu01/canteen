package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.DiningTimeSlot;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.DiningTimeSlotService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/timer")
public class DiningTimeSlotController {
    private final DiningTimeSlotService timeSlotService;

    public DiningTimeSlotController(DiningTimeSlotService timeSlotService) {
        this.timeSlotService = timeSlotService;
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<List<DiningTimeSlot>> getTimeSlotsByStore(@PathVariable Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return ApiResponse.success(timeSlotService.getTimeSlotsByStore(storeId));
    }

    @OperationLog(value = "创建就餐时段", detail = "'门店 ' + #resolver.storeName(#timeSlot.storeId) + ' ' + #resolver.mealType(#timeSlot.mealType) + ' ' + #timeSlot.startTime + '-' + #timeSlot.endTime")
    @PostMapping
    public ApiResponse<DiningTimeSlot> createTimeSlot(@RequestBody DiningTimeSlot timeSlot) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(timeSlot.getStoreId());
        return ApiResponse.success(timeSlotService.createTimeSlot(timeSlot));
    }

    @OperationLog(value = "更新就餐时段", detail = "'门店 ' + #resolver.storeName(#timeSlot.storeId) + ' ' + #resolver.mealType(#timeSlot.mealType) + ' ' + #timeSlot.startTime + '-' + #timeSlot.endTime")
    @PutMapping("/{id}")
    public ApiResponse<DiningTimeSlot> updateTimeSlot(@PathVariable Long id, @RequestBody DiningTimeSlot timeSlot) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        timeSlot.setId(id);
        return ApiResponse.success(timeSlotService.updateTimeSlot(timeSlot));
    }

    @OperationLog(value = "删除就餐时段", detail = "'名称 ' + #resolver.timeSlotName(#id)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTimeSlot(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        timeSlotService.deleteTimeSlot(id);
        return ApiResponse.success(null);
    }
}
