package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.OrderItem;
import com.example.canteen.entity.OrderStatus;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.OrderItemMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 报表查询辅助类:抽取 ReportService 中重复的区间查询与聚合逻辑。
 * 包括订单/充值区间查询、按状态过滤、热销菜品聚合、余额汇总等。
 */
@Service
public class ReportQueryHelper {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final EmployeeMapper employeeMapper;

    public ReportQueryHelper(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                             RechargeRecordMapper rechargeRecordMapper,
                             EmployeeMapper employeeMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.employeeMapper = employeeMapper;
    }

    /** 查询门店在 [start, end) 时间段内的全部订单(含所有状态) */
    public List<Order> findOrdersByRange(Long storeId, LocalDateTime start, LocalDateTime end) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getCreatedAt, start)
                .lt(Order::getCreatedAt, end));
    }

    /** 查询门店在 [start, end) 时间段内的全部充值记录 */
    public List<RechargeRecord> findRechargesByRange(Long storeId, LocalDateTime start, LocalDateTime end) {
        return rechargeRecordMapper.selectList(new LambdaQueryWrapper<RechargeRecord>()
                .eq(RechargeRecord::getStoreId, storeId)
                .ge(RechargeRecord::getCreatedAt, start)
                .lt(RechargeRecord::getCreatedAt, end));
    }

    /** 查询已完成订单(status=2) */
    public List<Order> findCompletedOrdersByRange(Long storeId, LocalDateTime start, LocalDateTime end) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getCreatedAt, start)
                .lt(Order::getCreatedAt, end)
                .eq(Order::getStatus, OrderStatus.COMPLETED.getCode()));
    }

    /**
     * 查询有效订单(排除已取消 status=3,保留 status 为 null 的历史数据)。
     * 对应原 Java 过滤:o.getStatus() == null || o.getStatus() != 3
     */
    public List<Order> findActiveOrdersByRange(Long storeId, LocalDateTime start, LocalDateTime end) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getCreatedAt, start)
                .lt(Order::getCreatedAt, end)
                .and(w -> w.isNull(Order::getStatus)
                        .or().ne(Order::getStatus, OrderStatus.CANCELED.getCode())));
    }

    /**
     * 热销菜品聚合:对传入订单的订单明细按 dishId 汇总销量,降序取前 limit 名。
     * 调用方负责先按需过滤订单状态(如仅已完成),helper 不再二次过滤。
     */
    public List<DishAgg> topDishes(List<Order> orders, int limit) {
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getOrderId, orderIds));
        Map<Long, DishAgg> agg = new HashMap<>();
        for (OrderItem it : items) {
            DishAgg a = agg.computeIfAbsent(it.getDishId(), k -> new DishAgg(it.getDishName()));
            a.quantity += it.getQuantity();
        }
        return agg.values().stream()
                .sorted(Comparator.comparingInt((DishAgg a) -> a.quantity).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** 充值总额:对传入充值记录的 amount 求和(忽略 null) */
    public BigDecimal sumAmount(List<RechargeRecord> recharges) {
        return recharges.stream()
                .map(RechargeRecord::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 门店所有活跃员工(is_deleted=0)的余额总和 */
    public BigDecimal sumEmployeeBalance(Long storeId) {
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getIsDeleted, 0));
        return employees.stream()
                .map(e -> e.getBalance() == null ? BigDecimal.ZERO : e.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 菜品聚合结果(菜品名 + 总销量) */
    public static class DishAgg {
        String name;
        int quantity;
        DishAgg(String name) {
            this.name = name;
            this.quantity = 0;
        }
    }
}
