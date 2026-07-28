package com.example.canteen.service;

import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Order;
import com.example.canteen.entity.OrderItem;
import com.example.canteen.entity.OrderStatus;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.OrderItemMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ReportQueryHelper 单元测试。
 * 覆盖:区间查询、热销菜品聚合、金额求和、员工余额求和。
 */
@DisplayName("报表查询辅助类测试")
class ReportQueryHelperTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private RechargeRecordMapper rechargeRecordMapper;
    private EmployeeMapper employeeMapper;
    private ReportQueryHelper helper;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        rechargeRecordMapper = mock(RechargeRecordMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        helper = new ReportQueryHelper(orderMapper, orderItemMapper, rechargeRecordMapper, employeeMapper);
    }

    @Test
    @DisplayName("findOrdersByRange - 应委托 orderMapper 并返回结果")
    void findOrdersByRange_delegatesToMapper() {
        Order o = new Order();
        o.setId(1L);
        List<Order> expected = List.of(o);
        when(orderMapper.selectList(any())).thenReturn(expected);

        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 2, 0, 0);
        List<Order> result = helper.findOrdersByRange(1L, start, end);

        assertSame(expected, result);
        verify(orderMapper).selectList(any());
    }

    @Test
    @DisplayName("findRechargesByRange - 应委托 rechargeRecordMapper")
    void findRechargesByRange_delegatesToMapper() {
        RechargeRecord r = new RechargeRecord();
        r.setId(1L);
        List<RechargeRecord> expected = List.of(r);
        when(rechargeRecordMapper.selectList(any())).thenReturn(expected);

        List<RechargeRecord> result = helper.findRechargesByRange(1L,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1));

        assertSame(expected, result);
        verify(rechargeRecordMapper).selectList(any());
    }

    @Test
    @DisplayName("findCompletedOrdersByRange - 应委托 orderMapper")
    void findCompletedOrdersByRange_delegatesToMapper() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        helper.findCompletedOrdersByRange(1L, LocalDateTime.now(), LocalDateTime.now().plusDays(1));

        verify(orderMapper).selectList(any());
    }

    @Test
    @DisplayName("findActiveOrdersByRange - 应委托 orderMapper")
    void findActiveOrdersByRange_delegatesToMapper() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        helper.findActiveOrdersByRange(1L, LocalDateTime.now(), LocalDateTime.now().plusDays(1));

        verify(orderMapper).selectList(any());
    }

    @Test
    @DisplayName("topDishes - 订单列表为空时应返回空列表,不查询数据库")
    void topDishes_emptyOrders_returnsEmptyWithoutDbHit() {
        List<ReportQueryHelper.DishAgg> result = helper.topDishes(List.of(), 5);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verifyNoInteractions(orderItemMapper);
    }

    @Test
    @DisplayName("topDishes - 应按销量降序聚合")
    void topDishes_aggregatesByQuantityDesc() {
        Order o1 = new Order();
        o1.setId(10L);
        Order o2 = new Order();
        o2.setId(11L);

        OrderItem i1 = new OrderItem();
        i1.setDishId(1L);
        i1.setDishName("红烧排骨");
        i1.setQuantity(3);

        OrderItem i2 = new OrderItem();
        i2.setDishId(2L);
        i2.setDishName("宫保鸡丁");
        i2.setQuantity(6);

        OrderItem i3 = new OrderItem();
        i3.setDishId(1L);
        i3.setDishName("红烧排骨");
        i3.setQuantity(2);

        when(orderItemMapper.selectList(any())).thenReturn(Arrays.asList(i1, i2, i3));

        List<ReportQueryHelper.DishAgg> result = helper.topDishes(Arrays.asList(o1, o2), 10);

        assertEquals(2, result.size());
        // 宫保鸡丁 6 份应排第一
        assertEquals("宫保鸡丁", result.get(0).name);
        assertEquals(6, result.get(0).quantity);
        // 红烧排骨 3+2=5 份应排第二
        assertEquals("红烧排骨", result.get(1).name);
        assertEquals(5, result.get(1).quantity);
    }

    @Test
    @DisplayName("topDishes - 应按 limit 截断结果")
    void topDishes_respectsLimit() {
        Order o1 = new Order();
        o1.setId(10L);

        OrderItem i1 = new OrderItem();
        i1.setDishId(1L);
        i1.setDishName("A");
        i1.setQuantity(1);

        OrderItem i2 = new OrderItem();
        i2.setDishId(2L);
        i2.setDishName("B");
        i2.setQuantity(2);

        OrderItem i3 = new OrderItem();
        i3.setDishId(3L);
        i3.setDishName("C");
        i3.setQuantity(3);

        when(orderItemMapper.selectList(any())).thenReturn(Arrays.asList(i1, i2, i3));

        List<ReportQueryHelper.DishAgg> result = helper.topDishes(List.of(o1), 2);

        assertEquals(2, result.size());
        // 取销量最高的前 2 名:C(3) 与 B(2)
        assertEquals("C", result.get(0).name);
        assertEquals(3, result.get(0).quantity);
        assertEquals("B", result.get(1).name);
        assertEquals(2, result.get(1).quantity);
    }

    @Test
    @DisplayName("sumAmount - 应忽略 null amount")
    void sumAmount_skipsNullAmount() {
        RechargeRecord r1 = new RechargeRecord();
        r1.setAmount(new BigDecimal("100.00"));
        RechargeRecord r2 = new RechargeRecord();
        r2.setAmount(null); // 应被忽略
        RechargeRecord r3 = new RechargeRecord();
        r3.setAmount(new BigDecimal("50.00"));

        BigDecimal total = helper.sumAmount(Arrays.asList(r1, r2, r3));

        assertEquals(new BigDecimal("150.00"), total);
    }

    @Test
    @DisplayName("sumAmount - 空列表应返回 ZERO")
    void sumAmount_emptyList_returnsZero() {
        assertEquals(BigDecimal.ZERO, helper.sumAmount(List.of()));
    }

    @Test
    @DisplayName("sumEmployeeBalance - 应忽略 null balance")
    void sumEmployeeBalance_skipsNullBalance() {
        Employee e1 = new Employee();
        e1.setBalance(new BigDecimal("200.00"));
        Employee e2 = new Employee();
        e2.setBalance(null); // 应按 ZERO 处理
        Employee e3 = new Employee();
        e3.setBalance(new BigDecimal("300.00"));

        when(employeeMapper.selectList(any())).thenReturn(Arrays.asList(e1, e2, e3));

        BigDecimal total = helper.sumEmployeeBalance(1L);

        assertEquals(new BigDecimal("500.00"), total);
        verify(employeeMapper).selectList(any());
    }

    @Test
    @DisplayName("sumEmployeeBalance - 空员工列表应返回 ZERO")
    void sumEmployeeBalance_emptyList_returnsZero() {
        when(employeeMapper.selectList(any())).thenReturn(List.of());

        assertEquals(BigDecimal.ZERO, helper.sumEmployeeBalance(1L));
    }

    @Test
    @DisplayName("DishAgg - 初始 quantity 应为 0")
    void dishAgg_initialQuantityIsZero() {
        ReportQueryHelper.DishAgg agg = new ReportQueryHelper.DishAgg("测试菜品");
        assertEquals("测试菜品", agg.name);
        assertEquals(0, agg.quantity);
    }

    @Test
    @DisplayName("OrderStatus 枚举 - code 值与数据库 schema 一致")
    void orderStatus_codesMatchSchema() {
        assertEquals(1, OrderStatus.PENDING.getCode());
        assertEquals(2, OrderStatus.COMPLETED.getCode());
        assertEquals(3, OrderStatus.CANCELED.getCode());
        assertEquals(OrderStatus.PENDING, OrderStatus.fromCode(1));
        assertEquals(OrderStatus.COMPLETED, OrderStatus.fromCode(2));
        assertEquals(OrderStatus.CANCELED, OrderStatus.fromCode(3));
        assertNull(OrderStatus.fromCode(null));
        assertNull(OrderStatus.fromCode(99));
    }
}
