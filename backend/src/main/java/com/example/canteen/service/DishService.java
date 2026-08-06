package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Dish;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 菜品服务,带 Redis 缓存 + SSE 广播。
 *
 * 缓存策略:
 * - getDishesByStore 走 Redis,TTL 30 分钟
 * - 写操作(create/update/delete/toggle/batch)主动失效缓存 + 广播 SSE
 *
 * 容错:
 * - Redis 不可用(dev 环境)时自动降级为直查数据库,不影响业务
 * - SSE 广播失败不影响主流程
 */
@Slf4j
@Service
public class DishService {

    private static final String CACHE_KEY_DISHES = "dish:store:%s:list";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final DishMapper dishMapper;
    /** 使用 ObjectProvider 支持 dev 环境无 Redis 的降级 */
    private final ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider;
    private final ObjectProvider<SseService> sseServiceProvider;

    public DishService(DishMapper dishMapper,
                       ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider,
                       ObjectProvider<SseService> sseServiceProvider) {
        this.dishMapper = dishMapper;
        this.redisTemplateProvider = redisTemplateProvider;
        this.sseServiceProvider = sseServiceProvider;
    }

    /* ============ 缓存读写工具(降级安全) ============ */

    private RedisTemplate<String, Object> redis() {
        try {
            return redisTemplateProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T cacheGet(String key, Class<T> type) {
        RedisTemplate<String, Object> tpl = redis();
        if (tpl == null) return null;
        try {
            Object v = tpl.opsForValue().get(key);
            return v == null ? null : (T) v;
        } catch (Exception e) {
            log.warn("Redis 读取失败,降级直查 DB:{}", e.getMessage());
            return null;
        }
    }

    private void cachePut(String key, Object value) {
        RedisTemplate<String, Object> tpl = redis();
        if (tpl == null || value == null) return;
        try {
            tpl.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 写入失败:{}", e.getMessage());
        }
    }

    private void cacheEvictByStore(Long storeId) {
        RedisTemplate<String, Object> tpl = redis();
        if (tpl == null) return;
        try {
            tpl.delete(String.format(CACHE_KEY_DISHES, storeId));
            // 同时清除全量菜品缓存(含已下架菜品,供终端使用)
            tpl.delete(String.format(CACHE_KEY_DISHES, storeId) + ":all");
        } catch (Exception e) {
            log.warn("Redis 失效失败:{}", e.getMessage());
        }
    }

    private void broadcastSse(Long storeId, Long dishId, String action) {
        try {
            SseService sse = sseServiceProvider.getIfAvailable();
            if (sse != null) sse.broadcastDishChanged(storeId, dishId, action);
        } catch (Exception e) {
            log.debug("SSE 广播失败:{}", e.getMessage());
        }
    }

    private void broadcastSseBatch(Long storeId) {
        try {
            SseService sse = sseServiceProvider.getIfAvailable();
            if (sse != null) sse.broadcastDishBatchChanged(storeId);
        } catch (Exception e) {
            log.debug("SSE 广播失败:{}", e.getMessage());
        }
    }

    /* ============ 业务查询(走缓存) ============ */

    public List<Dish> getDishesByStore(Long storeId) {
        String key = String.format(CACHE_KEY_DISHES, storeId);
        List<Dish> cached = cacheGet(key, List.class);
        if (cached != null) return cached;
        List<Dish> list = dishMapper.selectByStoreId(storeId);
        cachePut(key, list);
        return list;
    }

    /**
     * 全量查询门店所有未删除菜品(含已下架 status=0),供终端启动时本地缓存。
     * 与 getDishesByStore 区别:后者只返回 status=1 的,用于前端展示;
     * 本方法返回全量,终端需自行判断 status。
     */
    public List<Dish> getAllDishesByStore(Long storeId) {
        String key = String.format(CACHE_KEY_DISHES, storeId) + ":all";
        List<Dish> cached = cacheGet(key, List.class);
        if (cached != null) return cached;
        List<Dish> list = dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsDeleted, 0)
                .orderByDesc(Dish::getId));
        cachePut(key, list);
        return list;
    }

    public Dish getDishById(Long id) {
        return dishMapper.selectById(id);
    }

    /* ============ 写操作(失效缓存 + SSE 广播) ============ */

