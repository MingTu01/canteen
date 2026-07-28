package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.canteen.dto.OrderCreateDTO;
import com.example.canteen.dto.OrderItemDTO;
import com.example.canteen.entity.Dish;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.MealType;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.OrderItem;
import com.example.canteen.entity.OrderStatus;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.OrderItemMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单服务单元测试
 */
@DisplayName("订单服务测试")
class OrderServiceTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private DishMapper dishMapper;
    private EmployeeMapper employeeMapper;
    private JdbcTemplate jdbcTemplate;
    private OrderService orderService;

    private Employee testEmployee;
    private Dish testDish1;
    private Dish testDish2;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        dishMapper = mock(DishMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        // 订单截止时间配置:默认 15:00(测试用例均使用今日日期,实际不会被读取)
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString()))
                .thenReturn("15:00");
        orderService = new OrderService(orderMapper, orderItemMapper, dishMapper, employeeMapper, jdbcTemplate);

        testEmployee = new Employee();
        testEmployee.setId(1L);
        testEmployee.setStoreId(1L);
        testEmployee.setCardNo("CARD001");
        testEmployee.setName("张明");
        testEmployee.setBalance(new BigDecimal("500.00"));
        testEmployee.setStatus(1);

        testDish1 = new Dish();
        testDish1.setId(1L);
        testDish1.setStoreId(1L);
        testDish1.setName("红烧排骨");
        testDish1.setPrice(new BigDecimal("15.00"));
        testDish1.setStatus(1);
        testDish1.setStock(100);
        testDish1.setMaxPerOrder(5);

        testDish2 = new Dish();
        testDish2.setId(2L);
        testDish2.setStoreId(1L);
        testDish2.setName("宫保鸡丁");
        testDish2.setPrice(new BigDecimal("12.00"));
        testDish2.setStatus(1);
        testDish2.setStock(100);
        testDish2.setMaxPerOrder(5);
    }

    @Test
    @DisplayName("创建订单 - 正常流程")
    void createOrder_Success() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setEmployeeId(1L);
        dto.setStoreId(1L);
        dto.setDate(LocalDate.now());
        dto.setMealType(MealType.LUNCH.getCode());
        dto.setItems(Arrays.asList(
                createItemDTO(1L, 2),
                createItemDTO(2L, 1)
        ));

        when(employeeMapper.selectById(1L)).thenReturn(testEmployee);
        when(orderMapper.selectByEmployeeDateMeal(eq(1L), any(LocalDate.class), eq(MealType.LUNCH.getCode()))).thenReturn(null);
        // OrderService 使用 selectBatchIds 批量查询菜品
        when(dishMapper.selectBatchIds(anyList())).thenReturn(Arrays.asList(testDish1, testDish2));
        when(employeeMapper.deductBalance(eq(1L), any(BigDecimal.class))).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(100L);
            return 1;
        });
        when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Order result = orderService.createOrder(dto);

            assertNotNull(result);
            assertNotNull(result.getOrderNo());
            assertEquals(1L, result.getStoreId());
            assertEquals(1L, result.getEmployeeId());
            assertEquals(OrderStatus.PENDING.getCode(), result.getStatus());
            assertNotNull(result.getPickupCode());
            // 总金额：15*2 + 12*1 = 42
            assertEquals(new BigDecimal("42.00"), result.getTotalAmount());

            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(employeeMapper).deductBalance(eq(1L), any(BigDecimal.class));
        verify(orderMapper).insert(any(Order.class));
        verify(orderItemMapper, times(2)).insert(any(OrderItem.class));
    }

    @Test
    @DisplayName("创建订单 - 员工不存在")
    void createOrder_EmployeeNotFound() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setEmployeeId(999L);
        dto.setStoreId(1L);
        dto.setDate(LocalDate.now());
        dto.setMealType(MealType.LUNCH.getCode());
        dto.setItems(List.of(createItemDTO(1L, 1)));

        when(employeeMapper.selectById(999L)).thenReturn(null);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> orderService.createOrder(dto));
            assertEquals("员工不存在", exception.getMessage());
        }

        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("创建订单 - 餐次为空应拒绝(防御性校验)")
    void createOrder_MealTypeNull() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setEmployeeId(1L);
        dto.setStoreId(1L);
        dto.setDate(LocalDate.now());
        dto.setItems(List.of(createItemDTO(1L, 1)));
        // mealType 未设置

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderService.createOrder(dto));
        assertEquals("餐次不能为空", exception.getMessage());

        verify(orderMapper, never()).insert(any(Order.class));
        verify(employeeMapper, never()).deductBalance(anyLong(), any(BigDecimal.class));
    }

    @Test
    @DisplayName("创建订单 - 菜品列表为空应拒绝")
    void createOrder_EmptyItems() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setEmployeeId(1L);
        dto.setStoreId(1L);
        dto.setMealType(MealType.LUNCH.getCode());
        dto.setItems(List.of());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderService.createOrder(dto));
        assertEquals("订单菜品不能为空", exception.getMessage());

        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("创建订单 - 余额不足")
    void createOrder_InsufficientBalance() {
        testEmployee.setBalance(new BigDecimal("10.00"));

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setEmployeeId(1L);
        dto.setStoreId(1L);
        dto.setDate(LocalDate.now());
        dto.setMealType(MealType.LUNCH.getCode());
        dto.setItems(List.of(createItemDTO(1L, 2))); // 15*2=30 > 10

        when(employeeMapper.selectById(1L)).thenReturn(testEmployee);
        when(orderMapper.selectByEmployeeDateMeal(eq(1L), any(LocalDate.class), eq(MealType.LUNCH.getCode()))).thenReturn(null);
        // OrderService 使用 selectBatchIds 批量查询菜品
        when(dishMapper.selectBatchIds(anyList())).thenReturn(List.of(testDish1));
        when(employeeMapper.deductBalance(eq(1L), any(BigDecimal.class))).thenReturn(0);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> orderService.createOrder(dto));
            assertEquals("余额不足", exception.getMessage());
        }

        verify(orderMapper, never()).insert(any(Order.class));
        verify(employeeMapper, never()).addBalance(anyLong(), any(BigDecimal.class));
    }

    @Test
    @DisplayName("取消订单 - 应退还余额")
    void cancelOrder_ShouldRefund() {
        Order order = new Order();
        order.setId(1L);
        order.setStoreId(1L);
        order.setEmployeeId(1L);
        order.setTotalAmount(new BigDecimal("42.00"));
        order.setStatus(OrderStatus.PENDING.getCode());

        when(orderMapper.selectById(1L)).thenReturn(order);
        // cancelOrder 新增员工账户预检,需 mock 返回有效员工
        when(employeeMapper.selectById(1L)).thenReturn(testEmployee);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(employeeMapper.addBalance(eq(1L), eq(new BigDecimal("42.00")))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            orderService.cancelOrder(1L);

            assertEquals(OrderStatus.CANCELED.getCode(), order.getStatus());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(orderMapper).update(isNull(), any(UpdateWrapper.class));
        verify(employeeMapper).addBalance(1L, new BigDecimal("42.00"));
    }

    @Test
    @DisplayName("完成订单 - 状态更新为已完成")
    void completeOrder_StatusUpdated() {
        Order order = new Order();
        order.setId(1L);
        order.setStoreId(1L);
        order.setStatus(OrderStatus.PENDING.getCode());

        when(orderMapper.selectById(1L)).thenReturn(order);
        when(orderMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            orderService.completeOrder(1L);

            assertEquals(OrderStatus.COMPLETED.getCode(), order.getStatus());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(orderMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    @DisplayName("获取订单详情 - 包含订单和菜品列表")
    void getOrderDetail_ReturnsOrderAndItems() {
        Order order = new Order();
        order.setId(1L);
        order.setStoreId(1L);
        order.setOrderNo("ORD20260717100001");
        order.setTotalAmount(new BigDecimal("42.00"));
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setEmployeeId(1L);

        OrderItem item1 = new OrderItem();
        item1.setId(1L);
        item1.setOrderId(1L);
        item1.setDishId(1L);
        item1.setDishName("红烧排骨");
        item1.setPrice(new BigDecimal("15.00"));
        item1.setQuantity(2);

        OrderItem item2 = new OrderItem();
        item2.setId(2L);
        item2.setOrderId(1L);
        item2.setDishId(2L);
        item2.setDishName("宫保鸡丁");
        item2.setPrice(new BigDecimal("12.00"));
        item2.setQuantity(1);

        when(orderMapper.selectById(1L)).thenReturn(order);
        when(employeeMapper.selectById(1L)).thenReturn(testEmployee);
        when(orderItemMapper.selectByOrderId(1L)).thenReturn(Arrays.asList(item1, item2));

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Map<String, Object> result = orderService.getOrderDetail(1L);

            assertNotNull(result);
            assertEquals(order, result.get("order"));
            @SuppressWarnings("unchecked")
            List<OrderItem> items = (List<OrderItem>) result.get("items");
            assertEquals(2, items.size());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }
    }

    @Test
    @DisplayName("仪表盘统计 - 返回正确的统计数据")
    void getDashboardStats_ReturnsCorrectStats() {
        Order order1 = new Order();
        order1.setStatus(OrderStatus.PENDING.getCode());
        order1.setMealType(MealType.BREAKFAST.getCode());
        order1.setTotalAmount(new BigDecimal("20.00"));

        Order order2 = new Order();
        order2.setStatus(OrderStatus.COMPLETED.getCode());
        order2.setMealType(MealType.LUNCH.getCode());
        order2.setTotalAmount(new BigDecimal("30.00"));

        Order order3 = new Order();
        order3.setStatus(OrderStatus.COMPLETED.getCode());
        order3.setMealType(MealType.LUNCH.getCode());
        order3.setTotalAmount(new BigDecimal("15.00"));

        when(orderMapper.selectByStoreDate(eq(1L), any(LocalDate.class)))
                .thenReturn(Arrays.asList(order1, order2, order3));
        // getDashboardStats 会查询最近 7 天趋势,需 mock selectList 返回空列表
        when(orderMapper.selectList(any())).thenReturn(List.of());

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Map<String, Object> stats = orderService.getDashboardStats(1L);

            assertNotNull(stats);
            assertEquals(3L, stats.get("todayOrders"));
            assertEquals(new BigDecimal("65.00"), stats.get("todayRevenue"));
            assertEquals(1L, stats.get("pendingOrders"));
            assertEquals(2L, stats.get("completedOrders"));
            assertEquals(67L, stats.get("completionRate"));

            @SuppressWarnings("unchecked")
            Map<String, Long> mealTypeStats = (Map<String, Long>) stats.get("mealTypeStats");
            assertEquals(1L, mealTypeStats.get("breakfast"));
            assertEquals(2L, mealTypeStats.get("lunch"));
            assertEquals(0L, mealTypeStats.get("dinner"));
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }
    }

    private OrderItemDTO createItemDTO(Long dishId, int quantity) {
        OrderItemDTO item = new OrderItemDTO();
        item.setDishId(dishId);
        item.setQuantity(quantity);
        return item;
    }
}
