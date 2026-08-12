package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("recharge_record")
public class RechargeRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private Long employeeId;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String operator;
    /** 备注(如"批量充值"/"余额充值"/用户输入的自定义备注) */
    private String remark;
    private LocalDateTime createdAt;
}
