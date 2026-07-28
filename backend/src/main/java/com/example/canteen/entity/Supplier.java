package com.example.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("supplier")
public class Supplier {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storeId;
    /** 供应商名称 */
    private String name;
    /** 联系人 */
    private String contactPerson;
    /** 联系电话 */
    private String phone;
    /** 地址 */
    private String address;
    /** 供货品类(米面/蔬菜/肉类/调料等) */
    private String category;
    /** 1=合作中 0=已停用 */
    private Integer status;
    /** 备注 */
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
