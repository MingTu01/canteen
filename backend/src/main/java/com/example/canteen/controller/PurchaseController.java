package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.PurchaseCreateDTO;
import com.example.canteen.dto.PurchaseDetailDTO;
import com.example.canteen.entity.Purchase;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.PurchaseService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/purchase")
public class PurchaseController {
    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getPurchaseList(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        IPage<Purchase> p = purchaseService.getPurchaseList(storeId, page, size, status, startDate, endDate);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<PurchaseDetailDTO> getPurchaseDetail(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        return ApiResponse.success(purchaseService.getPurchaseDetail(id));
    }

    @OperationLog(value = "创建采购单", detail = "'门店ID ' + #dto.purchase.storeId + ' 供应商ID ' + #dto.purchase.supplierId")
    @PostMapping
    public ApiResponse<Purchase> createPurchase(@RequestBody PurchaseCreateDTO dto) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        if (dto.getPurchase() == null) {
            throw new BusinessException("采购单信息不能为空");
        }
        SecurityContext.checkStoreAccess(dto.getPurchase().getStoreId());
        return ApiResponse.success(purchaseService.createPurchase(dto));
    }

    @OperationLog(value = "更新采购状态", detail = "'采购单ID ' + #id + ' 状态 ' + #status")
    @PutMapping("/{id}/status")
    public ApiResponse<Purchase> updateStatus(@PathVariable Long id,
                                              @RequestParam int status) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        return ApiResponse.success(purchaseService.updateStatus(id, status));
    }

    @OperationLog(value = "删除采购单", detail = "'采购单ID ' + #id")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePurchase(@PathVariable Long id) {
        if (!SecurityContext.canManageProcurement()) {
            throw new com.example.canteen.exception.SecurityException("无权管理采购/库存");
        }
        purchaseService.deletePurchase(id);
        return ApiResponse.success(null);
    }
}
