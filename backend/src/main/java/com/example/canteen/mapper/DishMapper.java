package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.Dish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DishMapper extends BaseMapper<Dish> {
    List<Dish> selectByStoreId(@Param("storeId") Long storeId);

    /** B3 原子扣减库存:stock 为 null 表示不限,stock>=qty 才扣减;返回受影响行数 */
    int deductStock(@Param("id") Long id, @Param("qty") Integer qty);

    /**
     * 从回收站恢复菜品:把 is_deleted 置 0。
     * 需用自定义 SQL 绕过逻辑删除拦截器(拦截器会给 update 的 WHERE 追加 is_deleted=0,
     * 无法匹配到已删除记录;updateById 也会跳过 is_deleted 字段)。
     */
    @Update("UPDATE dish SET is_deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") Long id);
}
