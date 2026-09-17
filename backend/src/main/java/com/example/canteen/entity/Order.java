package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("`order`")
public class Order {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long storeId;
    private Long employeeId;
    private LocalDate date;
    private Integer mealType;
    private BigDecimal totalAmount;
    private Integer status;
    /** 订单来源: 0-正常订餐, 1-未订餐用餐 */
    private Integer orderSource;
    /** 未订餐用餐手续费(包含在 totalAmount 中,正常订餐为 0) */
    private BigDecimal serviceFee;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 员工姓名快照(下单时固化,删/禁/换卡不影响历史订单展示) */
    @TableField("employee_name")
    private String employeeName;

    /** 员工卡号快照(下单时固化,删/禁/换卡不影响历史订单展示) */
    @TableField("employee_card_no")
    private String cardNo;

    /** 非数据库字段:员工部门名称(列表展示用,由 service 层填充) */
    @TableField(exist = false)
    private String departmentName;

    /** 非数据库字段:订单菜品列表(终端订单查询页展示用,由 service 层批量填充) */
    @TableField(exist = false)
    private List<OrderItem> items;
}
