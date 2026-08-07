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

    /**
     * 核销/取餐码查重:按门店+日期+取餐码查询订单。
     * storeId 为 null 时不按门店过滤(超管场景);date 限定当天,防止跨日错核销。
     */
    Order selectByStoreDatePickupCode(@Param("storeId") Long storeId,
                                      @Param("date") LocalDate date,
                                      @Param("pickupCode") String pickupCode);
}
