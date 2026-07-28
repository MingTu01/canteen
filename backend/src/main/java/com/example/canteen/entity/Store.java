package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("store")
public class Store {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private String address;
    private String phone;
    /** 食堂安全码(用于终端绑定,超管可重置) */
    private String securityCode;
    private Integer status;
    /** 企业 Logo URL */
    private String logoUrl;
    /** 食堂展示图片 URL */
    private String imageUrl;
    /** 取餐终端主图/背景图 URL */
    private String terminalBackgroundUrl;
    /** H5 顶部 banner URL(可选) */
    private String h5BannerUrl;
    /** 食堂简介 */
    private String description;
    /** 早餐开始时间,格式 "07:00" */
    private String breakfastStart;
    /** 早餐结束时间,格式 "09:00" */
    private String breakfastEnd;
    /** 午餐开始时间,格式 "11:00" */
    private String lunchStart;
    /** 午餐结束时间,格式 "13:00" */
    private String lunchEnd;
    /** 晚餐开始时间,格式 "17:00" */
    private String dinnerStart;
    /** 晚餐结束时间,格式 "19:00" */
    private String dinnerEnd;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
