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

    @GetMapping("/store/{storeId}/date/{date}")
    public ApiResponse<List<MenuWithItemsDTO>> getMenuByDate(@PathVariable Long storeId, @PathVariable String date) {
        SecurityContext.checkStoreAccess(storeId);
        LocalDate localDate = LocalDate.parse(date);
        return ApiResponse.success(menuService.getMenuByDate(storeId, localDate));
    }

    /**
     * 查询门店某月已配置菜单的日期列表(用于月历标记)。
     * GET /api/menu/store/{storeId}/dates?year=2026&month=7
     */
    @GetMapping("/store/{storeId}/dates")
    public ApiResponse<List<String>> getMenuDatesByMonth(
            @PathVariable Long storeId,
            @RequestParam int year,
            @RequestParam int month) {
        SecurityContext.checkStoreAccess(storeId);
        return ApiResponse.success(menuService.getMenuDatesByMonth(storeId, year, month));
    }

    @OperationLog("创建菜单")
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
     * 复制菜单:把源日期所有餐次菜单复制到目标日期。
     * POST /api/menu/copy
     */
    @OperationLog("复制菜单")
    @PostMapping("/copy")
    public ApiResponse<Map<String, Object>> copyMenu(@Valid @RequestBody MenuCopyDTO dto) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(dto.getStoreId());
        int copied = menuService.copyMenu(dto);
        return ApiResponse.success(Map.of("copied", copied));
    }

    @OperationLog("删除菜单")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMenu(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        menuService.deleteMenu(id);
        return ApiResponse.success(null);
    }
}
