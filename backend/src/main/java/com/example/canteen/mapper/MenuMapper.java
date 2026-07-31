package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.Menu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface MenuMapper extends BaseMapper<Menu> {
    Menu selectByStoreDateType(@Param("storeId") Long storeId, @Param("date") LocalDate date, @Param("mealType") Integer mealType);
    List<Menu> selectByStoreDate(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /** 仅查询已发布菜单(点菜端使用) */
    List<Menu> selectPublishedByStoreDate(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /** 查询门店在指定日期范围内的所有菜单(用于月历标记) */
    List<Menu> selectByStoreDateRange(@Param("storeId") Long storeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /** 按日期分组统计发布状态(管理端月历使用):每条含 date/published/total */
    List<Map<String, Object>> selectDateStatusByRange(@Param("storeId") Long storeId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
