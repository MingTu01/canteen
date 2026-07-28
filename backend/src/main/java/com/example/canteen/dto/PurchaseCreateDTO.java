package com.example.canteen.dto;

import com.example.canteen.entity.Purchase;
import com.example.canteen.entity.PurchaseItem;
import lombok.Data;

import java.util.List;

/**
 * 采购单创建/更新聚合 DTO:主表 + 明细列表
 */
@Data
public class PurchaseCreateDTO {
    private Purchase purchase;
    private List<PurchaseItem> items;
}
