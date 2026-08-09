package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日终对账/关店记录:对账三阶段(待对账→已对账→已关店)的对账单快照。
 */
@Data
@TableName("daily_settlement")
public class DailySettlement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    /** 对账日期 */
    private LocalDate settleDate;
    /** 营业总额(已完成订单) */
    private BigDecimal totalRevenue;
    /** 退款总额(已取消订单) */
    private BigDecimal totalRefund;
    /** 当日充值总额 */
    private BigDecimal totalRecharge;
    /** 当日消费总额 */
    private BigDecimal totalConsumption;
    /** 现金充值 */
    private BigDecimal cashRevenue;
    /** 线上充值 */
    private BigDecimal onlineRevenue;
    /** 订单数 */
    private Integer orderCount;
    /** 已完成订单数 */
    private Integer completedCount;
    /** 已取消订单数 */
    private Integer cancelledCount;
    /** 已取餐订单数 */
    private Integer servedCount;
    /** 未就餐订单数(超时未核销,已付款未退款) */
    private Integer missedCount;
    /** 操作人 */
    private Long operatorId;
    /** 1=待对账 2=已对账 3=已关店 */
    private Integer status;
    private String remark;
    /** 对账时间 */
    private LocalDateTime settledAt;
    /** 关店时间 */
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 非数据库字段:操作人姓名(由 service 层填充) */
    @TableField(exist = false)
    private String operatorName;
}
