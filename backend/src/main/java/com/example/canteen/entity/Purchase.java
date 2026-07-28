package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("purchase")
public class Purchase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    /** 采购单号 */
    private String purchaseNo;
    /** 供应商 */
    private Long supplierId;
    /** 采购总金额 */
    private BigDecimal totalAmount;
    /** 采购日期 */
    private LocalDate purchaseDate;
    /** 1=待入库 2=已入库 3=已取消 */
    private Integer status;
    private String remark;
    /** 操作人(adminId) */
    private Long operatorId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(exist = false)
    private String supplierName;
    @TableField(exist = false)
    private String operatorName;
}
