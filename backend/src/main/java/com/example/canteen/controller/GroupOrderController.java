package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.GroupOrderCreateDTO;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.GroupOrder;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.GroupOrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/group-order")
public class GroupOrderController {
    private final GroupOrderService groupOrderService;
    private final EmployeeMapper employeeMapper;

    public GroupOrderController(GroupOrderService groupOrderService, EmployeeMapper employeeMapper) {
        this.groupOrderService = groupOrderService;
        this.employeeMapper = employeeMapper;
    }

    /** 员工查看所在门店的团体订单(H5 端) */
    @GetMapping("/my")
    public ApiResponse<List<GroupOrder>> getMyGroupOrders() {
        Long employeeId = SecurityContext.currentEmployeeId();
        if (employeeId == null) {
            throw new SecurityException("无法验证员工身份");
        }
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null || emp.getStoreId() == null) {
            return ApiResponse.success(Collections.emptyList());
        }
        return ApiResponse.success(groupOrderService.getGroupOrdersByStore(emp.getStoreId()));
    }

    /** 员工查看所在门店的团体订单详情(H5 端) */
    @GetMapping("/my/{id}")
    public ApiResponse<Map<String, Object>> getMyGroupOrderDetail(@PathVariable Long id) {
        Long employeeId = SecurityContext.currentEmployeeId();
        if (employeeId == null) {
            throw new SecurityException("无法验证员工身份");
        }
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null || emp.getStoreId() == null) {
            throw new SecurityException("无法验证员工身份");
        }
        // 复用管理端详情,但校验该团餐属于员工所在门店
        Map<String, Object> detail = groupOrderService.getDetail(id);
        GroupOrder go = (GroupOrder) detail.get("groupOrder");
        if (go == null || !emp.getStoreId().equals(go.getStoreId())) {
            throw new BusinessException("团餐不存在或无权访问");
        }
        return ApiResponse.success(detail);
    }

    /** 列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> getList(
            @RequestParam Long storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问管理端数据");
        }
        SecurityContext.checkStoreAccess(storeId);
        IPage<GroupOrder> p = groupOrderService.getList(storeId, page, size, status, startDate, endDate);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    /** 详情(含明细) */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getDetail(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("无权访问管理端数据");
        }
        return ApiResponse.success(groupOrderService.getDetail(id));
    }

    /** 创建(含 items) */
    @PostMapping
    public ApiResponse<GroupOrder> create(@RequestBody GroupOrderCreateDTO dto) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        if (dto.getGroupOrder() == null) {
            throw new BusinessException("团体订单信息不能为空");
        }
        return ApiResponse.success(groupOrderService.createGroupOrder(dto));
    }

    /** 确认 */
    @PutMapping("/{id}/confirm")
    public ApiResponse<GroupOrder> confirm(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        return ApiResponse.success(groupOrderService.confirm(id));
    }

    /** 取消 */
    @PutMapping("/{id}/cancel")
    public ApiResponse<GroupOrder> cancel(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        return ApiResponse.success(groupOrderService.cancel(id));
    }

    /** 完成 */
    @PutMapping("/{id}/complete")
    public ApiResponse<GroupOrder> complete(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        return ApiResponse.success(groupOrderService.complete(id));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        groupOrderService.delete(id);
        return ApiResponse.success(null);
    }
}
