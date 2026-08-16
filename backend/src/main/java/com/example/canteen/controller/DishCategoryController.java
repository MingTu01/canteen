package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.DishCategory;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.DishCategoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dish-category")
public class DishCategoryController {
    private final DishCategoryService dishCategoryService;

    public DishCategoryController(DishCategoryService dishCategoryService) {
        this.dishCategoryService = dishCategoryService;
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<List<DishCategory>> getCategoriesByStore(@PathVariable Long storeId) {
        return ApiResponse.success(dishCategoryService.getCategoriesByStore(storeId));
    }

    @OperationLog(value = "创建菜品分类", detail = "'分类 ' + #category.name")
    @PostMapping
    public ApiResponse<DishCategory> createCategory(@RequestBody DishCategory category) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        return ApiResponse.success(dishCategoryService.createCategory(category));
    }

    @OperationLog(value = "更新菜品分类", detail = "'名称 ' + #category.name")
    @PutMapping("/{id}")
    public ApiResponse<DishCategory> updateCategory(@PathVariable Long id, @RequestBody DishCategory category) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        category.setId(id);
        return ApiResponse.success(dishCategoryService.updateCategory(category));
    }

    @OperationLog(value = "删除菜品分类", detail = "'名称 ' + #resolver.dishCategoryName(#id)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        dishCategoryService.deleteCategory(id);
        return ApiResponse.success(null);
    }
}
