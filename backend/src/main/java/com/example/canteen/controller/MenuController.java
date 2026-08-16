package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.MenuCopyDTO;
import com.example.canteen.dto.MenuCreateDTO;
import com.example.canteen.dto.MenuWithItemsDTO;
import com.example.canteen.entity.Menu;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.MenuService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/menu")
public class MenuController {
    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * 查询某日菜单。
     * 点菜端(H5/终端)传 published=1 只返回已发布菜单;管理端不传返回全部。
     */
    @GetMapping("/store/{storeId}/date/{date}")
    public ApiResponse<List<MenuWithItemsDTO>> getMenuByDate(
            @PathVariable Long storeId, @PathVariable String date,
            @RequestParam(required = false) Integer published) {
        SecurityContext.checkStoreAccess(storeId);
        LocalDate localDate = LocalDate.parse(date);
        if (published != null && published == 1) {
            return ApiResponse.success(menuService.getPublishedMenuByDate(storeId, localDate));
        }
        return ApiResponse.success(menuService.getMenuByDate(storeId, localDate));
    }

    /**
     * 查询门店某月已配置菜单的日期列表(含发布状态,用于月历标记)。
     * GET /api/menu/store/{storeId}/dates?year=2026&month=7
     * 返回: [{date: "2026-07-01", published: true}, ...]
     */
    @GetMapping("/store/{storeId}/dates")
    public ApiResponse<List<Map<String, Object>>> getMenuDatesByMonth(
            @PathVariable Long storeId,
            @RequestParam int year,
            @RequestParam int month) {
        SecurityContext.checkStoreAccess(storeId);
        return ApiResponse.success(menuService.getMenuDatesByMonth(storeId, year, month));
    }

    @OperationLog(value = "创建菜单", detail = "'日期 ' + #dto.date + ' 餐次 ' + #dto.mealType")
    @PostMapping
    public ApiResponse<Menu> createMenu(@Valid @RequestBody MenuCreateDTO dto) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(dto.getStoreId());
        Menu menu = new Menu();
        menu.setStoreId(dto.getStoreId());
        menu.setDate(dto.getDate());
        menu.setMealType(dto.getMealType());
        return ApiResponse.success(menuService.createMenu(menu, dto.getDishIds()));
    }

    /**
     * 发布某日菜单(二次确认后调用):将当天所有菜单设为已发布,点菜端即可看到。
     * POST /api/menu/publish?storeId=1&date=2026-08-01
     */
    @OperationLog(value = "发布菜单", detail = "'日期 ' + #date")
    @PostMapping("/publish")
    public ApiResponse<Map<String, Object>> publishMenu(
            @RequestParam Long storeId,
            @RequestParam String date) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(storeId);
        LocalDate localDate = LocalDate.parse(date);
        int count = menuService.publishMenu(storeId, localDate);
        return ApiResponse.success(Map.of("published", count));
    }

    /**
     * 批量发布菜单:将指定日期范围内所有菜单设为已发布。
     * POST /api/menu/batch-publish?storeId=1
     * 无需选择日期,直接发布该门店所有未发布的菜单。
     */
    @OperationLog(value = "批量发布菜单", detail = "'门店 ' + #resolver.storeName(#storeId)")
    @PostMapping("/batch-publish")
    public ApiResponse<Map<String, Object>> batchPublishMenu(@RequestParam Long storeId) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(storeId);
        Map<String, Object> result = menuService.publishAllUnpublished(storeId);
        return ApiResponse.success(result);
    }

    /**
     * 复制菜单:把源日期所有餐次菜单复制到目标日期。
     * POST /api/menu/copy
     */
    @OperationLog(value = "复制菜单", detail = "'源日期 ' + #dto.sourceDate + ' 目标日期 ' + #dto.targetDate")
    @PostMapping("/copy")
    public ApiResponse<Map<String, Object>> copyMenu(@Valid @RequestBody MenuCopyDTO dto) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(dto.getStoreId());
        int copied = menuService.copyMenu(dto);
        return ApiResponse.success(Map.of("copied", copied));
    }

    @OperationLog(value = "删除菜单", detail = "#resolver.menuBrief(#id)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMenu(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        menuService.deleteMenu(id);
        return ApiResponse.success(null);
    }

    /**
     * 查询某日菜单的订单情况(修改/清空前提示用)。
     * GET /api/menu/store/{storeId}/date/{date}/orders-check
     * 返回:{ mealOrders: { "1": 3, "2": 5 }, total: 8 }
     * 仅包含订单数 > 0 的餐次。前端据此决定是否弹出二次确认。
     */
    @GetMapping("/store/{storeId}/date/{date}/orders-check")
    public ApiResponse<Map<String, Object>> checkOrdersBeforeModify(
            @PathVariable Long storeId, @PathVariable String date) {
        SecurityContext.checkStoreAccess(storeId);
        LocalDate localDate = LocalDate.parse(date);
        Map<Integer, Integer> mealOrders = menuService.countOrdersByDate(storeId, localDate);
        int total = mealOrders.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        // key 转字符串方便前端 JSON 处理
        Map<String, Integer> mealOrdersStr = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : mealOrders.entrySet()) {
            mealOrdersStr.put(String.valueOf(e.getKey()), e.getValue());
        }
        result.put("mealOrders", mealOrdersStr);
        result.put("total", total);
        return ApiResponse.success(result);
    }

    /**
     * 清空某日所有餐次菜单(草稿+已发布)。
     * DELETE /api/menu/store/{storeId}/date/{date}
     * 返回:{ cleared: 删除的菜单数量 }
     * 注:订单保存菜品快照,清空菜单不影响已有订单。
     */
    @OperationLog(value = "清空菜单", detail = "'门店 ' + #storeId + ' 日期 ' + #date")
    @DeleteMapping("/store/{storeId}/date/{date}")
    public ApiResponse<Map<String, Object>> clearMenusByDate(
            @PathVariable Long storeId, @PathVariable String date) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(storeId);
        LocalDate localDate = LocalDate.parse(date);
        int cleared = menuService.clearMenusByDate(storeId, localDate);
        return ApiResponse.success(Map.of("cleared", cleared));
    }
}
