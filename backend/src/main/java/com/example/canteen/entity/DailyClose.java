package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日终对账记录:每次确认关店时落库的对账单快照。
 */
@Data
@TableName("daily_close")
public class DailyClose {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private LocalDate closeDate;
    private Integer orderCount;
    private BigDecimal totalRevenue;
    private BigDecimal totalRefund;
    private BigDecimal rechargeAmount;
    /** 1=已对账 */
    private Integer status;
    private Long operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
