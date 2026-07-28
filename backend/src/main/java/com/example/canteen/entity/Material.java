package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("material")
public class Material {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    /** 食材名称 */
    private String name;
    /** 单位 */
    private String unit;
    /** 当前库存 */
    private BigDecimal stockQty;
    /** 最低库存预警线 */
    private BigDecimal minStock;
    /** 分类 */
    private String category;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
