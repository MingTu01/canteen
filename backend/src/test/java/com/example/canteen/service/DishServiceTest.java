package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.entity.Dish;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 菜品服务单元测试
 */
@DisplayName("菜品服务测试")
class DishServiceTest {

    private DishMapper dishMapper;
    private DishService dishService;

    private Dish testDish1;
    private Dish testDish2;
    private Dish newDish;

    @BeforeEach
    void setUp() {
        dishMapper = mock(DishMapper.class);
        dishService = new DishService(dishMapper);

        testDish1 = new Dish();
        testDish1.setId(1L);
        testDish1.setStoreId(1L);
        testDish1.setName("红烧排骨");
        testDish1.setPrice(new BigDecimal("15.00"));
        testDish1.setCategory("荤菜");
        testDish1.setIsNew(0);
        testDish1.setStatus(1);
        testDish1.setStock(100);
        testDish1.setMaxPerOrder(5);
        testDish1.setIsDeleted(0);

        testDish2 = new Dish();
        testDish2.setId(2L);
        testDish2.setStoreId(1L);
        testDish2.setName("绿豆沙");
        testDish2.setPrice(new BigDecimal("3.00"));
        testDish2.setCategory("饮品");
        testDish2.setIsNew(1);
        testDish2.setStatus(1);
        testDish2.setStock(100);
        testDish2.setMaxPerOrder(5);
        testDish2.setIsDeleted(0);

        newDish = new Dish();
        newDish.setStoreId(1L);
        newDish.setName("宫保鸡丁");
        newDish.setPrice(new BigDecimal("12.00"));
        newDish.setCategory("荤菜");
        newDish.setIsNew(0);
        newDish.setStatus(1);
    }

    @Test
    @DisplayName("获取门店菜品列表")
    void getDishesByStore_ReturnsList() {
        when(dishMapper.selectByStoreId(1L)).thenReturn(Arrays.asList(testDish1, testDish2));

        List<Dish> result = dishService.getDishesByStore(1L);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("红烧排骨", result.get(0).getName());
        verify(dishMapper).selectByStoreId(1L);
    }

    @Test
    @DisplayName("获取门店新品菜品")
    void getNewDishesByStore_ReturnsOnlyNewDishes() {
        when(dishMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(testDish2));

        List<Dish> result = dishService.getNewDishesByStore(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("绿豆沙", result.get(0).getName());
        assertEquals(1, result.get(0).getIsNew());
    }

    @Test
    @DisplayName("根据ID获取菜品")
    void getDishById_ReturnsDish() {
        when(dishMapper.selectById(1L)).thenReturn(testDish1);

        Dish result = dishService.getDishById(1L);

        assertNotNull(result);
        assertEquals("红烧排骨", result.getName());
        assertEquals(new BigDecimal("15.00"), result.getPrice());
    }

    @Test
    @DisplayName("创建菜品")
    void createDish_Success() {
        when(dishMapper.insert(any(Dish.class))).thenAnswer(invocation -> {
            Dish dish = invocation.getArgument(0);
            dish.setId(10L);
            return 1;
        });

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Dish result = dishService.createDish(newDish);

            assertNotNull(result);
            assertEquals(10L, result.getId());
            assertEquals("宫保鸡丁", result.getName());
            assertEquals(0, result.getIsDeleted());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(dishMapper).insert(newDish);
    }

    @Test
    @DisplayName("更新菜品")
    void updateDish_Success() {
        testDish1.setPrice(new BigDecimal("18.00"));
        when(dishMapper.selectById(1L)).thenReturn(testDish1);
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Dish result = dishService.updateDish(testDish1);

            assertNotNull(result);
            assertEquals(new BigDecimal("18.00"), result.getPrice());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(dishMapper).updateById(testDish1);
    }

    @Test
    @DisplayName("删除菜品 - 软删除(is_deleted=1)")
    void deleteDish_SoftDelete() {
        when(dishMapper.selectById(1L)).thenReturn(testDish1);
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            dishService.deleteDish(1L);

            assertEquals(1, testDish1.getIsDeleted());
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(dishMapper).updateById(testDish1);
        verify(dishMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除菜品 - 菜品不存在抛异常")
    void deleteDish_NotFound() {
        when(dishMapper.selectById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> dishService.deleteDish(999L));
        assertEquals("菜品不存在", exception.getMessage());

        verify(dishMapper, never()).updateById(any(Dish.class));
    }

    @Test
    @DisplayName("上下架状态切换")
    void toggleStatus_Success() {
        when(dishMapper.selectById(1L)).thenReturn(testDish1);
        when(dishMapper.updateById(any(Dish.class))).thenReturn(1);

        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            Dish result = dishService.toggleStatus(1L);

            assertNotNull(result);
            assertEquals(0, result.getStatus()); // 1 -> 0
            mocked.verify(() -> SecurityContext.checkStoreAccess(1L));
        }

        verify(dishMapper).updateById(testDish1);
    }

    @Test
    @DisplayName("获取空菜品列表 - 门店无菜品")
    void getDishesByStore_EmptyList() {
        when(dishMapper.selectByStoreId(999L)).thenReturn(List.of());

        List<Dish> result = dishService.getDishesByStore(999L);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
