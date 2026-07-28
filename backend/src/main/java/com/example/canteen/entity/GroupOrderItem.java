package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("group_order_item")
public class GroupOrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupOrderId;
    private Long dishId;
    private String dishName;
    private BigDecimal price;
    /** 份数 */
    private Integer quantity;
    /** 小计 */
    private BigDecimal amount;
}
