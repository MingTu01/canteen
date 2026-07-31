package com.example.canteen.service;

import com.example.canteen.entity.Dish;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * DishService 缓存失效测试
 *
 * 覆盖本次 P1 修复:cacheEvictByStore 补充清除 :all 菜品缓存
 * 确保终端获取的全量菜品缓存(含已下架)在菜品变更后正确失效
 */
@DisplayName("菜品缓存失效测试")
class DishServiceCacheTest {

    private DishMapper dishMapper;
    private DishService dishService;
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dishMapper = mock(DishMapper.class);
        redisTemplate = mock(RedisTemplate.class);

        ObjectProvider<RedisTemplate<String, Object>> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        ObjectProvider<SseService> sseProvider = mock(ObjectProvider.class);
        when(sseProvider.getIfAvailable()).thenReturn(null);

        dishService = new DishService(dishMapper, redisProvider, sseProvider);

        // mock dishMapper.selectById 返回测试菜品
        Dish testDish = new Dish();
        testDish.setId(1L);
        testDish.setStoreId(1L);
        testDish.setName("红烧排骨");
        testDish.setPrice(new BigDecimal("15.00"));
        testDish.setStatus(1);
        testDish.setIsDeleted(0);
        when(dishMapper.selectById(1L)).thenReturn(testDish);
    }

    @Test
    @DisplayName("删除菜品时 - :all 缓存 key 应被清除")
    void deleteDish_clearsAllCacheKey() {
        // mock SecurityContext.checkStoreAccess(不抛异常)
        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            mocked.when(() -> SecurityContext.checkStoreAccess(any())).thenAnswer(inv -> null);

            dishService.deleteDish(1L);
        }

        // 捕获所有 delete 调用的 key
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, atLeastOnce()).delete(keyCaptor.capture());

        // 验证 :all key 被清除
        assertTrue(keyCaptor.getAllValues().stream().anyMatch(k -> k.endsWith(":all")),
                "缓存失效应包含 :all key,实际清除的 keys: " + keyCaptor.getAllValues());
    }

    @Test
    @DisplayName("更新菜品时 - :all 缓存 key 应被清除")
    void updateDish_clearsAllCacheKey() {
        Dish update = new Dish();
        update.setId(1L);
        update.setStoreId(1L);
        update.setName("红烧排骨(改)");
        update.setPrice(new BigDecimal("16.00"));
        update.setStatus(1);

        // mock SecurityContext.checkStoreAccess(不抛异常)
        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            mocked.when(() -> SecurityContext.checkStoreAccess(any())).thenAnswer(inv -> null);

            dishService.updateDish(update);
        }

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, atLeastOnce()).delete(keyCaptor.capture());

        assertTrue(keyCaptor.getAllValues().stream().anyMatch(k -> k.endsWith(":all")),
                "缓存失效应包含 :all key,实际清除的 keys: " + keyCaptor.getAllValues());
    }

    @Test
    @DisplayName("切换菜品状态时 - :all 缓存 key 应被清除")
    void toggleStatus_clearsAllCacheKey() {
        try (MockedStatic<SecurityContext> mocked = mockStatic(SecurityContext.class)) {
            mocked.when(() -> SecurityContext.checkStoreAccess(any())).thenAnswer(inv -> null);

            dishService.toggleStatus(1L);
        }

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, atLeastOnce()).delete(keyCaptor.capture());

        assertTrue(keyCaptor.getAllValues().stream().anyMatch(k -> k.endsWith(":all")),
                "缓存失效应包含 :all key,实际清除的 keys: " + keyCaptor.getAllValues());
    }
}
