package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.MenuItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MenuItemMapper extends BaseMapper<MenuItem> {
    List<MenuItem> selectByMenuId(@Param("menuId") Long menuId);

    /** B10 批量查询多个 menu 的 items,避免 N+1 */
    List<MenuItem> selectByMenuIds(@Param("menuIds") List<Long> menuIds);

    /** 菜单价格快照:按 (门店,日期,餐次,菜品) 查 menu_item 固化价;无快照返回 null(下单回退 dish 实时价) */
    BigDecimal selectSnapshotPriceByStoreDateMealDish(@Param("storeId") Long storeId, @Param("date") LocalDate date,
                                                      @Param("mealType") Integer mealType, @Param("dishId") Long dishId);
}
