package com.example.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.canteen.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
     * 统计门店某日某餐次的已下单(未取消)订单数。
     * 用于菜单修改/删除前的提示。
     */
    int countByStoreDateMeal(@Param("storeId") Long storeId,
                             @Param("date") LocalDate date,
                             @Param("mealType") Integer mealType);

    /**
     * 按状态分组统计订单数量(基于与列表一致的筛选条件,忽略分页)。
     * 用于订单列表底部展示各状态条数(如 待用餐X条/已用餐X条)。
     */
    List<Map<String, Object>> countByStatus(@Param("storeId") Long storeId,
                                            @Param("status") Integer status,
                                            @Param("mealType") Integer mealType,
                                            @Param("orderSource") Integer orderSource,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate,
                                            @Param("keyword") String keyword);
}
