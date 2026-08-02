package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("employee")
public class Employee {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private String cardNo;
    /** 员工消费密码(BCrypt),用于刷卡+密码二次校验,默认与卡号相同 */
    private String password;
    /** 手机号(员工跨设备稳定标识,可用于 H5/小程序登录) */
    private String phone;
    /** 微信 openid(小程序绑定后一键登录,本期 H5 不使用) */
    private String wxOpenid;
    private String name;
    private String avatar;
    private Long departmentId;
    private BigDecimal balance;
    private Integer status;
    private Integer isDeleted;
    /** 密码最后更新时间:用于 JWT 失效校验(iat < passwordUpdatedAt 则旧 token 失效) */
    private LocalDateTime passwordUpdatedAt;
    /** 是否需要强制修改密码(首次登录使用默认密码时为 true,改密后置 false) */
    private Integer mustChangePassword;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 非持久化:批量导入时使用部门名称,服务层解析为 departmentId */
    @TableField(exist = false)
    private String departmentName;

    /** 非持久化:关联查询时回填的部门名称 */
    @TableField(exist = false)
    private String departmentNameDisplay;
}
