package com.example.canteen.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 菜单创建 DTO
 */
@Data
public class MenuCreateDTO {
    @NotNull(message = "门店ID不能为空")
    private Long storeId;

    @NotNull(message = "日期不能为空")
    private LocalDate date;

    @NotNull(message = "餐次不能为空")
    private Integer mealType;

    @NotEmpty(message = "菜品列表不能为空")
    private List<Long> dishIds;
}
