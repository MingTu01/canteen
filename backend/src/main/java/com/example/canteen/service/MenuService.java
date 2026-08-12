package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.dto.MenuCopyDTO;
import com.example.canteen.dto.MenuWithItemsDTO;
import com.example.canteen.entity.Dish;
import com.example.canteen.entity.Menu;
import com.example.canteen.entity.MenuItem;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishMapper;
import com.example.canteen.mapper.MenuItemMapper;
import com.example.canteen.mapper.MenuMapper;
import com.example.canteen.mapper.OrderMapper;
import com.example.canteen.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单服务,带 Redis 缓存 + SSE 广播。
 *
 * 缓存策略:
 * - getMenuByDate 走 Redis,TTL 10 分钟(菜单变更频率比菜品低)
 * - getMenuDatesByMonth 走 Redis,TTL 1 小时
 * - 写操作(createMenu/copyMenu/deleteMenu)主动失效缓存 + 广播 SSE
 *
 * 容错:Redis 不可用时自动降级为直查数据库。
 */
@Slf4j
@Service
public class MenuService {

    private static final String CACHE_KEY_MENU_BY_DATE = "menu:store:%s:date:%s";
    private static final String CACHE_KEY_MENU_DATES = "menu:store:%s:year:%d:month:%d";
    private static final Duration MENU_TTL = Duration.ofMinutes(10);
    private static final Duration MENU_DATES_TTL = Duration.ofHours(1);

    private final MenuMapper menuMapper;
    private final MenuItemMapper menuItemMapper;
    private final DishMapper dishMapper;
    private final OrderMapper orderMapper;
    private final ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider;
    private final ObjectProvider<SseService> sseServiceProvider;

    public MenuService(MenuMapper menuMapper, MenuItemMapper menuItemMapper, DishMapper dishMapper,
                       OrderMapper orderMapper,
                       ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider,
                       ObjectProvider<SseService> sseServiceProvider) {
        this.menuMapper = menuMapper;
        this.menuItemMapper = menuItemMapper;
        this.dishMapper = dishMapper;
        this.orderMapper = orderMapper;
        this.redisTemplateProvider = redisTemplateProvider;
        this.sseServiceProvider = sseServiceProvider;
    }

    /* ============ 缓存工具(降级安全) ============ */