    public Dish createDish(Dish dish) {
        SecurityContext.checkStoreAccess(dish.getStoreId());
        if (dish.getIsDeleted() == null) {
            dish.setIsDeleted(0);
        }
        dishMapper.insert(dish);
        cacheEvictByStore(dish.getStoreId());
        broadcastSse(dish.getStoreId(), dish.getId(), "create");
        return dish;
    }

    public Dish updateDish(Dish dish) {
        Dish existing = dishMapper.selectById(dish.getId());
        if (existing == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        dish.setStoreId(existing.getStoreId());
        dishMapper.updateById(dish);
        cacheEvictByStore(existing.getStoreId());
        broadcastSse(existing.getStoreId(), dish.getId(), "update");
        return dish;
    }

    public void deleteDish(Long id) {
        // 注意:不能手动 setIsDeleted(1)+updateById,因为逻辑删除字段被全局配置托管,
        // updateById 的 SET 子句会跳过 is_deleted 字段,导致删除无效。
        // 必须用 deleteById,MyBatis-Plus 会自动转换为 UPDATE SET is_deleted=1。
        Dish dish = dishMapper.selectById(id);
        if (dish == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(dish.getStoreId());
        dishMapper.deleteById(id);
        cacheEvictByStore(dish.getStoreId());
        broadcastSse(dish.getStoreId(), id, "delete");
    }

    public Dish toggleStatus(Long id) {
        Dish dish = dishMapper.selectById(id);
        if (dish == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(dish.getStoreId());
        dish.setStatus(dish.getStatus() == 1 ? 0 : 1);
        dishMapper.updateById(dish);
        cacheEvictByStore(dish.getStoreId());
        broadcastSse(dish.getStoreId(), id, "toggle");
        return dish;
    }

    public int batchUpdateStatus(List<Long> dishIds, Integer status, Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (dishIds == null || dishIds.isEmpty()) {
            throw new BusinessException("请选择要操作的菜品");
        }
        LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>()
                .in(Dish::getId, dishIds)
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsDeleted, 0)
                .set(Dish::getStatus, status);
        int affected = dishMapper.update(null, wrapper);
        cacheEvictByStore(storeId);
        broadcastSseBatch(storeId);
        return affected;
    }

    public int batchUpdateCategory(List<Long> dishIds, String category, Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (dishIds == null || dishIds.isEmpty()) {
            throw new BusinessException("请选择要操作的菜品");
        }
        LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>()
                .in(Dish::getId, dishIds)
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsDeleted, 0)
                .set(Dish::getCategory, category);
        int affected = dishMapper.update(null, wrapper);
        cacheEvictByStore(storeId);
        broadcastSseBatch(storeId);
        return affected;
    }

    public int batchDelete(List<Long> dishIds, Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        if (dishIds == null || dishIds.isEmpty()) {
            throw new BusinessException("请选择要操作的菜品");
        }
        LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>()
                .in(Dish::getId, dishIds)
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsDeleted, 0)
                .set(Dish::getIsDeleted, 1);
        int affected = dishMapper.update(null, wrapper);
        cacheEvictByStore(storeId);
        broadcastSseBatch(storeId);
        return affected;
    }

    public IPage<Dish> getTrashList(Long storeId, int page, int size) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Dish> wrapper = new LambdaQueryWrapper<Dish>()
                .eq(Dish::getStoreId, storeId)
                .eq(Dish::getIsDeleted, 1)
                .orderByDesc(Dish::getId);
        return dishMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public void restoreDish(Long id) {
        Dish dish = dishMapper.selectById(id);
        if (dish == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(dish.getStoreId());
        // 恢复需把 is_deleted 置 0:不能用 updateById(逻辑删除字段被跳过),
        // 也不能用 wrapper(逻辑删除拦截器会给 WHERE 追加 is_deleted=0,匹配不到已删除记录)。
        // 用自定义 SQL 绕过逻辑删除拦截器。
        dishMapper.restoreById(id);
        cacheEvictByStore(dish.getStoreId());
        broadcastSse(dish.getStoreId(), id, "restore");
    }

    public void purgeDish(Long id) {
        Dish dish = dishMapper.selectById(id);
        if (dish == null) {
            throw new BusinessException("菜品不存在");
        }
        SecurityContext.checkStoreAccess(dish.getStoreId());
        dishMapper.deleteById(id);
        cacheEvictByStore(dish.getStoreId());
        broadcastSse(dish.getStoreId(), id, "purge");
    }
}
