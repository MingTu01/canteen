package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("order_item")
public class OrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long dishId;
    private String dishName;
    private BigDecimal price;
    private Integer quantity;
    private LocalDateTime createdAt;

    /**
     * 菜品图片相对路径(关联 dish 表查询填充,非 order_item 表字段)。
     * 前端订单详情菜品明细展示用;为空时前端回退到占位图标。
     */
    @TableField(exist = false)
    private String dishImage;

    /**
     * 辣度(关联 dish 表查询填充,非 order_item 表字段):0=不辣,1=微辣,2=中辣,3=重辣。
     * 前端订单列表/详情展示辣椒图标用。
     */
    @TableField(exist = false)
    private Integer spiceLevel;
}
