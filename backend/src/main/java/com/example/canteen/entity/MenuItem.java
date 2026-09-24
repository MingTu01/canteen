package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("menu_item")
public class MenuItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long menuId;
    private Long dishId;
    /** 菜品价格快照:菜单保存(创建/复制)时从 dish 固化,菜品改价不影响已存菜单与订单 */
    private BigDecimal price;
    /** 辣度快照:0=不辣,1=微辣,2=中辣,3=重辣(保存时固化) */
    private Integer spiceLevel;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
