package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.canteen.dto.OrderCreateDTO;
import com.example.canteen.dto.OrderItemDTO;
import com.example.canteen.entity.DiningTimeSlot;
import com.example.canteen.entity.Dish;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.MealType;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.OrderItem;
import com.example.canteen.entity.OrderSource;
import com.example.canteen.entity.OrderStatus;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.OrderItemMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrderService {
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final DishMapper dishMapper;
    private final EmployeeMapper employeeMapper;
    private final JdbcTemplate jdbcTemplate;
    private final WechatNotifyService wechatNotifyService;
    private final DiningTimeSlotService diningTimeSlotService;

    public OrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                        DishMapper dishMapper, EmployeeMapper employeeMapper,
                        JdbcTemplate jdbcTemplate,
                        WechatNotifyService wechatNotifyService,
                        DiningTimeSlotService diningTimeSlotService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.dishMapper = dishMapper;
        this.employeeMapper = employeeMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.wechatNotifyService = wechatNotifyService;
        this.diningTimeSlotService = diningTimeSlotService;
    }

    /**
     * 校验订单核销时是否处于该餐次的就餐时段内。
     * 规则:订单日期必须是今天,且当前时间在 dining_time_slot 配置的 [startTime, endTime] 内。
     * 未配置时段的餐次拒绝核销(避免无配置即可任意核销)。
     * @throws BusinessException 不在就餐时段或订单日期不是今天
     */
    private void checkPickupTimeWindow(Order order) {
        LocalDate today = LocalDate.now(ZONE_SHANGHAI);
        if (order.getDate() == null || !order.getDate().equals(today)) {
            throw new BusinessException("仅支持核销当日订单");
        }
        LocalTime now = LocalTime.now(ZONE_SHANGHAI);
        if (!diningTimeSlotService.isWithinDiningTime(order.getStoreId(), order.getMealType(), now)) {
            DiningTimeSlot slot = diningTimeSlotService.getByStoreAndMealType(order.getStoreId(), order.getMealType());
            if (slot == null) {
                throw new BusinessException("该餐次未配置就餐时段,无法核销");
            }
            throw new BusinessException("未到用餐时间," + slot.getStartTime() + "-" + slot.getEndTime() + " 才可取餐");
        }
    }

    /**
     * 读取 sys_config 中的时间配置(HH:mm),默认 15:00。
     * @param key 配置键,如 order_deadline_time / cancel_deadline_time
     */
    private LocalTime getDeadlineTime(String key) {
        try {
            String v = jdbcTemplate.queryForObject(
                    "SELECT config_value FROM sys_config WHERE config_key = ?",
                    String.class, key);
            if (v != null && !v.isBlank()) {
                String[] parts = v.trim().split(":");
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
        } catch (Exception ignored) {
        }
        return LocalTime.of(15, 0);
    }

    /**
     * 校验订单时间窗(下单/取消共用):
     * 业务规则:订单日期 X 的截止时间是 (X-1) deadlineTime,即"前一天 15:00 前可操作"。
     * - 今天及之前:截止时间(昨天15:00)已过 → 拒绝
     * - 明天:截止时间是今天15:00 → 今天15:00前允许
     * - 后天及以后:截止时间在未来 → 允许
     * @param action "下单" 或 "取消",用于决定读哪个配置键
     */
    private void checkAdvanceOrderDeadline(LocalDate orderDate, String action) {
        LocalDate today = LocalDate.now(ZONE_SHANGHAI);
        if (orderDate == null) return;
        String key = "取消".equals(action) ? "cancel_deadline_time" : "order_deadline_time";
        LocalTime deadline = getDeadlineTime(key);
        LocalDateTime now = LocalDateTime.now(ZONE_SHANGHAI);

        if (!orderDate.isAfter(today)) {
            // 今天及之前:截止时间(前一天15:00)已过
            throw new BusinessException("订单需在前一天 " + deadline + " 之前" + action + ",已截止");
        }

        // 未来订单:检查是否已过前一天截止时间
        LocalDateTime deadlineAt = LocalDateTime.of(orderDate.minusDays(1), deadline);
        if (now.isAfter(deadlineAt)) {
            throw new BusinessException("次日订单需在前一天 " + deadline + " 之前" + action + ",已截止");
        }
    }

    // 已降级为默认隔离级别(REPEATABLE_READ),防重复下单由 selectByEmployeeDateMeal + 余额原子扣减保证
    @Transactional
    public Order createOrder(OrderCreateDTO dto) {
        // B13 时区
        LocalDate orderDate = dto.getDate() != null ? dto.getDate() : LocalDate.now(ZONE_SHANGHAI);
        Long employeeId = dto.getEmployeeId();
        Long storeId = dto.getStoreId();

        // 防御性校验(避免 NOT NULL 约束触发 500)
        if (employeeId == null) {
            throw new BusinessException("员工ID不能为空");
        }
        // P0-3 员工只能为自己下单,防止越权扣他人余额
        // 使用 isEmployee()(boolean) 而非 currentRole()==0,避免 mockStatic 环境下误触发
        if (SecurityContext.isEmployee()) {
            Long currentEmpId = SecurityContext.currentEmployeeId();
            if (currentEmpId == null) {
                throw new com.example.canteen.exception.SecurityException("登录态异常,请重新登录");
            }
            if (!currentEmpId.equals(dto.getEmployeeId())) {
                throw new com.example.canteen.exception.SecurityException("只能为自己下单");
            }
        }
        if (storeId == null) {
            throw new BusinessException("门店ID不能为空");
        }
        if (dto.getMealType() == null) {
            throw new BusinessException("餐次不能为空");
        }
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BusinessException("订单菜品不能为空");
        }

        // B12 多租户校验
        SecurityContext.checkStoreAccess(storeId);

        // 订单来源:0-正常订餐(默认), 1-未订餐用餐
        int orderSourceCode = dto.getOrderSource() != null ? dto.getOrderSource() : OrderSource.NORMAL.getCode();
        boolean isUnsolicited = orderSourceCode == OrderSource.UNSOLICITED.getCode();

        // 次日订单截止校验:未订餐用餐(现场加餐)绕过此校验
        if (!isUnsolicited) {
            checkAdvanceOrderDeadline(orderDate, "下单");
        }

        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        // 员工门店与订单门店必须一致
        if (employee.getStoreId() == null || !employee.getStoreId().equals(storeId)) {
            throw new BusinessException("员工不属于本门店");
        }

        // B6 防重复下单:未订餐用餐(现场加餐)绕过此校验,允许同一餐次多次下单
        if (!isUnsolicited) {
            Order existOrder = orderMapper.selectByEmployeeDateMeal(employeeId, orderDate, dto.getMealType());
            if (existOrder != null) {
                throw new BusinessException("今日该餐次已下单");
            }
        }

        // B3 库存/限购校验 + B5 菜品归属校验 + B8 菜品不存在抛异常 + 计算总价
        // P1 优化:用 selectBatchIds 一次性查询所有菜品,避免循环内 N+1 查询
        List<Long> dishIds = new ArrayList<>();
        for (OrderItemDTO itemDTO : dto.getItems()) {
            dishIds.add(itemDTO.getDishId());
        }
        List<Dish> dishes = dishMapper.selectBatchIds(dishIds);
        Map<Long, Dish> dishMap = new HashMap<>();
        for (Dish d : dishes) {
            dishMap.put(d.getId(), d);
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItemDTO itemDTO : dto.getItems()) {
            Dish dish = dishMap.get(itemDTO.getDishId());
            if (dish == null) {
                // B8 不静默跳过
                throw new BusinessException("菜品不存在:" + itemDTO.getDishId());
            }
            // B5 菜品归属校验
            if (dish.getStoreId() == null || !dish.getStoreId().equals(storeId)) {
                throw new BusinessException("菜品不属于本门店:" + dish.getName());
            }
            Integer quantity = itemDTO.getQuantity();
            if (quantity == null || quantity <= 0) {
                throw new BusinessException("购买数量必须大于0:" + dish.getName());
            }
            // P2-11 单次限购校验
            if (dish.getMaxPerOrder() != null && quantity > dish.getMaxPerOrder()) {
                throw new BusinessException("超过单次限购:" + dish.getName());
            }
            // 库存校验已移除(库存功能下线,保留会阻止 stock=0 菜品下单)
            totalAmount = totalAmount.add(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        // B1 余额原子扣减
        int balanceRows = employeeMapper.deductBalance(employeeId, totalAmount);
        if (balanceRows == 0) {
            throw new BusinessException("余额不足");
        }

        // 库存扣减已下线(前端不再提供库存功能);保留字段以兼容历史数据,默认 null 跳过

        // 创建订单(B7 订单号 + B3 取餐码)
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setStoreId(storeId);
        order.setEmployeeId(employeeId);
        order.setDate(orderDate);
        order.setMealType(dto.getMealType());
        order.setTotalAmount(totalAmount);
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setOrderSource(orderSourceCode);
        order.setPickupCode(generatePickupCode(storeId, orderDate));
        orderMapper.insert(order);

        for (OrderItemDTO itemDTO : dto.getItems()) {
            Dish dish = dishMap.get(itemDTO.getDishId());
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setDishId(dish.getId());
            item.setDishName(dish.getName());
            item.setPrice(dish.getPrice());
            item.setQuantity(itemDTO.getQuantity());
            orderItemMapper.insert(item);
        }

        // 微信公众号模板消息推送(含订单日期、餐次、金额、取餐码)
        // 事务提交后异步推送,失败仅记录日志,不影响下单主流程
        try {
            wechatNotifyService.notifyOrderCreated(order, employee);
        } catch (Exception e) {
            log.warn("微信订单通知推送异常: orderId={}, error={}", order.getId(), e.getMessage());
        }

        return order;
    }

    public List<Order> getOrdersByStore(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return orderMapper.selectByStoreId(storeId);
    }

    public List<Order> getOrdersByEmployee(Long employeeId) {
        List<Order> orders = orderMapper.selectByEmployeeId(employeeId);
        if (orders == null || orders.isEmpty()) {
            return orders;
        }
        // 批量查询订单菜品(避免 N+1),按 orderId 分组后填充到各订单
        List<Long> orderIds = orders.stream().map(Order::getId).distinct().toList();
        List<OrderItem> allItems = orderItemMapper.selectByOrderIds(orderIds);
        Map<Long, List<OrderItem>> itemMap = new HashMap<>();
        for (OrderItem it : allItems) {
            itemMap.computeIfAbsent(it.getOrderId(), k -> new ArrayList<>()).add(it);
        }
        for (Order o : orders) {
            o.setItems(itemMap.getOrDefault(o.getId(), List.of()));
        }
        return orders;
    }

    public Order getOrderById(Long id) {
        return orderMapper.selectById(id);
    }

    public Map<String, Object> getOrderDetail(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // B12 订单归属校验
        SecurityContext.checkStoreAccess(order.getStoreId());
        // 填充员工姓名供前端详情展示
        Employee emp = employeeMapper.selectById(order.getEmployeeId());
        if (emp != null) {
            order.setEmployeeName(emp.getName());
        }
        List<OrderItem> items = orderItemMapper.selectByOrderId(orderId);

        Map<String, Object> result = new HashMap<>();
        result.put("order", order);
        result.put("items", items);
        return result;
    }

    @Transactional
    public void completeOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // B12 订单归属校验
        SecurityContext.checkStoreAccess(order.getStoreId());
        // 就餐时段校验:只能在配置的 [startTime, endTime] 内核销当日订单
        checkPickupTimeWindow(order);
        // P0-4 原子状态更新:仅 status=1 可完成,防并发重复操作
        // 使用 UpdateWrapper+字符串列名(非 LambdaUpdateWrapper),兼容无 Spring 上下文的单元测试
        int rows = orderMapper.update(null, new UpdateWrapper<Order>()
                .eq("id", orderId)
                .eq("status", OrderStatus.PENDING.getCode())
                .set("status", OrderStatus.COMPLETED.getCode()));
        order.setStatus(OrderStatus.COMPLETED.getCode());
        if (rows == 0) {
            throw new BusinessException("订单状态已变更");
        }
    }

    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // B12 订单归属校验
        SecurityContext.checkStoreAccess(order.getStoreId());
        // B2 状态校验
        if (order.getStatus() == null || order.getStatus() != OrderStatus.PENDING.getCode()) {
            throw new BusinessException("订单状态不允许取消");
        }
        // 次日订单取消截止校验:前一天 15:00 之后不可取消次日
        checkAdvanceOrderDeadline(order.getDate(), "取消");

        // 预检员工账户:退款需要员工账户有效(软删除后 selectById 返回 null)
        // 提前抛异常,避免状态更新后再因退款失败回滚事务,导致提示与实际状态不符
        Employee employee = employeeMapper.selectById(order.getEmployeeId());
        if (employee == null) {
            throw new BusinessException("员工账户已失效,无法取消订单,请联系管理员处理");
        }

        // P0-4 原子状态更新:仅 status=1 可取消,防并发重复退款
        // 使用 UpdateWrapper+字符串列名,兼容单元测试
        int rows = orderMapper.update(null, new UpdateWrapper<Order>()
                .eq("id", orderId)
                .eq("status", OrderStatus.PENDING.getCode())
                .set("status", OrderStatus.CANCELED.getCode()));
        order.setStatus(OrderStatus.CANCELED.getCode());
        if (rows == 0) {
            throw new BusinessException("订单状态已变更");
        }

        // 退款(员工已预检存在,此处失败概率极低;若失败则事务回滚,订单状态恢复为 1)
        int refundRows = employeeMapper.addBalance(order.getEmployeeId(), order.getTotalAmount());
        if (refundRows == 0) {
            // 极端情况:预检通过但退款失败(如并发软删除),事务回滚,订单状态恢复
            log.error("退款失败:员工账户异常,orderId={}, employeeId={}, amount={}",
                    orderId, order.getEmployeeId(), order.getTotalAmount());
            throw new BusinessException("退款失败,订单未取消,请联系管理员处理");
        }
    }

    /**
     * 核销取餐:按取餐码查询订单(收口到当前门店+当天),校验后置为已完成。
     * 收口说明:取餐码仅保证「同店+当天」唯一,全局查询会在跨店/跨日碰撞时错核销他人订单。
     */
    @Transactional
    public Order pickup(String pickupCode) {
        Long storeId = SecurityContext.currentStoreId();
        LocalDate today = LocalDate.now(ZONE_SHANGHAI);
        Order order = orderMapper.selectByStoreDatePickupCode(storeId, today, pickupCode);
        if (order == null) {
            throw new BusinessException("取餐码无效");
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatus.PENDING.getCode()) {
            throw new BusinessException("订单状态不允许核销");
        }
        SecurityContext.checkStoreAccess(order.getStoreId());
        // 就餐时段校验:只能在配置的 [startTime, endTime] 内核销当日订单
        checkPickupTimeWindow(order);
        // P0-4 原子状态更新:仅 status=1 可核销,防并发重复核销
        // 使用 UpdateWrapper+字符串列名,兼容单元测试
        int rows = orderMapper.update(null, new UpdateWrapper<Order>()
                .eq("id", order.getId())
                .eq("status", OrderStatus.PENDING.getCode())
                .set("status", OrderStatus.COMPLETED.getCode()));
        if (rows == 0) {
            throw new BusinessException("订单状态已变更");
        }
        order.setStatus(OrderStatus.COMPLETED.getCode());
        return order;
    }

    /**
     * 标记超时未核销订单为"未就餐"(status=4)。
     * 由定时任务 OrderStatusScheduler 每分钟调用:
     * 扫描所有 status=1 且订单日期<=今天 的订单,若该订单餐次的就餐时段已过,则标记为 4。
     * 幂等:仅 status=1 才更新,重复执行无副作用。
     * @return 本次标记的订单数
     */
    @Transactional
    public int markExpiredOrdersAsMissed() {
        LocalDate today = LocalDate.now(ZONE_SHANGHAI);
        LocalTime now = LocalTime.now(ZONE_SHANGHAI);
        // 查 status=1 且日期在 [今天-7天, 今天] 范围内的订单。
        // 加 7 天下限:避免定时任务长期挂掉后恢复时全表扫描大量历史订单(7天前的订单
        // 即使被标记也无实际意义,且可能影响性能)。正常情况下 pending 订单不会有 7 天前的。
        LocalDate scanFrom = today.minusDays(7);
        List<Order> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .ge(Order::getDate, scanFrom)
                .le(Order::getDate, today));
        if (pendingOrders == null || pendingOrders.isEmpty()) {
            return 0;
        }
        int marked = 0;
        for (Order order : pendingOrders) {
            // 订单日期早于今天:全天所有餐次都已过,直接标记
            // 订单日期是今天:按该餐次 endTime 判断
            boolean shouldMark;
            if (order.getDate().isBefore(today)) {
                shouldMark = true;
            } else {
                shouldMark = diningTimeSlotService.isDiningTimePassed(order.getStoreId(), order.getMealType(), now);
            }
            if (shouldMark) {
                int rows = orderMapper.update(null, new UpdateWrapper<Order>()
                        .eq("id", order.getId())
                        .eq("status", OrderStatus.PENDING.getCode())
                        .set("status", OrderStatus.MISSED.getCode()));
                if (rows > 0) {
                    marked++;
                    log.info("订单超时未核销标记为未就餐: orderId={}, date={}, mealType={}",
                            order.getId(), order.getDate(), order.getMealType());
                }
            }
        }
        if (marked > 0) {
            log.info("本次标记未就餐订单 {} 单", marked);
        }
        return marked;
    }

    /**
     * B7 订单号:基于 UUID 保证唯一性
     */
    private String generateOrderNo() {
        return "ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /**
     * B3 6 位取餐码(P1-4 从 4 位改为 6 位降低暴力破解风险;P2-6 使用 SecureRandom 防可预测)
     * 生成时在「同店+当天」范围内查重重试,避免碰撞导致无法核销/错核销;
     * 数据库层由 uk_order_store_date_pickup 唯一索引兜底(V18)。
     */
    private String generatePickupCode(Long storeId, LocalDate date) {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 10; i++) {
            String code = String.format("%06d", random.nextInt(1000000));
            if (orderMapper.selectByStoreDatePickupCode(storeId, date, code) == null) {
                return code;
            }
        }
        // 连续 10 次碰撞(理论上几乎不可能)仍放行,由唯一索引兜底,插入冲突时事务回滚提示重试
        return String.format("%06d", random.nextInt(1000000));
    }

    public Map<String, Object> getDashboardStats(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        Map<String, Object> stats = new HashMap<>();

        // B13 时区
        LocalDate today = LocalDate.now(ZONE_SHANGHAI);
        List<Order> todayOrders = orderMapper.selectByStoreDate(storeId, today);
        // P2-4 排除已取消订单(status==3)
        todayOrders = todayOrders.stream().filter(o -> o.getStatus() != null && o.getStatus() != OrderStatus.CANCELED.getCode()).toList();

        long totalOrders = todayOrders.size();
        BigDecimal totalRevenue = todayOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingOrders = todayOrders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.PENDING.getCode())
                .count();

        long completedOrders = todayOrders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == OrderStatus.COMPLETED.getCode())
                .count();

        double completionRate = totalOrders > 0 ? (completedOrders * 100.0 / totalOrders) : 0;

        stats.put("todayOrders", totalOrders);
        stats.put("todayRevenue", totalRevenue);
        stats.put("completionRate", Math.round(completionRate));
        stats.put("pendingOrders", pendingOrders);
        stats.put("completedOrders", completedOrders);

        Map<String, Long> mealTypeStats = new HashMap<>();
        mealTypeStats.put(MealType.BREAKFAST.getEnglishKey(), todayOrders.stream().filter(o -> o.getMealType() != null && o.getMealType() == MealType.BREAKFAST.getCode()).count());
        mealTypeStats.put(MealType.LUNCH.getEnglishKey(), todayOrders.stream().filter(o -> o.getMealType() != null && o.getMealType() == MealType.LUNCH.getCode()).count());
        mealTypeStats.put(MealType.DINNER.getEnglishKey(), todayOrders.stream().filter(o -> o.getMealType() != null && o.getMealType() == MealType.DINNER.getCode()).count());
        stats.put("mealTypeStats", mealTypeStats);

        // 最近7天趋势:每天的订单数与营业额(排除已取消订单 status=3)
        LocalDate trendStart = today.minusDays(6);
        List<Order> weekOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStoreId, storeId)
                .ge(Order::getDate, trendStart)
                .le(Order::getDate, today));
        if (weekOrders == null) {
            weekOrders = new ArrayList<>();
        }
        List<Order> effectiveWeekOrders = weekOrders.stream()
                .filter(o -> o.getStatus() == null || o.getStatus() != OrderStatus.CANCELED.getCode())
                .toList();
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            LocalDate day = d;
            List<Order> dayOrders = effectiveWeekOrders.stream()
                    .filter(o -> o.getDate() != null && o.getDate().equals(day))
                    .toList();
            BigDecimal dayRevenue = dayOrders.stream()
                    .map(Order::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> dayStat = new HashMap<>();
            dayStat.put("date", d.toString());
            dayStat.put("orderCount", dayOrders.size());
            dayStat.put("revenue", dayRevenue);
            trend.add(dayStat);
        }
        stats.put("trend", trend);

        return stats;
    }
}
