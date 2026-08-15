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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 报表查询辅助类:抽取 ReportService 中重复的区间查询与聚合逻辑。
 * 包括订单/充值区间查询、按状态过滤、热销菜品聚合、余额汇总等;
 * 以及月报/同环比的 SQL 聚合下推(避免整段订单 SELECT * 拉进 JVM 再 stream 聚合)。
 */
@Service
public class ReportQueryHelper {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final EmployeeMapper employeeMapper;
    private final JdbcTemplate jdbcTemplate;

    public ReportQueryHelper(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                             RechargeRecordMapper rechargeRecordMapper,
                             EmployeeMapper employeeMapper,
                             JdbcTemplate jdbcTemplate) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.employeeMapper = employeeMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询门店在 [start, end) 订餐日期范围内的全部订单(含所有状态)。
     * 口径统一:按订餐日期(order.date)统计,次日订单计入就餐日,而非下单日。
     */
    public List<Order> findOrdersByRange(Long storeId, LocalDate start, LocalDate end) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getDate, start)
                .lt(Order::getDate, end));
    }

    /**
     * 查询门店在 [start, end) 时间段内按下单时间(created_at)的全部订单。
     * 供时段/高峰分布(getHourlyDistribution / getPeakHours)使用:反映实际下单流量。
     */
    public List<Order> findOrdersByCreatedAtRange(Long storeId, LocalDateTime start, LocalDateTime end) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getCreatedAt, start)
                .lt(Order::getCreatedAt, end));
    }

    /** 查询门店在 [start, end) 时间段内的全部充值记录(充值按 created_at 时间统计,保持不变) */
    public List<RechargeRecord> findRechargesByRange(Long storeId, LocalDateTime start, LocalDateTime end) {
        return rechargeRecordMapper.selectList(new LambdaQueryWrapper<RechargeRecord>()
                .eq(RechargeRecord::getStoreId, storeId)
                .ge(RechargeRecord::getCreatedAt, start)
                .lt(RechargeRecord::getCreatedAt, end));
    }

    /** 查询已完成订单(status=2),按订餐日期范围 */
    public List<Order> findCompletedOrdersByRange(Long storeId, LocalDate start, LocalDate end) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getDate, start)
                .lt(Order::getDate, end)
                .eq(Order::getStatus, OrderStatus.COMPLETED.getCode()));
    }

    /**
     * 查询有效订单(排除已取消 status=3,保留 status 为 null 的历史数据),按订餐日期范围。
     * 对应原 Java 过滤:o.getStatus() == null || o.getStatus() != 3
     */
    public List<Order> findActiveOrdersByRange(Long storeId, LocalDate start, LocalDate end) {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getDate, start)
                .lt(Order::getDate, end)
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

    /* ============================================================
     * SQL 聚合下推(月报/同环比):只 SELECT 聚合结果,不拉明细行
     * 兼容 MySQL(生产)与 H2 MODE=MySQL(dev):
     * - 不用 DATE_FORMAT(H2 不支持),按月分组用 EXTRACT(YEAR/MONTH FROM date)
     * - status 条件分支口径与原 Java stream 聚合完全一致(null 视为有效历史数据)
     * ============================================================ */

    /** 同/环比订单聚合列(订单数排除已取消 / 营业额仅已完成 / 退款为已取消) */
    private static final String COMPARISON_AGG_COLUMNS =
            "COALESCE(SUM(CASE WHEN status IS NULL OR status <> 3 THEN 1 ELSE 0 END), 0) AS order_count, "
            + "COALESCE(SUM(CASE WHEN status = 2 THEN total_amount ELSE 0 END), 0) AS revenue, "
            + "COALESCE(SUM(CASE WHEN status = 3 THEN total_amount ELSE 0 END), 0) AS refund";

    /**
     * 单时间段的订单聚合(SQL 下推,同/环比口径)。
     * 返回 {orderCount: long, revenue: BigDecimal, refund: BigDecimal},
     * 与原 aggregateYoyMom(List<Order>) 的数值口径一致:
     * - orderCount: 排除已取消(status=3,status 为 null 的历史数据保留)
     * - revenue: 仅已完成(status=2)的 totalAmount 合计
     * - refund: 已取消(status=3)的 totalAmount 合计
     */
    public Map<String, Object> aggregateOrdersByRange(Long storeId, LocalDate start, LocalDate end) {
        String sql = "SELECT " + COMPARISON_AGG_COLUMNS
                + " FROM `order` WHERE store_id = ? AND date >= ? AND date < ?";
        return jdbcTemplate.query(sql, rs -> {
            rs.next();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderCount", rs.getLong("order_count"));
            m.put("revenue", rs.getBigDecimal("revenue"));
            m.put("refund", rs.getBigDecimal("refund"));
            return m;
        }, storeId, start, end);
    }

    /**
     * 按月分组的订单聚合(SQL 下推):key 为 "yyyy-MM",value 同 aggregateOrdersByRange。
     * 环比(MoM)两期均为完整自然月,一次分组查询即可同时取到当月与上月,少一次往返。
     * 注意:分组基于订餐日期 order.date(口径与列表查询一致)。
     */
    public Map<String, Map<String, Object>> aggregateOrdersByMonth(Long storeId, LocalDate start, LocalDate end) {
        // 不使用 DATE_FORMAT(H2 不支持),用 EXTRACT 组合出 ym 数值(202608)再在 Java 侧格式化
        String groupExpr = "EXTRACT(YEAR FROM date) * 100 + EXTRACT(MONTH FROM date)";
        String sql = "SELECT " + groupExpr + " AS ym, " + COMPARISON_AGG_COLUMNS
                + " FROM `order` WHERE store_id = ? AND date >= ? AND date < ?"
                + " GROUP BY " + groupExpr + " ORDER BY ym";
        return jdbcTemplate.query(sql, rs -> {
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            while (rs.next()) {
                long ym = rs.getLong("ym");
                String month = String.format("%04d-%02d", ym / 100, ym % 100);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderCount", rs.getLong("order_count"));
                m.put("revenue", rs.getBigDecimal("revenue"));
                m.put("refund", rs.getBigDecimal("refund"));
                result.put(month, m);
            }
            return result;
        }, storeId, start, end);
    }

    /**
     * 有效订单聚合(SQL 下推,月报口径):排除已取消(status=3,null 保留)。
     * 返回 {totalOrders, totalRevenue, breakfast, lunch, dinner},
     * 与原 findActiveOrdersByRange + buildReport 的内存聚合口径一致。
     */
    public Map<String, Object> aggregateActiveOrdersByRange(Long storeId, LocalDate start, LocalDate end) {
        String sql = "SELECT COUNT(*) AS total_orders, "
                + "COALESCE(SUM(total_amount), 0) AS total_revenue, "
                + "COALESCE(SUM(CASE WHEN meal_type = 1 THEN 1 ELSE 0 END), 0) AS breakfast, "
                + "COALESCE(SUM(CASE WHEN meal_type = 2 THEN 1 ELSE 0 END), 0) AS lunch, "
                + "COALESCE(SUM(CASE WHEN meal_type = 3 THEN 1 ELSE 0 END), 0) AS dinner "
                + "FROM `order` WHERE store_id = ? AND date >= ? AND date < ?"
                + " AND (status IS NULL OR status <> 3)";
        return jdbcTemplate.query(sql, rs -> {
            rs.next();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalOrders", rs.getLong("total_orders"));
            m.put("totalRevenue", rs.getBigDecimal("total_revenue"));
            m.put("breakfast", rs.getLong("breakfast"));
            m.put("lunch", rs.getLong("lunch"));
            m.put("dinner", rs.getLong("dinner"));
            return m;
        }, storeId, start, end);
    }

    /**
     * 热销菜品聚合(SQL 下推):JOIN order_item 按菜品汇总销量,降序取前 limit 名。
     * 与原 topDishes(orders, limit) 口径一致(传入订单需为有效订单,状态过滤在 SQL 内完成);
     * 返回行仅含聚合结果(dishId/dishName/quantity),不拉订单明细。
     */
    public List<Map<String, Object>> topDishesByRange(Long storeId, LocalDate start, LocalDate end, int limit) {
        String sql = "SELECT oi.dish_id AS dish_id, oi.dish_name AS dish_name, "
                + "COALESCE(SUM(oi.quantity), 0) AS quantity "
                + "FROM order_item oi INNER JOIN `order` o ON oi.order_id = o.id "
                + "WHERE o.store_id = ? AND o.date >= ? AND o.date < ?"
                + " AND (o.status IS NULL OR o.status <> 3) "
                + "GROUP BY oi.dish_id, oi.dish_name "
                + "ORDER BY quantity DESC, oi.dish_id ASC LIMIT ?";
        return jdbcTemplate.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dishId", rs.getLong("dish_id"));
            m.put("dishName", rs.getString("dish_name"));
            m.put("quantity", rs.getInt("quantity"));
            return m;
        }, storeId, start, end, limit);
    }

    /** 充值总额(SQL 下推):按 created_at 时间段 SUM(amount),与 sumAmount(recharges) 口径一致 */
    public BigDecimal sumRechargeAmount(Long storeId, LocalDateTime start, LocalDateTime end) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM recharge_record"
                        + " WHERE store_id = ? AND created_at >= ? AND created_at < ?",
                BigDecimal.class, storeId, start, end);
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
