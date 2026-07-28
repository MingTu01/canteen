package com.example.canteen.controller;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/report")
public class ReportController {
    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 报表接口统一鉴权:校验门店访问权限,且需有财务报表查看权限 */
    private void checkReportAccess(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (!SecurityContext.canViewFinance()) {
            throw new SecurityException("无权访问报表数据");
        }
    }

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> daily(@RequestParam Long storeId,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new BusinessException("date 参数不能为空");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.dailyReport(storeId, date));
    }

    @GetMapping("/weekly")
    public ApiResponse<Map<String, Object>> weekly(@RequestParam Long storeId,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate) {
        if (startDate == null) {
            throw new BusinessException("startDate 参数不能为空");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.weeklyReport(storeId, startDate));
    }

    @GetMapping("/monthly")
    public ApiResponse<Map<String, Object>> monthly(@RequestParam Long storeId,
                                                    @RequestParam String month) {
        if (month == null || month.isBlank()) {
            throw new BusinessException("month 参数不能为空");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.monthlyReport(storeId, month));
    }

    @GetMapping("/finance")
    public ApiResponse<Map<String, Object>> finance(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.financeReport(storeId, startDate, endDate));
    }

    @GetMapping("/employee-consumption")
    public ApiResponse<Map<String, Object>> employeeConsumption(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.employeeConsumptionReport(storeId, startDate, endDate));
    }

    /** 日终对账:统计当日订单、营业额、充值、退款、余额变动等关店核对数据 */
    @GetMapping("/daily-close")
    public ApiResponse<Map<String, Object>> dailyClose(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new BusinessException("date 参数不能为空");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.dailyCloseReport(storeId, date));
    }

    /** 同比分析:对比指定时间段与去年同期的订单数、营业额、退款额及增长率 */
    @GetMapping("/yoy")
    public ApiResponse<Map<String, Object>> yearOverYear(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new BusinessException("startDate 和 endDate 参数不能为空");
        }
        if (startDate.isAfter(endDate)) {
            throw new BusinessException("startDate 不能晚于 endDate");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.getYearOverYear(storeId, startDate, endDate));
    }

    /** 环比分析:对比指定月份与上月的订单数、营业额、退款额及增长率 */
    @GetMapping("/mom")
    public ApiResponse<Map<String, Object>> monthOverMonth(
            @RequestParam Long storeId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        if (year == null || month == null) {
            throw new BusinessException("year 和 month 参数不能为空");
        }
        if (month < 1 || month > 12) {
            throw new BusinessException("month 必须在 1-12 之间");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.getMonthOverMonth(storeId, year, month));
    }

    /** 拥堵分析:某日各时段订单数分布 */
    @GetMapping("/hourly")
    public ApiResponse<Map<String, Object>> hourly(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new BusinessException("date 参数不能为空");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.getHourlyDistribution(storeId, date));
    }

    /** 拥堵分析:指定时间段内的高峰时段 */
    @GetMapping("/peak")
    public ApiResponse<Map<String, Object>> peak(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new BusinessException("startDate 和 endDate 参数不能为空");
        }
        if (startDate.isAfter(endDate)) {
            throw new BusinessException("startDate 不能晚于 endDate");
        }
        checkReportAccess(storeId);
        return ApiResponse.success(reportService.getPeakHours(storeId, startDate, endDate));
    }
}
