package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体(对应 sys_operation_log 表)
 */
@Data
@TableName("sys_operation_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long adminId;
    private String adminName;
    private Long storeId;
    private String operation;
    private String method;
    private String params;
    private String ip;
    /** 1=成功,0=失败 */
    private Integer status;
    private String errorMsg;
    private LocalDateTime createdAt;
}
