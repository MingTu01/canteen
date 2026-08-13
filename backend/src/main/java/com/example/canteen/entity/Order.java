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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 非数据库字段:员工姓名(列表展示用,由 service 层填充) */
    @TableField(exist = false)
    private String employeeName;

    /** 非数据库字段:员工卡号(列表展示用,由 controller 层填充) */
    @TableField(exist = false)
    private String cardNo;

    /** 非数据库字段:订单菜品列表(终端订单查询页展示用,由 service 层批量填充) */
    @TableField(exist = false)
    private List<OrderItem> items;
}
