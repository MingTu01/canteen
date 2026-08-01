package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Supplier;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.SupplierService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/supplier")
public class SupplierController {
    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getSupplierList(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        IPage<Supplier> p = supplierService.getSupplierList(storeId, page, size, keyword);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    /** 采购单下拉选择用:返回合作中的供应商列表 */
    @GetMapping("/active")
    public ApiResponse<List<Supplier>> getActiveSuppliers(@RequestParam Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        return ApiResponse.success(supplierService.getActiveSuppliers(storeId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Supplier> getSupplierById(@PathVariable Long id) {
        Supplier supplier = supplierService.getSupplierById(id);
        if (supplier == null) {
            throw new BusinessException("供应商不存在");
        }
        SecurityContext.checkStoreAccess(supplier.getStoreId());
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        return ApiResponse.success(supplier);
    }

    @OperationLog(value = "创建供应商", detail = "'供应商 ' + #supplier.name")
    @PostMapping
    public ApiResponse<Supplier> createSupplier(@RequestBody Supplier supplier) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        SecurityContext.checkStoreAccess(supplier.getStoreId());
        return ApiResponse.success(supplierService.createSupplier(supplier));
    }

    @OperationLog(value = "更新供应商", detail = "'供应商ID ' + #id + ' 名称 ' + #supplier.name")
    @PutMapping("/{id}")
    public ApiResponse<Supplier> updateSupplier(@PathVariable Long id, @RequestBody Supplier supplier) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        supplier.setId(id);
        return ApiResponse.success(supplierService.updateSupplier(supplier));
    }

    @OperationLog(value = "删除供应商", detail = "'供应商ID ' + #id")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSupplier(@PathVariable Long id) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        Supplier existing = supplierService.getSupplierById(id);
        if (existing == null) {
            throw new BusinessException("供应商不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        supplierService.deleteSupplier(id);
        return ApiResponse.success(null);
    }
}
