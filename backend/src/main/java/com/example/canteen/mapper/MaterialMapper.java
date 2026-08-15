package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.Material;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface MaterialMapper extends BaseMapper<Material> {

    /** 原子累加库存:数据库端直接加,避免 select-then-update 在并发入库时丢失更新 */
    @Update("UPDATE material SET stock_qty = COALESCE(stock_qty, 0) + #{delta} WHERE id = #{id}")
    int addStockQty(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
