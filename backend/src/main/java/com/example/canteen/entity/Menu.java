package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("menu")
public class Menu {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private LocalDate date;
    private Integer mealType;
    /** 发布状态:0=未发布(草稿),1=已发布(点菜端可见) */
    private Integer published;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
