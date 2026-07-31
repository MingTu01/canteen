package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.*;
import com.example.canteen.mapper.*;
import com.example.canteen.security.SecurityContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 测试用接口。
 *
 * /api/test/employees: 公开接口(所有环境可用),返回员工列表用于登录页"模拟刷卡"。
 * /api/test/create-order: 仅 dev 环境可用(绕过校验创建测试订单)。
 *
 * 满足硬约束:测试面板/模拟刷卡在所有环境可见。
 */
@RestController
@RequestMapping("/api/test")
public class TestController {

    private final EmployeeMapper employeeMapper;
    private final StoreMapper storeMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final DishMapper dishMapper;
    private final Environment environment;

    public TestController(EmployeeMapper employeeMapper,
                         StoreMapper storeMapper,
                         OrderMapper orderMapper,
                         OrderItemMapper orderItemMapper,
                         DishMapper dishMapper,
                         Environment environment) {
        this.employeeMapper = employeeMapper;
        this.storeMapper = storeMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.dishMapper = dishMapper;
        this.environment = environment;
    }

    /**
     * 公开获取全部员工列表(测试模拟刷卡用)。
     * 返回字段:id / cardNo / name / storeId / storeName / departmentId
     * 注意:phone 字段仅在 dev 环境返回,生产环境不暴露 PII
     */
    @GetMapping("/employees")
    public ApiResponse<List<Map<String, Object>>> listEmployees() {
        boolean isDev = Arrays.asList(environment.getActiveProfiles()).contains("dev");

        Map<Long, String> storeNameMap = storeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Store::getId, s -> s.getName() == null ? "" : s.getName(), (a, b) -> a));

        List<Employee> employees = employeeMapper.selectList(
                new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getIsDeleted, 0)
                        .orderByAsc(Employee::getStoreId)
                        .orderByAsc(Employee::getId)
        );

        List<Map<String, Object>> result = employees.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("cardNo", e.getCardNo());
            m.put("name", e.getName());
            // phone 属于 PII,仅 dev 环境返回
            if (isDev) {
                m.put("phone", e.getPhone());
            }
            m.put("storeId", e.getStoreId());
            m.put("storeName", storeNameMap.getOrDefault(e.getStoreId(), ""));
            m.put("departmentId", e.getDepartmentId());
            return m;
        }).collect(Collectors.toList());

        return ApiResponse.success(result);
    }

    /**
     * 为指定员工创建"今天"的测试订单(绕过登录/截止时间/余额校验,仅用于测试取餐流程)。
     * 若该员工今天该餐次已有订单,直接返回现有订单(避免重复)。
     *
     * @param employeeId 员工 ID
     * @param mealType   餐次:1早 2午 3晚(默认 2 午餐)
     */
    @PostMapping("/create-order")
    public ApiResponse<Map<String, Object>> createTestOrder(
            @RequestParam Long employeeId,
            @RequestParam(required = false, defaultValue = "2") Integer mealType) {

        // 仅 dev 环境允许创建测试订单(绕过校验),生产环境禁止
        boolean isDev = Arrays.asList(environment.getActiveProfiles()).contains("dev");
        if (!isDev) {
            return ApiResponse.error(403, "测试订单接口仅在开发环境可用");
        }

        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null) {
            return ApiResponse.error(404, "员工不存在");
        }
        Long storeId = emp.getStoreId();
        if (storeId == null) {
            return ApiResponse.error(400, "员工未绑定门店");
        }
        // 门店隔离校验:终端只能为本门店员工创建测试订单,防止跨租户数据污染
        SecurityContext.checkStoreAccess(storeId);

        LocalDate today = LocalDate.now();
        int mt = mealType == null ? 2 : mealType;

        // 已有该餐次订单:直接返回(不重复创建)
        Order exist = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getEmployeeId, employeeId)
                .eq(Order::getDate, today)
                .eq(Order::getMealType, mt)
                .last("LIMIT 1"));
        if (exist != null) {
            return ApiResponse.success(buildOrderResp(exist, "已存在测试订单,直接复用"));
        }

        // 取该门店 3 个菜(优先 status=1 在售,不足则取全部)
        List<Dish> dishes = dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsDeleted, 0)
                .eq(Dish::getStatus, 1)
                .last("LIMIT 3"));
        if (dishes.isEmpty()) {
            dishes = dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                    .eq(Dish::getStoreId, storeId)
                    .eq(Dish::getIsDeleted, 0)
                    .last("LIMIT 3"));
        }
        if (dishes.isEmpty()) {
            return ApiResponse.error(400, "门店无菜品,无法创建测试订单");
        }

        // 计算总价
        BigDecimal total = BigDecimal.ZERO;
        for (Dish d : dishes) {
            BigDecimal p = d.getPrice() == null ? BigDecimal.ZERO : d.getPrice();
            total = total.add(p);
        }

        // 插入订单(status=1 待取餐)
        Order order = new Order();
        order.setOrderNo("TEST" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(100, 999));
        order.setStoreId(storeId);
        order.setEmployeeId(employeeId);
        order.setDate(today);
        order.setMealType(mt);
        order.setTotalAmount(total);
        order.setStatus(1);
        order.setPickupCode(String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1000000)));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(order);

        // 插入订单明细
        for (Dish d : dishes) {
            OrderItem it = new OrderItem();
            it.setOrderId(order.getId());
            it.setDishId(d.getId());
            it.setDishName(d.getName());
            it.setPrice(d.getPrice());
            it.setQuantity(1);
            it.setCreatedAt(LocalDateTime.now());
            orderItemMapper.insert(it);
        }

        return ApiResponse.success(buildOrderResp(order, "测试订单创建成功"));
    }

    private Map<String, Object> buildOrderResp(Order order, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", order.getId());
        m.put("orderNo", order.getOrderNo());
        m.put("pickupCode", order.getPickupCode());
        m.put("mealType", order.getMealType());
        m.put("date", order.getDate() == null ? null : order.getDate().toString());
        m.put("totalAmount", order.getTotalAmount());
        m.put("message", msg);
        return m;
    }
}
