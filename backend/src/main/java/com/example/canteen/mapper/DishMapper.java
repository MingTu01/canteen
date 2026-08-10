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

    /**
     * 回收站查询:查 is_deleted=1 的菜品。
     * 需用自定义 SQL 绕过逻辑删除拦截器(selectPage 会自动追加 is_deleted=0)。
     */
    List<Dish> selectTrashByStoreId(@Param("storeId") Long storeId);

    /** 回收站计数 */
    long countTrashByStoreId(@Param("storeId") Long storeId);

    /**
     * 彻底删除:物理删除已放入回收站的菜品。
     * deleteById 在逻辑删除模式下会变成 UPDATE SET is_deleted=1,对已删除记录无效。
     */
    int purgeById(@Param("id") Long id);

    /**
     * 按 ID 查询已删除的菜品(绕过逻辑删除拦截器),供恢复/彻底删除前校验用。
     */
    Dish selectDeletedById(@Param("id") Long id);
}
