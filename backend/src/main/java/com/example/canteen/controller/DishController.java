package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Dish;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.DishService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dish")
public class DishController {
    private final DishService dishService;
    private final DishMapper dishMapper;

    public DishController(DishService dishService, DishMapper dishMapper) {
        this.dishService = dishService;
        this.dishMapper = dishMapper;
    }

    // ==================== 批量操作请求体 ====================

    @Data
    public static class BatchStatusRequest {
        private List<Long> dishIds;
        private Integer status;
        private Long storeId;
    }

    @Data
    public static class BatchCategoryRequest {
        private List<Long> dishIds;
        private String category;
        private Long storeId;
    }

    @Data
    public static class BatchDeleteRequest {
        private List<Long> dishIds;
        private Long storeId;
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<Map<String, Object>> getDishesByStore(@PathVariable Long storeId,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "10") int size,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) String category) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Dish> wrapper = new LambdaQueryWrapper<Dish>()
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsDeleted, 0)
                .orderByDesc(Dish::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Dish::getName, keyword);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(Dish::getCategory, category);
        }
        IPage<Dish> p = dishMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    /**
     * 全量查询门店所有菜品(不分页,供终端启动时本地缓存用)。
     * 返回 List<Dish>(含 status=0 已下架的,终端需展示已下架状态)。
     */
    @GetMapping("/store/{storeId}/all")
    public ApiResponse<List<Dish>> getAllDishesByStore(@PathVariable Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        // 走 Service 缓存(含 status=0 已下架的)
        return ApiResponse.success(dishService.getAllDishesByStore(storeId));
    }

    @GetMapping("/store/{storeId}/new")
    public ApiResponse<Map<String, Object>> getNewDishesByStore(@PathVariable Long storeId,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "10") int size) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Dish> wrapper = new LambdaQueryWrapper<Dish>()
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsNew, 1)
                .eq(Dish::getStatus, 1)
                .eq(Dish::getIsDeleted, 0)
                .orderByDesc(Dish::getId);
        IPage<Dish> p = dishMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Dish> getDishById(@PathVariable Long id) {
        Dish dish = dishService.getDishById(id);
        if (dish == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(dish.getStoreId());
        return ApiResponse.success(dish);
    }

    @OperationLog("创建菜品")
    @PostMapping
    public ApiResponse<Dish> createDish(@RequestBody Dish dish) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        SecurityContext.checkStoreAccess(dish.getStoreId());
        return ApiResponse.success(dishService.createDish(dish));
    }

    @OperationLog("更新菜品")
    @PutMapping("/{id}")
    public ApiResponse<Dish> updateDish(@PathVariable Long id, @RequestBody Dish dish) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        dish.setId(id);
        // storeId 校验和覆盖在 service 层基于 existing 完成,避免信任 body storeId
        return ApiResponse.success(dishService.updateDish(dish));
    }

    @OperationLog("删除菜品")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDish(@PathVariable Long id) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        Dish existing = dishService.getDishById(id);
        if (existing == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        dishService.deleteDish(id);
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}/toggle-status")
    public ApiResponse<Void> toggleStatus(@PathVariable Long id) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        Dish existing = dishService.getDishById(id);
        if (existing == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        dishService.toggleStatus(id);
        return ApiResponse.success(null);
    }

    // ==================== 批量操作 ====================

    @PutMapping("/batch/status")
    public ApiResponse<Map<String, Object>> batchUpdateStatus(@RequestBody BatchStatusRequest req) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        if (req.getStatus() == null || (req.getStatus() != 0 && req.getStatus() != 1)) {
            return ApiResponse.error("状态值无效");
        }
        int affected = dishService.batchUpdateStatus(req.getDishIds(), req.getStatus(), req.getStoreId());
        Map<String, Object> result = new HashMap<>();
        result.put("affected", affected);
        return ApiResponse.success(result);
    }

    @PutMapping("/batch/category")
    public ApiResponse<Map<String, Object>> batchUpdateCategory(@RequestBody BatchCategoryRequest req) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        int affected = dishService.batchUpdateCategory(req.getDishIds(), req.getCategory(), req.getStoreId());
        Map<String, Object> result = new HashMap<>();
        result.put("affected", affected);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/batch")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody BatchDeleteRequest req) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        int affected = dishService.batchDelete(req.getDishIds(), req.getStoreId());
        Map<String, Object> result = new HashMap<>();
        result.put("affected", affected);
        return ApiResponse.success(result);
    }

    // ==================== 回收站 ====================

    @GetMapping("/trash")
    public ApiResponse<Map<String, Object>> getTrashList(@RequestParam Long storeId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        SecurityContext.checkStoreAccess(storeId);
        IPage<Dish> p = dishService.getTrashList(storeId, page, size);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @PutMapping("/{id}/restore")
    public ApiResponse<Void> restoreDish(@PathVariable Long id) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        dishService.restoreDish(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}/purge")
    public ApiResponse<Void> purgeDish(@PathVariable Long id) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        dishService.purgeDish(id);
        return ApiResponse.success(null);
    }
}
