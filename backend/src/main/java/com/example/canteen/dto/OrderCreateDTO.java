package com.example.canteen.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class OrderCreateDTO {
    @NotNull(message = "员工ID不能为空")
    private Long employeeId;

    @NotNull(message = "门店ID不能为空")
    private Long storeId;

    private LocalDate date;

    @NotNull(message = "餐次不能为空")
    private Integer mealType;

    @NotEmpty(message = "订单菜品不能为空")
    private List<OrderItemDTO> items;

    /** 订单来源: 0-正常订餐(默认), 1-未订餐用餐 */
    private Integer orderSource = 0;
}
