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
@TableName("group_order")
public class GroupOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    /** 团体订单号 */
    private String orderNo;
    /** 订单标题(如"3楼会议室会议餐") */
    private String title;
    /** 组织人(员工ID) */
    private Long organizerId;
    /** 用餐人数 */
    private Integer headcount;
    /** 用餐日期 */
    private LocalDate mealDate;
    /** 1早 2中 3晚 */
    private Integer mealType;
    /** 用餐地点 */
    private String location;
    /** 总金额 */
    private BigDecimal totalAmount;
    /** 1=待确认 2=已确认 3=已取消 4=已完成 */
    private Integer status;
    /** 备注(特殊要求) */
    private String remark;
    /** 操作人(adminId) */
    private Long operatorId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private String organizerName;
    @TableField(exist = false)
    private String operatorName;
}
