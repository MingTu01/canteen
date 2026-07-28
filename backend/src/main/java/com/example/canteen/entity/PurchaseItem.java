package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("purchase_item")
public class PurchaseItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long purchaseId;
    /** 食材名称 */
    private String materialName;
    /** 单位(斤/公斤/桶) */
    private String unit;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal price;
    /** 小计 */
    private BigDecimal amount;
}
