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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dish")
public class DishController {
    private final DishService dishService;
    private final DishMapper dishMapper;
    private final JdbcTemplate jdbcTemplate;

    public DishController(DishService dishService, DishMapper dishMapper, JdbcTemplate jdbcTemplate) {
        this.dishService = dishService;
        this.dishMapper = dishMapper;
        this.jdbcTemplate = jdbcTemplate;
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
                                                            @RequestParam(required = false) String category,
                                                            @RequestParam(required = false) Integer mealType) {
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
        if (mealType != null) {
            // mealTypes 是逗号分隔的字符串(如 "1,2,3"),用 FIND_IN_SET 过滤
            wrapper.apply("FIND_IN_SET({0}, meal_types) > 0", mealType);
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

    /**
     * 菜品数据版本号(供终端增量同步判断是否有变更)。
     * 返回 {"version": "<count>:<maxUpdated>"},如 "35:2026-08-15 10:30:00"。
     *
     * 用 JdbcTemplate 原生 SQL(不走 MyBatis-Plus 逻辑删除拦截):
     * 统计范围包含逻辑删除行(is_deleted=1),使"删除菜品"也会改变版本号,
     * 正好覆盖删除变更场景;空表时 maxUpdated 为 '0'。
     * 权限/门店校验与 /store/{storeId}/all 一致。
     */
    @GetMapping("/store/{storeId}/version")
    public ApiResponse<Map<String, Object>> getDishVersion(@PathVariable Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        String version = jdbcTemplate.queryForObject(
                "SELECT CONCAT(COUNT(*), ':', COALESCE(CAST(MAX(updated_at) AS CHAR), '0'))"
                        + " FROM dish WHERE store_id = ?",
                String.class, storeId);
        Map<String, Object> result = new HashMap<>();
        result.put("version", version == null ? "0:0" : version);
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

    @OperationLog(value = "创建菜品", detail = "'菜品 ' + #dish.name + ' 价格 ' + #dish.price")
    @PostMapping
    public ApiResponse<Dish> createDish(@RequestBody Dish dish) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        SecurityContext.checkStoreAccess(dish.getStoreId());
        return ApiResponse.success(dishService.createDish(dish));
    }

    @OperationLog(value = "更新菜品", detail = "'菜品ID ' + #id + ' 名称 ' + #dish.name")
    @PutMapping("/{id}")
    public ApiResponse<Dish> updateDish(@PathVariable Long id, @RequestBody Dish dish) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        dish.setId(id);
        // storeId 校验和覆盖在 service 层基于 existing 完成,避免信任 body storeId
        return ApiResponse.success(dishService.updateDish(dish));
    }

    @OperationLog(value = "删除菜品", detail = "'菜品ID ' + #id")
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

    @OperationLog(value = "菜品上下架", detail = "'菜品ID ' + #id")
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

    @OperationLog(value = "批量修改菜品状态", detail = "'菜品数量 ' + #req.dishIds.size() + ' 状态 ' + (#req.status == 1 ? '上架' : '下架')")
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

    @OperationLog(value = "批量修改菜品分类", detail = "'菜品数量 ' + #req.dishIds.size() + ' 分类 ' + #req.category")
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

    @OperationLog(value = "批量删除菜品", detail = "'菜品数量 ' + #req.dishIds.size()")
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

    @OperationLog(value = "恢复菜品", detail = "'菜品ID ' + #id")
    @PutMapping("/{id}/restore")
    public ApiResponse<Void> restoreDish(@PathVariable Long id) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        dishService.restoreDish(id);
        return ApiResponse.success(null);
    }

    @OperationLog(value = "彻底删除菜品", detail = "'菜品ID ' + #id")
    @DeleteMapping("/{id}/purge")
    public ApiResponse<Void> purgeDish(@PathVariable Long id) {
        if (!SecurityContext.canManageDish()) {
            throw new com.example.canteen.exception.SecurityException("无权管理菜品");
        }
        dishService.purgeDish(id);
        return ApiResponse.success(null);
    }
}
