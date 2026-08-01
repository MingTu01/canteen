package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存盘点记录
 * status: 1=待处理(有差异) 2=已处理(已恢复)
 */
@Data
@TableName("stock_count")
public class StockCount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private Long materialId;
    private String materialName;
    /** 盘点时系统库存 */
    private BigDecimal systemQty;
    /** 实际盘点数量 */
    private BigDecimal countedQty;
    /** 差异 = countedQty - systemQty */
    private BigDecimal difference;
    /** 1=待处理 2=已处理 */
    private Integer status;
    private Long operatorId;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
