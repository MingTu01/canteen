package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.DailySettlement;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.DailySettlementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 日终对账/关店流程接口。
 * 三阶段:1=待对账 → 2=已对账 → 3=已关店。
 */
@RestController
@RequestMapping("/api/settlement")
public class DailySettlementController {
    private final DailySettlementService dailySettlementService;

    public DailySettlementController(DailySettlementService dailySettlementService) {
        this.dailySettlementService = dailySettlementService;
    }

    /** 统一鉴权:校验门店访问权限,且禁止员工访问管理端数据 */
    private void checkAccess(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new SecurityException("无权访问管理端数据");
        }
        if (!SecurityContext.canSettle()) {
            throw new SecurityException("无对账/关店权限");
        }
    }

    /** 获取指定日期对账数据 */
    @GetMapping
    public ApiResponse<DailySettlement> getSettlement(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new BusinessException("date 参数不能为空");
        }
        checkAccess(storeId);
        return ApiResponse.success(dailySettlementService.getSettlement(storeId, date));
    }

    /** 生成/刷新对账数据 */
    @OperationLog(value = "生成对账数据", detail = "'门店ID ' + #storeId + ' 日期 ' + #date")
    @PostMapping("/generate")
    public ApiResponse<DailySettlement> generate(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new BusinessException("date 参数不能为空");
        }
        checkAccess(storeId);
        return ApiResponse.success(dailySettlementService.generateSettlement(storeId, date));
    }

    /** 历史列表 */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        checkAccess(storeId);
        return ApiResponse.success(dailySettlementService.getList(storeId, startDate, endDate, page, size));
    }

    /** 确认对账(1→2) */
    @OperationLog(value = "确认对账", detail = "'对账ID ' + #id")
    @PutMapping("/{id}/confirm")
    public ApiResponse<DailySettlement> confirm(@PathVariable Long id) {
        // 先查询记录并鉴权,避免先修改数据后被拦截
        DailySettlement existing = dailySettlementService.getById(id);
        if (existing == null) {
            throw new BusinessException("对账记录不存在");
        }
        checkAccess(existing.getStoreId());
        return ApiResponse.success(dailySettlementService.confirmSettlement(id));
    }

    /** 关店(2→3) */
    @OperationLog(value = "关店", detail = "'对账ID ' + #id")
    @PutMapping("/{id}/close")
    public ApiResponse<DailySettlement> close(@PathVariable Long id) {
        // 先查询记录并鉴权,避免先修改数据后被拦截
        DailySettlement existing = dailySettlementService.getById(id);
        if (existing == null) {
            throw new BusinessException("对账记录不存在");
        }
        checkAccess(existing.getStoreId());
        return ApiResponse.success(dailySettlementService.closeStore(id));
    }

    /** 今日状态 */
    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> today(
            @RequestParam Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        checkAccess(storeId);
        LocalDate target = date != null ? date : LocalDate.now();
        return ApiResponse.success(dailySettlementService.getCurrentStatus(storeId, target));
    }
}
