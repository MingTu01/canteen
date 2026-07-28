package com.example.canteen.dto;

import com.example.canteen.entity.GroupOrder;
import com.example.canteen.entity.GroupOrderItem;
import lombok.Data;

import java.util.List;

/**
 * 团体订餐创建 DTO:主表 + 明细列表
 */
@Data
public class GroupOrderCreateDTO {
    private GroupOrder groupOrder;
    private List<GroupOrderItem> items;
}
