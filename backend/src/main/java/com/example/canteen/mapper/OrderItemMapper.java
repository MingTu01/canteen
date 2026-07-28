package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
    List<OrderItem> selectByOrderId(@Param("orderId") Long orderId);

    /** 批量查询多个订单的菜品(避免 N+1,终端订单查询列表用) */
    List<OrderItem> selectByOrderIds(@Param("orderIds") List<Long> orderIds);

    /**
     * 订餐汇总:按门店+日期+餐次(可选)统计各菜品订购数量,供厨师备料使用。
     * 仅统计有效订单(status=1 待完成 或 2 已完成),排除已取消(status=3)。
     *
     * @param storeId  门店 ID
     * @param date     订餐日期(必填)
     * @param mealType 餐次 1早 2中 3晚(为 null 则统计全部餐次)
     * @return 每行:dishId, dishName, price, quantity(订购总数), orderCount(订单数)
     */
    List<Map<String, Object>> selectDishOrderSummary(@Param("storeId") Long storeId,
                                                     @Param("date") LocalDate date,
                                                     @Param("mealType") Integer mealType);

    /**
     * 统计去重订单数(同筛选条件下的实际下单人数/订单数)。
     * 用于订餐汇总中 totalOrders 字段,避免 sum(orderCount) 重复计算多菜品订单。
     */
    Integer countDistinctOrders(@Param("storeId") Long storeId,
                                @Param("date") LocalDate date,
                                @Param("mealType") Integer mealType);
}
