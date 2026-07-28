package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("feedback")
public class Feedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private Long employeeId;
    /** 关联订单(可为空) */
    private Long orderId;
    /** 关联菜品(可为空) */
    private Long dishId;
    /** 评分 1-5 */
    private Integer rating;
    /** 反馈内容 */
    private String content;
    /** 1=菜品评价 2=服务投诉 3=建议 4=其他 */
    private Integer category;
    /** 1=待处理 2=已处理 3=已忽略 */
    private Integer status;
    /** 管理员回复 */
    private String reply;
    /** 回复人 */
    private Long replyAdminId;
    private LocalDateTime repliedAt;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private String employeeName;
    @TableField(exist = false)
    private String dishName;
}
