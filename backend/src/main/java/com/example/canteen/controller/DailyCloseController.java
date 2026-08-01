package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.DailyClose;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.DailyCloseService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 日终对账/关店流程接口。
 */
@RestController
@RequestMapping("/api/daily-close")
public class DailyCloseController {
    private final DailyCloseService dailyCloseService;

    public DailyCloseController(DailyCloseService dailyCloseService) {
        this.dailyCloseService = dailyCloseService;
    }

    /** 统一鉴权:校验门店访问权限,且需有对账/关店权限 */
    private void checkAccess(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (!SecurityContext.canSettle()) {
            throw new SecurityException("无权进行对账操作");
        }
    }

    /** 日终汇总:订单/营业额/退款/充值/新增员工/菜品销量 TOP5 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new BusinessException("date 参数不能为空");
        }
        checkAccess(storeId);
        return ApiResponse.success(dailyCloseService.summary(storeId, date));
    }

    /** 确认日终对账:记录到 daily_close 表 */
    @OperationLog(value = "日终对账确认", detail = "'门店ID ' + #storeId + ' 日期 ' + #date")
    @PostMapping("/confirm")
    public ApiResponse<DailyClose> confirm(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new BusinessException("date 参数不能为空");
        }
        checkAccess(storeId);
        Long operatorId = SecurityContext.currentAdminId();
        return ApiResponse.success(dailyCloseService.confirm(storeId, date, operatorId));
    }

    /** 历史对账记录分页查询 */
    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> history(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        checkAccess(storeId);
        return ApiResponse.success(dailyCloseService.history(storeId, page, size));
    }
}
