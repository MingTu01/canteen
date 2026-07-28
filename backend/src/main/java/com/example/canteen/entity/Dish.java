package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("dish")
public class Dish {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private String name;
    private BigDecimal price;
    private String image;
    private String category;
    /** 适用餐次(逗号分隔:1=早餐,2=午餐,3=晚餐),如 "1,2,3" */
    private String mealTypes;
    private Integer isNew;
    private Integer status;
    /** 库存,null 表示不限 */
    private Integer stock;
    /** 单次限购数量,默认 99 */
    private Integer maxPerOrder;
    /** 软删除:0=正常,1=已删除 */
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
