package com.example.canteen.dto;

import com.example.canteen.entity.Purchase;
import com.example.canteen.entity.PurchaseItem;
import lombok.Data;

import java.util.List;

/**
 * 采购单详情聚合视图:主表信息(含供应商/操作人名称)+ 明细列表
 */
@Data
public class PurchaseDetailDTO {
    private Purchase purchase;
    private List<PurchaseItem> items;
}
