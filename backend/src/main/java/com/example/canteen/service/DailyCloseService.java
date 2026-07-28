package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.DailyClose;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.OrderItem;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DailyCloseMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.OrderItemMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 日终对账 Service:汇总当日订单/营业额/退款/充值/新增员工/菜品销量 TOP5,
 * 确认后落库 daily_close 表,并提供历史对账记录查询。
 *
 * 订单状态:1=待支付 2=已完成(等同已支付) 3=已取消。
 * 营业额口径:已支付/已完成订单(status=2)的 totalAmount 之和。
 * 退款口径:已取消订单(status=3)的 totalAmount 之和。
 */
@Service
public class DailyCloseService {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final EmployeeMapper employeeMapper;
    private final DailyCloseMapper dailyCloseMapper;

    public DailyCloseService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                             RechargeRecordMapper rechargeRecordMapper,
                             EmployeeMapper employeeMapper,
                             DailyCloseMapper dailyCloseMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.employeeMapper = employeeMapper;
        this.dailyCloseMapper = dailyCloseMapper;
    }

    /**
     * 日终汇总:统计指定门店/日期的订单数、营业额、退款、充值、新增员工与菜品销量 TOP5。
     */
    public Map<String, Object> summary(Long storeId, LocalDate date) {
        SecurityContext.checkStoreAccess(storeId);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        // 当日订单(包含已取消,用于统计已取消数与退款)
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getCreatedAt, start)
                .lt(Order::getCreatedAt, end));
        // 当日充值
        List<RechargeRecord> recharges = rechargeRecordMapper.selectList(new LambdaQueryWrapper<RechargeRecord>()
                .eq(RechargeRecord::getStoreId, storeId)
                .ge(RechargeRecord::getCreatedAt, start)
                .lt(RechargeRecord::getCreatedAt, end));

        long orderCount = orders.size();
        // status=2 视为已支付/已完成
        long paidCount = orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == 2).count();
        long completedCount = paidCount;
        long cancelledCount = orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == 3).count();

        BigDecimal totalRevenue = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == 2)
                .map(o -> o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRefund = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == 3)
                .map(o -> o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rechargeAmount = recharges.stream()
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 当日新增员工
        long newEmployeeCount = employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .ge(Employee::getCreatedAt, start)
                .lt(Employee::getCreatedAt, end));

        // 菜品销量 TOP5(基于已支付/已完成订单)
        List<Long> orderIds = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == 2)
                .map(Order::getId)
                .collect(Collectors.toList());
        List<Map<String, Object>> dishSales = new ArrayList<>();
        if (!orderIds.isEmpty()) {
            List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                    .in(OrderItem::getOrderId, orderIds));
            Map<Long, DishAgg> agg = new HashMap<>();
            for (OrderItem it : items) {
                DishAgg a = agg.computeIfAbsent(it.getDishId(), k -> new DishAgg(it.getDishName()));
                int qty = it.getQuantity() == null ? 0 : it.getQuantity();
                BigDecimal price = it.getPrice() == null ? BigDecimal.ZERO : it.getPrice();
                a.quantity += qty;
                a.amount = a.amount.add(price.multiply(BigDecimal.valueOf(qty)));
            }
            dishSales = agg.values().stream()
                    .sorted(Comparator.comparingInt((DishAgg a) -> a.quantity).reversed())
                    .limit(5)
                    .map(a -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("dishName", a.name);
                        m.put("quantity", a.quantity);
                        m.put("amount", a.amount);
                        return m;
                    })
                    .collect(Collectors.toList());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("storeId", storeId);
        result.put("orderCount", orderCount);
        result.put("paidCount", paidCount);
        result.put("completedCount", completedCount);
        result.put("cancelledCount", cancelledCount);
        result.put("totalRevenue", totalRevenue);
        result.put("totalRefund", totalRefund);
        result.put("rechargeAmount", rechargeAmount);
        result.put("newEmployeeCount", newEmployeeCount);
        result.put("dishSales", dishSales);
        return result;
    }

    /**
     * 确认日终对账:校验当日未对账后,落库一条 daily_close 记录并返回对账单。
     */
    @Transactional
    public DailyClose confirm(Long storeId, LocalDate date, Long operatorId) {
        SecurityContext.checkStoreAccess(storeId);
        Long exist = dailyCloseMapper.selectCount(new LambdaQueryWrapper<DailyClose>()
                .eq(DailyClose::getStoreId, storeId)
                .eq(DailyClose::getCloseDate, date));
        if (exist != null && exist > 0) {
            throw new BusinessException("该日期已对账,无法重复确认");
        }

        Map<String, Object> summary = summary(storeId, date);

        DailyClose dc = new DailyClose();
        dc.setStoreId(storeId);
        dc.setCloseDate(date);
        dc.setOrderCount(((Number) summary.get("orderCount")).intValue());
        dc.setTotalRevenue((BigDecimal) summary.get("totalRevenue"));
        dc.setTotalRefund((BigDecimal) summary.get("totalRefund"));
        dc.setRechargeAmount((BigDecimal) summary.get("rechargeAmount"));
        dc.setStatus(1);
        dc.setOperatorId(operatorId);
        dailyCloseMapper.insert(dc);
        return dc;
    }

    /**
     * 历史对账记录分页查询。
     */
    public Map<String, Object> history(Long storeId, int page, int size) {
        SecurityContext.checkStoreAccess(storeId);
        IPage<DailyClose> p = dailyCloseMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<DailyClose>()
                        .eq(DailyClose::getStoreId, storeId)
                        .orderByDesc(DailyClose::getCloseDate));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    private static class DishAgg {
        String name;
        int quantity;
        BigDecimal amount;
        DishAgg(String name) {
            this.name = name;
            this.quantity = 0;
            this.amount = BigDecimal.ZERO;
        }
    }
}
