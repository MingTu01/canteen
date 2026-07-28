package com.example.canteen.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 充值请求 DTO
 */
@Data
public class RechargeDTO {
    @NotNull(message = "员工ID不能为空")
    private Long employeeId;

    @NotNull(message = "充值金额不能为空")
    @Positive(message = "充值金额必须大于0")
    private BigDecimal amount;

    private String remark;

    /** 仅超管操作时需要传门店ID;门店管理员自动取当前门店 */
    private Long storeId;
}
