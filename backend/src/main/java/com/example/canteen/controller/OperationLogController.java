package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.OperationLog;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.mapper.OperationLogMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/operation-log")
public class OperationLogController {
    private final OperationLogMapper operationLogMapper;

    public OperationLogController(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Integer status) {
        // 仅管理员可查看操作日志
        Integer role = SecurityContext.currentRole();
        if (role == null || (role != SecurityContext.ROLE_SUPER_ADMIN && role != SecurityContext.ROLE_STORE_ADMIN)) {
            throw new SecurityException("无权访问");
        }
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getId);
        // 门店管理员只能看本店日志,超管可看全部
        if (!SecurityContext.isSuperAdmin()) {
            Long currentStoreId = SecurityContext.currentStoreId();
            if (currentStoreId != null) {
                wrapper.eq(OperationLog::getStoreId, currentStoreId);
            }
        }
        if (storeId != null && SecurityContext.isSuperAdmin()) {
            wrapper.eq(OperationLog::getStoreId, storeId);
        }
        if (operation != null && !operation.isBlank()) {
            wrapper.like(OperationLog::getOperation, operation);
        }
        if (status != null) {
            wrapper.eq(OperationLog::getStatus, status);
        }
        IPage<OperationLog> p = operationLogMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }
}
