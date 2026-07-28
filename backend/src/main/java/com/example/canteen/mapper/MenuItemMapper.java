package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.MenuItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MenuItemMapper extends BaseMapper<MenuItem> {
    List<MenuItem> selectByMenuId(@Param("menuId") Long menuId);

    /** B10 批量查询多个 menu 的 items,避免 N+1 */
    List<MenuItem> selectByMenuIds(@Param("menuIds") List<Long> menuIds);
}
