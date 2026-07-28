package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    private String title;
    private String content;
    /** 配图 URL(dataURL 或外链) */
    private String imageUrl;
    private Integer type;
    /** 1=启用,0=下架(可由到期自动下架触发) */
    private Integer status;
    /** 上架时间:为空表示立即上架 */
    private LocalDateTime publishAt;
    /** 下架时间:为空表示不下架;到期后由调度器自动 status=0 */
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 计算展示状态:pending=待发布,active=已发布,expired=已下架(到期),offline=手动下架 */
    @TableField(exist = false)
    private String displayStatus;
}
