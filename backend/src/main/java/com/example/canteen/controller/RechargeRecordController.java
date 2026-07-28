package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.RechargeDTO;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.RechargeRecordService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recharge")
public class RechargeRecordController {
    private final RechargeRecordService rechargeRecordService;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final EmployeeMapper employeeMapper;

    public RechargeRecordController(RechargeRecordService rechargeRecordService,
                                   RechargeRecordMapper rechargeRecordMapper,
                                   EmployeeMapper employeeMapper) {
        this.rechargeRecordService = rechargeRecordService;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.employeeMapper = employeeMapper;
    }

    @PostMapping
    public ApiResponse<RechargeRecord> recharge(@Valid @RequestBody RechargeDTO dto) {
        // P1-3 员工(role=0)和终端(role=3)无权充值,防止资金漏洞
        Integer role = SecurityContext.currentRole();
        if (role == null || role == 0 || role == 3) {
            throw new com.example.canteen.exception.SecurityException("无权充值");
        }
        Long storeId = dto.getStoreId();
        if (storeId == null) {
            storeId = SecurityContext.currentStoreId();
        }
        if (storeId == null) {
            throw new BusinessException("缺少门店参数");
        }
        SecurityContext.checkStoreAccess(storeId);
        // 校验员工归属该门店
        Employee employee = employeeMapper.selectById(dto.getEmployeeId());
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        if (!storeId.equals(employee.getStoreId())) {
            throw new BusinessException("员工不属于该门店");
        }
        // 调用新的 Service 签名 recharge(employeeId, storeId, amount, remark)
        return ApiResponse.success(rechargeRecordService.recharge(dto.getEmployeeId(), storeId, dto.getAmount(), dto.getRemark()));
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<Map<String, Object>> getRecordsByStore(@PathVariable Long storeId,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "10") int size,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        // P1-3 员工(role=0)和终端(role=3)无权查看门店充值记录
        Integer role = SecurityContext.currentRole();
        if (role == null || role == 0 || role == 3) {
            throw new com.example.canteen.exception.SecurityException("无权查看充值记录");
        }
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<RechargeRecord> wrapper = new LambdaQueryWrapper<RechargeRecord>()
                .eq(RechargeRecord::getStoreId, storeId)
                .orderByDesc(RechargeRecord::getId);
        if (startDate != null) {
            wrapper.ge(RechargeRecord::getCreatedAt, startDate.atStartOfDay());
        }
        if (endDate != null) {
            wrapper.le(RechargeRecord::getCreatedAt, endDate.plusDays(1).atStartOfDay());
        }
        IPage<RechargeRecord> p = rechargeRecordMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @GetMapping("/employee/{employeeId}")
    public ApiResponse<List<RechargeRecord>> getRecordsByEmployee(@PathVariable Long employeeId) {
        // 员工查自己;超管/门店管理员需校验员工归属门店;终端(role=3)不算管理员,只能查本店员工
        Integer role = SecurityContext.currentRole();
        boolean isSuper = role != null && role == SecurityContext.ROLE_SUPER_ADMIN;
        boolean isAdmin = role != null && (role == SecurityContext.ROLE_SUPER_ADMIN
                || role == SecurityContext.ROLE_STORE_ADMIN);
        Long currentEmployeeId = SecurityContext.currentEmployeeId();
        // P1-2 员工(role=0)只能查自己的充值记录,禁止查看同店其他员工
        if (role != null && role == 0 && currentEmployeeId != null
                && !currentEmployeeId.equals(employeeId)) {
            throw new com.example.canteen.exception.SecurityException("无权查看他人充值记录");
        }
        if (!isAdmin) {
            if (currentEmployeeId == null || !currentEmployeeId.equals(employeeId)) {
                Employee employee = employeeMapper.selectById(employeeId);
                if (employee == null) {
                    throw new BusinessException("员工不存在");
                }
                SecurityContext.checkStoreAccess(employee.getStoreId());
            }
        } else if (!isSuper) {
            Employee employee = employeeMapper.selectById(employeeId);
            if (employee == null) {
                throw new BusinessException("员工不存在");
            }
            SecurityContext.checkStoreAccess(employee.getStoreId());
        }
        return ApiResponse.success(rechargeRecordService.getRecordsByEmployee(employeeId));
    }
}
