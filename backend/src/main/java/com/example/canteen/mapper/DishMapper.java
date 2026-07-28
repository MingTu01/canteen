package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.Dish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DishMapper extends BaseMapper<Dish> {
    List<Dish> selectByStoreId(@Param("storeId") Long storeId);

    /** B3 原子扣减库存:stock 为 null 表示不限,stock>=qty 才扣减;返回受影响行数 */
    int deductStock(@Param("id") Long id, @Param("qty") Integer qty);
}