    private RedisTemplate<String, Object> redis() {
        try {
            return redisTemplateProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T cacheGet(String key) {
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

    private void cachePut(String key, Object value, Duration ttl) {
        RedisTemplate<String, Object> tpl = redis();
        if (tpl == null || value == null) return;
        try {
            tpl.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis 写入失败:{}", e.getMessage());
        }
    }

    /** 失效指定门店某日菜单缓存(以及该日所在月的日期列表缓存) */
    private void cacheEvictMenu(Long storeId, LocalDate date) {
        RedisTemplate<String, Object> tpl = redis();
        if (tpl == null) return;
        try {
            tpl.delete(String.format(CACHE_KEY_MENU_BY_DATE, storeId, date));
            tpl.delete(String.format(CACHE_KEY_MENU_DATES, storeId, date.getYear(), date.getMonthValue()));
        } catch (Exception e) {
            log.warn("Redis 失效失败:{}", e.getMessage());
        }
    }

    private void broadcastMenuChanged(Long storeId, LocalDate date, Integer mealType) {
        try {
            SseService sse = sseServiceProvider.getIfAvailable();
            if (sse != null) {
                sse.broadcastMenuChanged(storeId, date == null ? null : date.toString(), mealType);
            }
        } catch (Exception e) {
            log.debug("SSE 广播失败:{}", e.getMessage());
        }
    }

    /* ============ 业务查询(走缓存) ============ */

    /**
     * 查询某日菜单(管理端,返回全部含未发布)。
     * 点菜端请用 getPublishedMenuByDate。
     */
    public List<MenuWithItemsDTO> getMenuByDate(Long storeId, LocalDate date) {
        String key = String.format(CACHE_KEY_MENU_BY_DATE, storeId, date);
        List<MenuWithItemsDTO> cached = cacheGet(key);
        if (cached != null) return cached;

        List<MenuWithItemsDTO> result = loadMenuByDateFromDb(storeId, date, false);
        cachePut(key, result, MENU_TTL);
        return result;
    }

    /**
     * 查询某日已发布菜单(点菜端使用)。
     */
    public List<MenuWithItemsDTO> getPublishedMenuByDate(Long storeId, LocalDate date) {
        String key = String.format(CACHE_KEY_MENU_BY_DATE, storeId, date) + ":published";
        List<MenuWithItemsDTO> cached = cacheGet(key);
        if (cached != null) return cached;

        List<MenuWithItemsDTO> result = loadMenuByDateFromDb(storeId, date, true);
        cachePut(key, result, MENU_TTL);
        return result;
    }

    private List<MenuWithItemsDTO> loadMenuByDateFromDb(Long storeId, LocalDate date, boolean publishedOnly) {
        List<Menu> menus = publishedOnly
                ? menuMapper.selectPublishedByStoreDate(storeId, date)
                : menuMapper.selectByStoreDate(storeId, date);
        if (menus == null || menus.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> menuIds = menus.stream().map(Menu::getId).collect(Collectors.toList());
        List<MenuItem> allItems = menuItemMapper.selectByMenuIds(menuIds);
        if (allItems == null) {
            allItems = Collections.emptyList();
        }

        List<Long> dishIds = allItems.stream().map(MenuItem::getDishId).distinct().collect(Collectors.toList());
        Map<Long, Dish> dishMapTemp = new HashMap<>();
        if (!dishIds.isEmpty()) {
            List<Dish> dishes = dishMapper.selectBatchIds(dishIds);
            if (dishes != null) {
                dishMapTemp = dishes.stream().collect(Collectors.toMap(Dish::getId, d -> d));
            }
        }
        final Map<Long, Dish> dishMap = dishMapTemp;

        Map<Long, List<MenuItem>> itemsByMenu = allItems.stream()
                .collect(Collectors.groupingBy(MenuItem::getMenuId));

        List<MenuWithItemsDTO> result = new ArrayList<>();
        for (Menu menu : menus) {
            MenuWithItemsDTO dto = new MenuWithItemsDTO();
            dto.setMenu(menu);
            List<MenuItem> items = itemsByMenu.getOrDefault(menu.getId(), new ArrayList<>());
            List<MenuWithItemsDTO.ItemView> itemViews = items.stream()
                    .map(it -> new MenuWithItemsDTO.ItemView(it, dishMap.get(it.getDishId())))
                    .collect(Collectors.toList());
            dto.setItems(itemViews);
            result.add(dto);
        }
        return result;
    }

    public List<Map<String, Object>> getMenuDatesByMonth(Long storeId, int year, int month) {
        String key = String.format(CACHE_KEY_MENU_DATES, storeId, year, month);
        List<Map<String, Object>> cached = cacheGet(key);
        if (cached != null) return cached;

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<Map<String, Object>> raw = menuMapper.selectDateStatusByRange(storeId, start, end);
        List<Map<String, Object>> result;
        if (raw == null || raw.isEmpty()) {
            result = Collections.emptyList();
        } else {
            result = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                Map<String, Object> m = new HashMap<>();
                Object dateVal = row.get("date");
                String dateStr = dateVal == null ? null : dateVal.toString();
                m.put("date", dateStr);
                Object pubVal = row.get("published");
                boolean published = pubVal != null && (
                        (pubVal instanceof Number n && n.intValue() == 1) ||
                        "1".equals(pubVal.toString()));
                m.put("published", published);
                result.add(m);
            }
        }
        cachePut(key, result, MENU_DATES_TTL);
        return result;
    }

    /* ============ 写操作(失效缓存 + SSE 广播) ============ */

    @Transactional
    public Menu createMenu(Menu menu, List<Long> dishIds) {
        Menu existing = menuMapper.selectByStoreDateType(menu.getStoreId(), menu.getDate(), menu.getMealType());
        // 保留原菜单的发布状态:编辑已发布菜单后仍保持已发布,避免 H5 端看不到菜品
        Integer inheritedPublished = null;
        if (existing != null) {
            inheritedPublished = existing.getPublished();
            deleteMenuInternal(existing.getId());
        }
        if (dishIds != null && !dishIds.isEmpty()) {
            List<Dish> dishes = dishMapper.selectBatchIds(dishIds);
            if (dishes.size() != dishIds.size()) {
                throw new BusinessException("部分菜品不存在");
            }
            for (Dish dish : dishes) {
                if (dish.getStoreId() == null || !dish.getStoreId().equals(menu.getStoreId())) {
                    throw new BusinessException("菜品不属于本门店");
                }
            }
        }
        // 继承原发布状态;新建菜单默认未发布(草稿),需手动发布后点菜端才可见
        if (menu.getPublished() == null) {
            menu.setPublished(inheritedPublished != null ? inheritedPublished : 0);
        }
        menuMapper.insert(menu);

        int sortOrder = 0;
        for (Long dishId : dishIds) {
            MenuItem item = new MenuItem();
            item.setMenuId(menu.getId());
            item.setDishId(dishId);
            item.setSortOrder(sortOrder++);
            menuItemMapper.insert(item);
        }

        cacheEvictMenu(menu.getStoreId(), menu.getDate());
        broadcastMenuChanged(menu.getStoreId(), menu.getDate(), menu.getMealType());
        return menu;
    }

    /**
     * 发布某日所有菜单:将 published 设为 1,点菜端即可看到。
     */
    @Transactional
    public int publishMenu(Long storeId, LocalDate date) {
        SecurityContext.checkStoreAccess(storeId);
        List<Menu> menus = menuMapper.selectByStoreDate(storeId, date);
        if (menus == null || menus.isEmpty()) {
            throw new BusinessException("当天无菜单可发布");
        }
        int count = 0;
        for (Menu menu : menus) {
            if (menu.getPublished() == null || menu.getPublished() == 0) {
                menu.setPublished(1);
                menuMapper.updateById(menu);
                count++;
            }
        }
        cacheEvictMenu(storeId, date);
        broadcastMenuChanged(storeId, date, null);
        return count;
    }

    /**
     * 批量发布日期范围内所有菜单。
     * @return {published: 发布餐次数, skipped: 无菜单跳过天数, days: 实际发布天数}
     */
    @Transactional
    public Map<String, Object> batchPublishMenu(Long storeId, LocalDate startDate, LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);
        if (startDate.isAfter(endDate)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        int publishedCount = 0;
        int daysPublished = 0;
        int daysSkipped = 0;
        LocalDate cur = startDate;
        while (!cur.isAfter(endDate)) {
            List<Menu> menus = menuMapper.selectByStoreDate(storeId, cur);
            if (menus == null || menus.isEmpty()) {
                daysSkipped++;
            } else {
                int dayCount = 0;
                for (Menu menu : menus) {
                    if (menu.getPublished() == null || menu.getPublished() == 0) {
                        menu.setPublished(1);
                        menuMapper.updateById(menu);
                        publishedCount++;
                        dayCount++;
                    }
                }
                if (dayCount > 0) {
                    daysPublished++;
                }
                cacheEvictMenu(storeId, cur);
                broadcastMenuChanged(storeId, cur, null);
            }
            cur = cur.plusDays(1);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("published", publishedCount);
        result.put("daysPublished", daysPublished);
        result.put("daysSkipped", daysSkipped);
        return result;
    }

    /**
     * 发布该门店所有未发布的菜单(无需指定日期范围)。
     */
    @Transactional
    public Map<String, Object> publishAllUnpublished(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        // 查询该门店所有未发布的菜单(published=0 或 published IS NULL)
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Menu> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Menu>()
                        .eq(Menu::getStoreId, storeId)
                        .and(w -> w.eq(Menu::getPublished, 0).or().isNull(Menu::getPublished));
        List<Menu> unpublished = menuMapper.selectList(wrapper);
        int publishedCount = 0;
        java.util.Set<LocalDate> publishedDates = new java.util.HashSet<>();
        for (Menu menu : unpublished) {
            menu.setPublished(1);
            menuMapper.updateById(menu);
            publishedCount++;
            if (menu.getDate() != null) {
                publishedDates.add(menu.getDate());
            }
        }
        // 清缓存 + 广播
        for (LocalDate date : publishedDates) {
            cacheEvictMenu(storeId, date);
            broadcastMenuChanged(storeId, date, null);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("published", publishedCount);
        result.put("daysPublished", publishedDates.size());
        return result;
    }

    @Transactional
    public int copyMenu(MenuCopyDTO dto) {
        Long storeId = dto.getStoreId();
        LocalDate sourceDate = dto.getSourceDate();
        LocalDate targetDate = dto.getTargetDate();
        if (sourceDate.equals(targetDate)) {
            throw new BusinessException("源日期与目标日期不能相同");
        }

        List<Menu> sourceMenus = menuMapper.selectByStoreDate(storeId, sourceDate);
        if (sourceMenus == null || sourceMenus.isEmpty()) {
            throw new BusinessException("源日期无菜单可复制");
        }

        boolean overwrite = Boolean.TRUE.equals(dto.getOverwrite());
        List<Menu> targetExisting = menuMapper.selectByStoreDate(storeId, targetDate);
        Map<Integer, Menu> targetByMealType = targetExisting == null
                ? Collections.emptyMap()
                : targetExisting.stream().collect(Collectors.toMap(Menu::getMealType, m -> m));

        List<Long> sourceMenuIds = sourceMenus.stream().map(Menu::getId).collect(Collectors.toList());
        List<MenuItem> sourceItems = menuItemMapper.selectByMenuIds(sourceMenuIds);
        if (sourceItems == null) {
            sourceItems = Collections.emptyList();
        }
        Map<Long, List<MenuItem>> itemsByMenu = sourceItems.stream()
                .collect(Collectors.groupingBy(MenuItem::getMenuId));

        int copied = 0;
        for (Menu src : sourceMenus) {
            Integer mealType = src.getMealType();
            Menu existing = targetByMealType.get(mealType);
            if (existing != null) {
                if (!overwrite) {
                    continue;
                }
                deleteMenuInternal(existing.getId());
            }

            Menu newMenu = new Menu();
            newMenu.setStoreId(storeId);
            newMenu.setDate(targetDate);
            newMenu.setMealType(mealType);
            menuMapper.insert(newMenu);

            List<MenuItem> items = itemsByMenu.getOrDefault(src.getId(), Collections.emptyList());
            int sortOrder = 0;
            for (MenuItem it : items) {
                MenuItem ni = new MenuItem();
                ni.setMenuId(newMenu.getId());
                ni.setDishId(it.getDishId());
                ni.setSortOrder(sortOrder++);
                menuItemMapper.insert(ni);
            }
            copied++;
        }

        // 复制可能影响目标日期菜单
        cacheEvictMenu(storeId, targetDate);
        broadcastMenuChanged(storeId, targetDate, null);
        return copied;
    }

    @Transactional
    public void deleteMenu(Long id) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException("菜单不存在");
        }
        SecurityContext.checkStoreAccess(menu.getStoreId());
        deleteMenuInternal(id);
        cacheEvictMenu(menu.getStoreId(), menu.getDate());
        broadcastMenuChanged(menu.getStoreId(), menu.getDate(), menu.getMealType());
    }

    private void deleteMenuInternal(Long id) {
        menuItemMapper.delete(new LambdaQueryWrapper<MenuItem>()
                .eq(MenuItem::getMenuId, id));
        menuMapper.deleteById(id);
    }

    /* ============ 菜单修改前的订单检查(方案A:提示但不阻止) ============ */

    /**
     * 检查某日某餐次是否已有订单(用于修改/清空前的提示)。
     * @return 订单数(0 表示无订单)
     */
    public int countOrdersForMeal(Long storeId, LocalDate date, Integer mealType) {
        if (mealType == null) return 0;
        Integer cnt = orderMapper.countByStoreDateMeal(storeId, date, mealType);
        return cnt == null ? 0 : cnt;
    }

    /**
     * 检查某日所有餐次的订单情况(用于清空/修改前的批量提示)。
     * @return Map:mealType -> 订单数(仅包含订单数 > 0 的餐次)
     */
    public Map<Integer, Integer> countOrdersByDate(Long storeId, LocalDate date) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int mt = 1; mt <= 3; mt++) {
            int cnt = countOrdersForMeal(storeId, date, mt);
            if (cnt > 0) {
                result.put(mt, cnt);
            }
        }
        return result;
    }

    /**
     * 清空某日所有餐次的菜单(草稿和已发布一并删除)。
     * 不删订单(订单保存菜品快照,不受菜单影响),仅清菜单本身。
     * @return 删除的菜单数量
     */
    @Transactional
    public int clearMenusByDate(Long storeId, LocalDate date) {
        SecurityContext.checkStoreAccess(storeId);
        List<Menu> menus = menuMapper.selectByStoreDate(storeId, date);
        if (menus == null || menus.isEmpty()) {
            return 0;
        }
        for (Menu menu : menus) {
            deleteMenuInternal(menu.getId());
        }
        cacheEvictMenu(storeId, date);
        broadcastMenuChanged(storeId, date, null);
        return menus.size();
    }
}
