package com.example.canteen.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 菜单复制 DTO:将源日期所有餐次菜单复制到目标日期
 */
@Data
public class MenuCopyDTO {
    @NotNull(message = "门店ID不能为空")
    private Long storeId;

    @NotNull(message = "源日期不能为空")
    private java.time.LocalDate sourceDate;

    @NotNull(message = "目标日期不能为空")
    private java.time.LocalDate targetDate;

    /** 是否覆盖目标日期已存在的菜单:true=先删除再复制;false=跳过已存在的餐次 */
    private Boolean overwrite = true;
}
