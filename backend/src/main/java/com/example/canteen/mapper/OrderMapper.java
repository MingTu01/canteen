package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    List<Order> selectByStoreId(@Param("storeId") Long storeId);
    List<Order> selectByEmployeeId(@Param("employeeId") Long employeeId);
    List<Order> selectByStoreDate(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /** B6 防重复:查询员工某天某餐次是否已有订单 */
    Order selectByEmployeeDateMeal(@Param("employeeId") Long employeeId,
                                   @Param("date") LocalDate date,
                                   @Param("mealType") Integer mealType);

    /** 核销:按取餐码查询订单 */
    Order selectByPickupCode(@Param("pickupCode") String pickupCode);
}
