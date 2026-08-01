package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Material;
import com.example.canteen.entity.StockCount;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.MaterialService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/material")
public class MaterialController {
    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getMaterialList(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean lowStock) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        IPage<Material> p = materialService.getMaterialList(storeId, page, size, keyword, lowStock);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Material> getMaterialById(@PathVariable Long id) {
        Material material = materialService.getMaterialById(id);
        if (material == null) {
            throw new BusinessException("食材不存在");
        }
        SecurityContext.checkStoreAccess(material.getStoreId());
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        return ApiResponse.success(material);
    }

    @PostMapping
    public ApiResponse<Material> createMaterial(@RequestBody Material material) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        SecurityContext.checkStoreAccess(material.getStoreId());
        return ApiResponse.success(materialService.createMaterial(material));
    }

    @PutMapping("/{id}")
    public ApiResponse<Material> updateMaterial(@PathVariable Long id, @RequestBody Material material) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        material.setId(id);
        return ApiResponse.success(materialService.updateMaterial(material));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMaterial(@PathVariable Long id) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        materialService.deleteMaterial(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/inbound")
    public ApiResponse<Material> inbound(@PathVariable Long id,
                                         @RequestParam BigDecimal qty,
                                         @RequestParam(required = false) String remark) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        return ApiResponse.success(materialService.inbound(id, qty, remark));
    }

    @PostMapping("/{id}/outbound")
    public ApiResponse<Material> outbound(@PathVariable Long id,
                                          @RequestParam BigDecimal qty,
                                          @RequestParam(required = false) String remark) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        return ApiResponse.success(materialService.outbound(id, qty, remark));
    }

    // ==================== 库存盘点 ====================

    /**
     * 创建盘点记录
     */
    @PostMapping("/{id}/stocktake")
    public ApiResponse<StockCount> stocktake(@PathVariable Long id,
                                              @RequestParam BigDecimal countedQty,
                                              @RequestParam(required = false) String remark) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        return ApiResponse.success(materialService.createStockCount(id, countedQty, remark));
    }

    /**
     * 查询盘点记录列表
     */
    @GetMapping("/stocktake")
    public ApiResponse<Map<String, Object>> stocktakeList(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status) {
        SecurityContext.checkStoreAccess(storeId);
        IPage<StockCount> p = materialService.getStockCountList(storeId, page, size, status);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    /**
     * 恢复单条盘点差异
     */
    @PostMapping("/stocktake/{stockCountId}/resolve")
    public ApiResponse<StockCount> resolveStockCount(@PathVariable Long stockCountId) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        return ApiResponse.success(materialService.resolveStockCount(stockCountId));
    }

    /**
     * 批量恢复所有待处理盘点差异
     */
    @PostMapping("/stocktake/resolve-all")
    public ApiResponse<Map<String, Object>> resolveAllStockCount(@RequestParam Long storeId) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        int count = materialService.resolveAllStockCount(storeId);
        Map<String, Object> result = new HashMap<>();
        result.put("resolvedCount", count);
        return ApiResponse.success(result);
    }
}
