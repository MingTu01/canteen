package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.OrderCreateDTO;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Order;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.OrderItemMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.security.LoginRateLimiter;
import com.example.canteen.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/order")
public class OrderController {
    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final EmployeeMapper employeeMapper;
    private final OrderItemMapper orderItemMapper;
    private final LoginRateLimiter rateLimiter;

    public OrderController(OrderService orderService, OrderMapper orderMapper,
                           EmployeeMapper employeeMapper, OrderItemMapper orderItemMapper,
                           LoginRateLimiter rateLimiter) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.employeeMapper = employeeMapper;
        this.orderItemMapper = orderItemMapper;
        this.rateLimiter = rateLimiter;
    }

    @OperationLog(value = "创建订单", detail = "'员工 ' + #resolver.employeeName(#dto.employeeId) + ' 餐次 ' + #resolver.mealType(#dto.mealType) + ' 日期 ' + #dto.date")
    @PostMapping
    public ApiResponse<Order> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        // 多租户:订单只能创建到当前用户有权限的门店
        SecurityContext.checkStoreAccess(dto.getStoreId());
        return ApiResponse.success(orderService.createOrder(dto));
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<Map<String, Object>> getOrdersByStore(@PathVariable Long storeId,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "10") int size,
                                                             @RequestParam(required = false) Integer status,
                                                             @RequestParam(required = false) Integer mealType,
                                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                             @RequestParam(required = false) String keyword) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new SecurityException("无权访问管理端数据");
        }
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .orderByDesc(Order::getId);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        if (mealType != null) {
            wrapper.eq(Order::getMealType, mealType);
        }
        if (startDate != null) {
            wrapper.ge(Order::getDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(Order::getDate, endDate);
        }
        // 关键字搜索:orderNo OR 员工姓名 OR 员工卡号
        if (keyword != null && !keyword.isBlank()) {
            List<Long> matchedEmpIds = employeeMapper.selectList(
                    new LambdaQueryWrapper<Employee>()
                            .eq(Employee::getStoreId, storeId)
                            .and(w -> w.like(Employee::getName, keyword)
                                    .or().like(Employee::getCardNo, keyword))
                            .select(Employee::getId)
            ).stream().map(Employee::getId).collect(Collectors.toList());
            if (matchedEmpIds.isEmpty()) {
                // 没有匹配的员工,仅按 orderNo 搜索
                wrapper.like(Order::getOrderNo, keyword);
            } else {
                // orderNo OR employeeId IN (matchedEmpIds)
                wrapper.and(w -> w.like(Order::getOrderNo, keyword)
                        .or().in(Order::getEmployeeId, matchedEmpIds));
            }
        }
        IPage<Order> p = orderMapper.selectPage(new Page<>(page, size), wrapper);

        // 填充 employeeName 和 cardNo(批量查询避免 N+1)
        List<Long> empIds = p.getRecords().stream()
                .map(Order::getEmployeeId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> empNameMap = new HashMap<>();
        Map<Long, String> empCardNoMap = new HashMap<>();
        if (!empIds.isEmpty()) {
            employeeMapper.selectBatchIds(empIds).forEach(e -> {
                empNameMap.put(e.getId(), e.getName());
                empCardNoMap.put(e.getId(), e.getCardNo());
            });
        }
        p.getRecords().forEach(o -> {
            o.setEmployeeName(empNameMap.getOrDefault(o.getEmployeeId(), null));
            o.setCardNo(empCardNoMap.getOrDefault(o.getEmployeeId(), null));
        });

        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @GetMapping("/employee/{employeeId}")
    public ApiResponse<List<Order>> getOrdersByEmployee(@PathVariable Long employeeId) {
        // 员工只能查自己订单,超管可查任意;终端(role=3)不算管理员,只能查本店员工
        Long currentEmployeeId = SecurityContext.currentEmployeeId();
        boolean isSuper = SecurityContext.isSuperAdmin();
        Integer role = SecurityContext.currentRole();
        boolean isAdmin = role != null && role != 0 && role != 3;
        // P1-3 员工角色只能查自己订单
        if (role != null && role == 0 && currentEmployeeId != null
                && !currentEmployeeId.equals(employeeId) && !isSuper) {
            throw new SecurityException("无权查看他人订单");
        }
        if (!isSuper && !isAdmin) {
            // 非管理员(员工/终端/未登录):员工只能查自己;终端等需校验员工归属门店
            if (currentEmployeeId == null || !currentEmployeeId.equals(employeeId)) {
                Employee employee = employeeMapper.selectById(employeeId);
                if (employee == null) {
                    throw new BusinessException("员工不存在");
                }
                SecurityContext.checkStoreAccess(employee.getStoreId());
            }
        } else if (!isSuper) {
            // 门店管理员(role=2):校验员工归属门店
            Employee employee = employeeMapper.selectById(employeeId);
            if (employee == null) {
                throw new BusinessException("员工不存在");
            }
            SecurityContext.checkStoreAccess(employee.getStoreId());
        }
        return ApiResponse.success(orderService.getOrdersByEmployee(employeeId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getOrderDetail(@PathVariable Long id) {
        Order order = orderService.getOrderById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        SecurityContext.checkStoreAccess(order.getStoreId());
        // P1-3 员工角色只能查自己订单
        SecurityContext.checkOrderOwnerOrAdmin(order.getEmployeeId());
        return ApiResponse.success(orderService.getOrderDetail(id));
    }

    @OperationLog(value = "完成订单", detail = "'订单ID ' + #id")
    @PutMapping("/{id}/complete")
    public ApiResponse<Void> completeOrder(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new SecurityException(SecurityException.FORBIDDEN, "员工无权完成订单,请到店核销");
        }
        Order order = orderService.getOrderById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        SecurityContext.checkStoreAccess(order.getStoreId());
        // P0-4 员工角色无权操作他人订单
        SecurityContext.checkOrderOwnerOrAdmin(order.getEmployeeId());
        orderService.completeOrder(id);
        return ApiResponse.success(null);
    }

    @OperationLog(value = "取消订单", detail = "'订单ID ' + #id")
    @PutMapping("/{id}/cancel")
    public ApiResponse<Void> cancelOrder(@PathVariable Long id) {
        Order order = orderService.getOrderById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        SecurityContext.checkStoreAccess(order.getStoreId());
        // P0-4 员工角色无权操作他人订单
        SecurityContext.checkOrderOwnerOrAdmin(order.getEmployeeId());
        orderService.cancelOrder(id);
        return ApiResponse.success(null);
    }

    @OperationLog(value = "取餐核销", detail = "'取餐码 ' + #body['pickupCode']")
    @PostMapping("/pickup")
    public ApiResponse<Void> pickup(@RequestBody Map<String, String> body) {
        // 取餐码核销限流:防止暴力枚举取餐码
        Long storeId = SecurityContext.currentStoreId();
        String rateLimitKey = "pickup:" + (storeId != null ? storeId : "unknown");
        rateLimiter.checkLocked(rateLimitKey);
        String pickupCode = body.get("pickupCode");
        if (pickupCode == null || pickupCode.isBlank()) {
            throw new BusinessException("取餐码不能为空");
        }
        try {
            orderService.pickup(pickupCode);
            rateLimiter.recordSuccess(rateLimitKey);
            return ApiResponse.success(null);
        } catch (RuntimeException e) {
            rateLimiter.recordFail(rateLimitKey);
            throw e;
        }
    }

    @GetMapping("/dashboard/{storeId}")
    public ApiResponse<Map<String, Object>> getDashboardStats(@PathVariable Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new SecurityException("无权访问管理端数据");
        }
        return ApiResponse.success(orderService.getDashboardStats(storeId));
    }

    /**
     * 订餐汇总:按门店+日期+餐次(可选)统计各菜品订购数量,供厨师备料导出。
     * 仅统计有效订单(status=1 待完成 / 2 已完成),排除已取消。
     */
    @GetMapping("/summary/{storeId}")
    public ApiResponse<Map<String, Object>> getOrderSummary(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer mealType) {
        SecurityContext.checkStoreAccess(storeId);
        if (SecurityContext.isEmployee()) {
            throw new SecurityException("无权访问管理端数据");
        }
        List<Map<String, Object>> rows = orderItemMapper.selectDishOrderSummary(storeId, date, mealType);

        // 规范化 key 为驼峰,统一返回结构(H2/MySQL 列名大小写不一致)
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("dishId", toLong(row.get("dish_id")));
            m.put("dishName", String.valueOf(row.get("dish_name")));
            m.put("price", row.get("price"));
            m.put("quantity", toInt(row.get("quantity")));
            m.put("orderCount", toInt(row.get("order_count")));
            return m;
        }).collect(Collectors.toList());

        int totalQuantity = items.stream()
                .mapToInt(m -> (int) m.get("quantity"))
                .sum();
        // 去重订单数:sum(orderCount) 会重复计算多菜品订单,改用独立 COUNT(DISTINCT) 查询
        Integer distinctOrders = orderItemMapper.countDistinctOrders(storeId, date, mealType);
        int totalOrders = distinctOrders != null ? distinctOrders : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("date", date.toString());
        result.put("mealType", mealType);
        result.put("items", items);
        result.put("totalQuantity", totalQuantity);
        result.put("totalOrders", totalOrders);
        result.put("dishCount", items.size());
        return ApiResponse.success(result);
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }
}
