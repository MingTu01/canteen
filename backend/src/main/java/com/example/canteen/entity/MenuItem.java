package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("menu_item")
public class MenuItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long menuId;
    private Long dishId;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
