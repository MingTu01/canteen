package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.entity.Department;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.MealType;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.OrderStatus;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 报表 Service:统计订单数、营业额、各餐次占比、热销菜品 TOP5、充值总额
 */
@Service
@Transactional(readOnly = true)
public class ReportService {
    private final ReportQueryHelper reportQueryHelper;
    private final EmployeeMapper employeeMapper;
    private final DepartmentMapper departmentMapper;

    public ReportService(ReportQueryHelper reportQueryHelper,
                         EmployeeMapper employeeMapper,
                         DepartmentMapper departmentMapper) {
        this.reportQueryHelper = reportQueryHelper;
        this.employeeMapper = employeeMapper;
        this.departmentMapper = departmentMapper;
    }

    public Map<String, Object> dailyReport(Long storeId, LocalDate date) {
        SecurityContext.checkStoreAccess(storeId);
        // 口径统一:订单按订餐日期统计;充值按下单时间(created_at)统计
        List<Order> orders = reportQueryHelper.findActiveOrdersByRange(storeId, date, date.plusDays(1));
        List<RechargeRecord> recharges = reportQueryHelper.findRechargesByRange(storeId,
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        return buildReport(orders, recharges, "daily", date.toString());
    }

    public Map<String, Object> weeklyReport(Long storeId, LocalDate startDate) {
        SecurityContext.checkStoreAccess(storeId);
        // 口径统一:订单按订餐日期统计;充值按下单时间(created_at)统计
        List<Order> orders = reportQueryHelper.findActiveOrdersByRange(storeId, startDate, startDate.plusDays(7));
        List<RechargeRecord> recharges = reportQueryHelper.findRechargesByRange(storeId,
                startDate.atStartOfDay(), startDate.plusDays(7).atStartOfDay());
        return buildReport(orders, recharges, "weekly", startDate.toString());
    }

    public Map<String, Object> monthlyReport(Long storeId, String month) {
        SecurityContext.checkStoreAccess(storeId);
        YearMonth ym = YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyy-MM"));
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.plusMonths(1).atDay(1);
        // SQL 聚合下推:月报订单量为整月数据,不再整段 SELECT * 拉 JVM stream 聚合;
        // 口径统一:订单按订餐日期统计;充值按下单时间(created_at)统计。
        // 输出结构与 buildReport(日/周报)完全一致,前端零改动。
        Map<String, Object> agg = reportQueryHelper.aggregateActiveOrdersByRange(storeId, start, end);
        List<Map<String, Object>> topDishes = reportQueryHelper.topDishesByRange(storeId, start, end, 5);
        BigDecimal totalRecharge = reportQueryHelper.sumRechargeAmount(storeId,
                start.atStartOfDay(), end.atStartOfDay());
        return buildReportFromAgg("monthly", month, agg, topDishes, totalRecharge);
    }

    /**
     * 财务对账报表:统计时间段内充值/消费/退款总额、当前员工余额总和及净流入。
     * 消费总额排除已取消订单(status=3);退款总额为已取消订单(status=3)的 totalAmount。
     */
    public Map<String, Object> financeReport(Long storeId, LocalDate startDate, LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);

        // 充值总额(从 recharge_record 表,按 created_at 统计)
        List<RechargeRecord> recharges = reportQueryHelper.findRechargesByRange(storeId,
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
        BigDecimal totalRecharge = reportQueryHelper.sumAmount(recharges);

        // 订单数据:消费/退款均从 order 表统计(口径统一:按订餐日期统计)
        List<Order> orders = reportQueryHelper.findOrdersByRange(storeId, startDate, endDate.plusDays(1));
        // 消费总额(排除已取消 status=3)
        BigDecimal totalConsumption = orders.stream()
                .filter(o -> o.getStatus() == null || o.getStatus() != OrderStatus.CANCELED.getCode())
                .map(Order::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 退款总额(status=3 的 totalAmount)
        BigDecimal totalRefund = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.CANCELED.getCode())
                .map(Order::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 当前所有活跃员工余额总和(从 employee 表)
        BigDecimal currentBalance = reportQueryHelper.sumEmployeeBalance(storeId);

        // 净流入 = 充值 - 消费(消费已排除已取消订单,退款不应再加,避免重复计算)
        BigDecimal netFlow = totalRecharge.subtract(totalConsumption);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRecharge", totalRecharge);
        result.put("totalConsumption", totalConsumption);
        result.put("totalRefund", totalRefund);
        result.put("currentBalance", currentBalance);
        result.put("netFlow", netFlow);
        return result;
    }

    /**
     * 员工消费统计报表:按员工统计时间段内消费总额与订单数(排除已取消订单)。
     */
    public Map<String, Object> employeeConsumptionReport(Long storeId, LocalDate startDate, LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);

        // 排除已取消订单(口径统一:按订餐日期统计)
        List<Order> orders = reportQueryHelper.findActiveOrdersByRange(storeId, startDate, endDate.plusDays(1));

        // 按员工聚合消费总额与订单数
        Map<Long, BigDecimal> consumptionByEmp = new LinkedHashMap<>();
        Map<Long, Long> countByEmp = new LinkedHashMap<>();
        for (Order o : orders) {
            BigDecimal amt = o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount();
            consumptionByEmp.merge(o.getEmployeeId(), amt, BigDecimal::add);
            countByEmp.merge(o.getEmployeeId(), 1L, Long::sum);
        }

        // 预加载员工与部门信息(过滤已删除员工)
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getIsDeleted, 0));
        Map<Long, Employee> empMap = employees.stream()
                .collect(Collectors.toMap(Employee::getId, e -> e, (a, b) -> a));
        List<Department> depts = departmentMapper.selectByStoreId(storeId);
        Map<Long, String> deptNameMap = new HashMap<>();
        if (depts != null) {
            for (Department d : depts) {
                deptNameMap.put(d.getId(), d.getName());
            }
        }

        BigDecimal totalConsumption = BigDecimal.ZERO;
        long totalOrders = 0L;
        List<Map<String, Object>> employeeList = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : consumptionByEmp.entrySet()) {
            Long empId = entry.getKey();
            Employee emp = empMap.get(empId);
            Long deptId = emp != null ? emp.getDepartmentId() : null;
            long orderCount = countByEmp.getOrDefault(empId, 0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("employeeId", empId);
            m.put("employeeName", emp != null ? emp.getName() : null);
            m.put("departmentName", deptId != null ? deptNameMap.get(deptId) : null);
            m.put("totalConsumption", entry.getValue());
            m.put("orderCount", orderCount);
            employeeList.add(m);
            totalConsumption = totalConsumption.add(entry.getValue());
            totalOrders += orderCount;
        }
        // 按消费总额降序
        employeeList.sort((a, b) -> ((BigDecimal) b.get("totalConsumption"))
                .compareTo((BigDecimal) a.get("totalConsumption")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employees", employeeList);
        result.put("totalConsumption", totalConsumption);
        result.put("totalOrders", totalOrders);
        return result;
    }

    /**
     * 日终对账报表:统计当日订单、营业额、充值、退款、各餐次、热销 TOP10、期初/期末余额与余额变动。
     * 期初余额 = 期末余额 - 当日(充值 - 消费 + 退款)
     * 余额变动 = 期末 - 期初 = 充值 - 消费 + 退款
     */
    public Map<String, Object> dailyCloseReport(Long storeId, LocalDate date) {
        SecurityContext.checkStoreAccess(storeId);

        // 当日订单(包含已取消,用于统计已取消数;口径统一:按订餐日期统计)
        List<Order> orders = reportQueryHelper.findOrdersByRange(storeId, date, date.plusDays(1));
        // 当日充值(按 created_at 统计)
        List<RechargeRecord> recharges = reportQueryHelper.findRechargesByRange(storeId,
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        // 订单统计
        long totalOrders = orders.size();
        long completedOrders = orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.COMPLETED.getCode()).count();
        long canceledOrders = orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.CANCELED.getCode()).count();
        long pendingOrders = orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.PENDING.getCode()).count();
        long missedOrders = orders.stream().filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.MISSED.getCode()).count();

        // 营业额:已完成(2) + 未就餐(4) 的金额之和(均已收款未退款,与 DailySettlementService 口径一致)
        BigDecimal totalRevenue = orders.stream()
                .filter(o -> o.getStatus() != null && (o.getStatus() == OrderStatus.COMPLETED.getCode() || o.getStatus() == OrderStatus.MISSED.getCode()))
                .map(Order::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 退款总额(已取消订单总额)
        BigDecimal totalRefund = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.CANCELED.getCode())
                .map(Order::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 消费总额(已完成订单,用于对账口径)
        BigDecimal totalConsumption = totalRevenue;
        // 充值总额
        BigDecimal totalRecharge = reportQueryHelper.sumAmount(recharges);

        // 各餐次订单数和营业额(已完成)
        List<Map<String, Object>> mealTypeStats = new ArrayList<>();
        for (MealType mt : MealType.values()) {
            final int mealType = mt.getCode();
            long count = orders.stream().filter(o -> o.getMealType() != null && o.getMealType() == mealType).count();
            BigDecimal revenue = orders.stream()
                    .filter(o -> o.getMealType() != null && o.getMealType() == mealType
                            && o.getStatus() != null && o.getStatus() == OrderStatus.COMPLETED.getCode())
                    .map(Order::getTotalAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mealType", mealType);
            m.put("mealTypeName", mt.getChineseName());
            m.put("orderCount", count);
            m.put("revenue", revenue);
            mealTypeStats.add(m);
        }

        // 热销菜品 TOP10(已完成订单)
        List<Order> completedOrderList = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.COMPLETED.getCode())
                .collect(Collectors.toList());
        List<Map<String, Object>> topDishes = reportQueryHelper.topDishes(completedOrderList, 10).stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dishName", a.name);
                    m.put("quantity", a.quantity);
                    return m;
                })
                .collect(Collectors.toList());

        // 期末余额:所有活跃员工当日余额总和
        BigDecimal endingBalance = reportQueryHelper.sumEmployeeBalance(storeId);
        // 期初余额 = 期末 - (充值 - 消费 + 退款)
        BigDecimal balanceChange = totalRecharge.subtract(totalConsumption).add(totalRefund);
        BigDecimal openingBalance = endingBalance.subtract(balanceChange);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        // 订单统计
        result.put("totalOrders", totalOrders);
        result.put("completedOrders", completedOrders);
        result.put("canceledOrders", canceledOrders);
        result.put("pendingOrders", pendingOrders);
        result.put("missedOrders", missedOrders);
        // 金额
        result.put("totalRevenue", totalRevenue);
        result.put("totalRefund", totalRefund);
        result.put("totalRecharge", totalRecharge);
        result.put("totalConsumption", totalConsumption);
        // 餐次
        result.put("mealTypeStats", mealTypeStats);
        // TOP10
        result.put("topDishes", topDishes);
        // 余额
        result.put("openingBalance", openingBalance);
        result.put("endingBalance", endingBalance);
        result.put("balanceChange", balanceChange);
        return result;
    }

    private Map<String, Object> buildReport(List<Order> orders, List<RechargeRecord> recharges,
                                            String periodType, String period) {
        // 调用方已通过 findActiveOrdersByRange 排除已取消订单(status=3)
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodType", periodType);
        result.put("period", period);

        long totalOrders = orders.size();
        BigDecimal totalRevenue = orders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() != OrderStatus.CANCELED.getCode())
                .map(Order::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 各餐次占比
        Map<String, Long> mealTypeCount = new LinkedHashMap<>();
        long breakfast = orders.stream().filter(o -> o.getMealType() != null && o.getMealType() == MealType.BREAKFAST.getCode()).count();
        long lunch = orders.stream().filter(o -> o.getMealType() != null && o.getMealType() == MealType.LUNCH.getCode()).count();
        long dinner = orders.stream().filter(o -> o.getMealType() != null && o.getMealType() == MealType.DINNER.getCode()).count();
        mealTypeCount.put("breakfast", breakfast);
        mealTypeCount.put("lunch", lunch);
        mealTypeCount.put("dinner", dinner);
        Map<String, Object> mealTypeRatio = new LinkedHashMap<>();
        mealTypeRatio.put("count", mealTypeCount);
        Map<String, String> ratio = new LinkedHashMap<>();
        if (totalOrders > 0) {
            ratio.put("breakfast", String.format("%.2f%%", breakfast * 100.0 / totalOrders));
            ratio.put("lunch", String.format("%.2f%%", lunch * 100.0 / totalOrders));
            ratio.put("dinner", String.format("%.2f%%", dinner * 100.0 / totalOrders));
        } else {
            ratio.put("breakfast", "0.00%");
            ratio.put("lunch", "0.00%");
            ratio.put("dinner", "0.00%");
        }
        mealTypeRatio.put("ratio", ratio);

        // 热销菜品 TOP5
        List<Map<String, Object>> topDishes = reportQueryHelper.topDishes(orders, 5).stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dishId", null);
                    m.put("dishName", a.name);
                    m.put("quantity", a.quantity);
                    return m;
                })
                .collect(Collectors.toList());

        // 充值总额
        BigDecimal totalRecharge = reportQueryHelper.sumAmount(recharges);

        result.put("totalOrders", totalOrders);
        result.put("totalRevenue", totalRevenue);
        result.put("mealTypeStats", mealTypeRatio);
        result.put("topDishes", topDishes);
        result.put("totalRecharge", totalRecharge);
        return result;
    }

    /**
     * 月报组装(SQL 聚合结果版):输出结构与 buildReport 完全一致,前端零改动。
     * agg 来自 aggregateActiveOrdersByRange(已排除已取消订单),
     * topDishes 来自 topDishesByRange(同样基于有效订单聚合)。
     */
    private Map<String, Object> buildReportFromAgg(String periodType, String period,
                                                   Map<String, Object> agg,
                                                   List<Map<String, Object>> topDishes,
                                                   BigDecimal totalRecharge) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodType", periodType);
        result.put("period", period);

        long totalOrders = (Long) agg.get("totalOrders");
        BigDecimal totalRevenue = (BigDecimal) agg.get("totalRevenue");
        long breakfast = (Long) agg.get("breakfast");
        long lunch = (Long) agg.get("lunch");
        long dinner = (Long) agg.get("dinner");

        // 各餐次占比(与 buildReport 相同的结构:count + ratio)
        Map<String, Long> mealTypeCount = new LinkedHashMap<>();
        mealTypeCount.put("breakfast", breakfast);
        mealTypeCount.put("lunch", lunch);
        mealTypeCount.put("dinner", dinner);
        Map<String, Object> mealTypeRatio = new LinkedHashMap<>();
        mealTypeRatio.put("count", mealTypeCount);
        Map<String, String> ratio = new LinkedHashMap<>();
        if (totalOrders > 0) {
            ratio.put("breakfast", String.format("%.2f%%", breakfast * 100.0 / totalOrders));
            ratio.put("lunch", String.format("%.2f%%", lunch * 100.0 / totalOrders));
            ratio.put("dinner", String.format("%.2f%%", dinner * 100.0 / totalOrders));
        } else {
            ratio.put("breakfast", "0.00%");
            ratio.put("lunch", "0.00%");
            ratio.put("dinner", "0.00%");
        }
        mealTypeRatio.put("ratio", ratio);

        result.put("totalOrders", totalOrders);
        result.put("totalRevenue", totalRevenue);
        result.put("mealTypeStats", mealTypeRatio);
        result.put("topDishes", topDishes);
        result.put("totalRecharge", totalRecharge);
        return result;
    }

    /* ============================================================
     * 同比环比 YoY / MoM
     * ============================================================ */

    /**
     * 同比分析:对比指定时间段与去年同期的订单数、营业额、退款额。
     * 订单数:排除已取消订单(status=3);
     * 营业额:已完成订单(status=2)的 totalAmount 合计;
     * 退款额:已取消订单(status=3)的 totalAmount 合计。
     * 增长率 = (今年 - 去年) / 去年 * 100,去年为 0 时返回 null。
     *
     * SQL 聚合下推:两段各一次聚合查询,不拉订单明细进 JVM。
     */
    public Map<String, Object> getYearOverYear(Long storeId, LocalDate startDate, LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);
        // 口径统一:按订餐日期统计
        Map<String, Object> current = reportQueryHelper.aggregateOrdersByRange(storeId,
                startDate, endDate.plusDays(1));
        Map<String, Object> previous = reportQueryHelper.aggregateOrdersByRange(storeId,
                startDate.minusYears(1), endDate.minusYears(1).plusDays(1));

        Map<String, Object> growth = buildGrowth(current, previous);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", current);
        result.put("previous", previous);
        result.put("growth", growth);
        return result;
    }

    /**
     * 环比分析:对比指定月份与上月数据。
     *
     * SQL 按月分组一次查回两月聚合(两期均为完整自然月),不拉订单明细进 JVM。
     */
    public Map<String, Object> getMonthOverMonth(Long storeId, int year, int month) {
        SecurityContext.checkStoreAccess(storeId);
        YearMonth ym = YearMonth.of(year, month);
        YearMonth prevYm = ym.minusMonths(1);

        // 口径统一:按订餐日期统计;一次分组查询覆盖上月月初 ~ 当月月末
        Map<String, Map<String, Object>> byMonth = reportQueryHelper.aggregateOrdersByMonth(storeId,
                prevYm.atDay(1), ym.plusMonths(1).atDay(1));
        Map<String, Object> current = byMonth.getOrDefault(ym.toString(), emptyComparisonAgg());
        Map<String, Object> previous = byMonth.getOrDefault(prevYm.toString(), emptyComparisonAgg());

        Map<String, Object> growth = buildGrowth(current, previous);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", current);
        result.put("previous", previous);
        result.put("growth", growth);
        return result;
    }

    /** 无订单月份的空聚合(分组结果缺失时补零) */
    private Map<String, Object> emptyComparisonAgg() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderCount", 0L);
        m.put("revenue", BigDecimal.ZERO);
        m.put("refund", BigDecimal.ZERO);
        return m;
    }

    /** 计算增长率:previous 为 0 时返回 null(无法计算) */
    private Map<String, Object> buildGrowth(Map<String, Object> current, Map<String, Object> previous) {
        BigDecimal curRev = toBigDecimal(current.get("revenue"));
        BigDecimal preRev = toBigDecimal(previous.get("revenue"));
        BigDecimal curRef = toBigDecimal(current.get("refund"));
        BigDecimal preRef = toBigDecimal(previous.get("refund"));
        long curCnt = toLong(current.get("orderCount"));
        long preCnt = toLong(previous.get("orderCount"));

        Map<String, Object> g = new LinkedHashMap<>();
        g.put("orderCountGrowth", growthRate(curCnt, preCnt));
        g.put("revenueGrowth", growthRate(curRev, preRev));
        g.put("refundGrowth", growthRate(curRef, preRef));
        return g;
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return 0L; }
    }

    /** 计算百分比增长率,基数为 0 时返回 null。结果保留 1 位小数。 */
    private BigDecimal growthRate(long current, long previous) {
        if (previous == 0) return null;
        return BigDecimal.valueOf((current - previous) * 100.0 / previous)
                .setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal growthRate(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) return null;
        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /* ============================================================
     * 拥堵分析 Hourly / Peak
     * ============================================================ */

    /**
     * 某日各时段订单分布:按 created_at 的小时分组统计订单数。
     * 返回 0-23 全量小时(无订单的小时为 0),便于前端绘制柱状图。
     */
    public Map<String, Object> getHourlyDistribution(Long storeId, LocalDate date) {
        SecurityContext.checkStoreAccess(storeId);
        // 时段分布本来就是按下单时间:保持 created_at 口径
        List<Order> orders = reportQueryHelper.findOrdersByCreatedAtRange(storeId,
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        // 按小时聚合(包含全部状态订单,反映实际下单流量)
        Map<Integer, Long> hourCount = new HashMap<>();
        for (Order o : orders) {
            if (o.getCreatedAt() == null) continue;
            int hour = o.getCreatedAt().getHour();
            hourCount.merge(hour, 1L, Long::sum);
        }
        List<Map<String, Object>> hourly = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", h);
            m.put("count", hourCount.getOrDefault(h, 0L));
            hourly.add(m);
        }
        long total = orders.size();
        // 当日峰值(非零最大值),用于前端高亮
        int peakHour = -1;
        long peakCount = 0;
        for (Map.Entry<Integer, Long> e : hourCount.entrySet()) {
            if (e.getValue() > peakCount) {
                peakCount = e.getValue();
                peakHour = e.getKey();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("hourly", hourly);
        result.put("totalOrders", total);
        result.put("peakHour", peakHour);
        result.put("peakCount", peakCount);
        return result;
    }

    /**
     * 高峰时段分析:统计时间段内各小时平均订单数,标记超过平均值 1.5 倍的时段为高峰。
     */
    public Map<String, Object> getPeakHours(Long storeId, LocalDate startDate, LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);
        // 高峰时段分布与 getHourlyDistribution 同口径:按下单时间 created_at 统计
        List<Order> orders = reportQueryHelper.findOrdersByCreatedAtRange(storeId,
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());

        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days <= 0) days = 1;

        // 按小时聚合订单数(汇总)
        Map<Integer, Long> hourTotal = new HashMap<>();
        for (Order o : orders) {
            if (o.getCreatedAt() == null) continue;
            int hour = o.getCreatedAt().getHour();
            hourTotal.merge(hour, 1L, Long::sum);
        }

        // 计算平均订单数(只考虑实际出现订单的小时,以避免 0 拉低阈值导致全时段都被标记为高峰)
        long sumOrders = hourTotal.values().stream().mapToLong(Long::longValue).sum();
        int activeHours = hourTotal.size();
        double avgPerHour = activeHours > 0 ? (double) sumOrders / activeHours : 0.0;
        double avgPerHourPerDay = avgPerHour / days;
        double threshold = avgPerHour * 1.5;

        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            long total = hourTotal.getOrDefault(h, 0L);
            double avg = (double) total / days;
            boolean isPeak = total > 0 && total > threshold;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", h);
            m.put("totalOrders", total);
            m.put("avgOrders", BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP));
            m.put("isPeak", isPeak);
            hours.add(m);
        }
        List<Map<String, Object>> peakHours = hours.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("isPeak")))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("days", days);
        result.put("totalOrders", sumOrders);
        result.put("avgOrdersPerHour", BigDecimal.valueOf(avgPerHour).setScale(1, RoundingMode.HALF_UP));
        result.put("avgOrdersPerHourPerDay", BigDecimal.valueOf(avgPerHourPerDay).setScale(1, RoundingMode.HALF_UP));
        result.put("threshold", BigDecimal.valueOf(threshold).setScale(1, RoundingMode.HALF_UP));
        result.put("hours", hours);
        result.put("peakHours", peakHours);
        return result;
    }
}
