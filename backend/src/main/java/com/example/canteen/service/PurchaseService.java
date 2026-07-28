package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.dto.PurchaseCreateDTO;
import com.example.canteen.dto.PurchaseDetailDTO;
import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Purchase;
import com.example.canteen.entity.PurchaseItem;
import com.example.canteen.entity.Supplier;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.PurchaseItemMapper;
import com.example.canteen.mapper.PurchaseMapper;
import com.example.canteen.mapper.SupplierMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PurchaseService {
    /** 采购单状态:1=待入库 2=已入库 3=已取消 */
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_INBOUND = 2;
    public static final int STATUS_CANCELLED = 3;

    private final PurchaseMapper purchaseMapper;
    private final PurchaseItemMapper purchaseItemMapper;
    private final SupplierMapper supplierMapper;
    private final AdminMapper adminMapper;

    public PurchaseService(PurchaseMapper purchaseMapper,
                           PurchaseItemMapper purchaseItemMapper,
                           SupplierMapper supplierMapper,
                           AdminMapper adminMapper) {
        this.purchaseMapper = purchaseMapper;
        this.purchaseItemMapper = purchaseItemMapper;
        this.supplierMapper = supplierMapper;
        this.adminMapper = adminMapper;
    }

    /**
     * 创建采购单:生成单号、计算总价、保存主表 + 明细
     */
    @Transactional
    public Purchase createPurchase(PurchaseCreateDTO dto) {
        Purchase purchase = dto.getPurchase();
        if (purchase == null) {
            throw new BusinessException("采购单信息不能为空");
        }
        SecurityContext.checkStoreAccess(purchase.getStoreId());
        if (purchase.getSupplierId() == null) {
            throw new BusinessException("请选择供应商");
        }
        Supplier supplier = supplierMapper.selectById(purchase.getSupplierId());
        if (supplier == null || !supplier.getStoreId().equals(purchase.getStoreId())) {
            throw new BusinessException("供应商不存在或不属于当前门店");
        }

        List<PurchaseItem> items = dto.getItems() == null ? new ArrayList<>() : dto.getItems();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseItem item : items) {
            if (item.getQuantity() == null || item.getPrice() == null) {
                throw new BusinessException("采购明细数量/单价不能为空");
            }
            BigDecimal amount = item.getQuantity().multiply(item.getPrice());
            item.setAmount(amount);
            total = total.add(amount);
        }

        purchase.setPurchaseNo(generatePurchaseNo());
        purchase.setTotalAmount(total);
        purchase.setStatus(STATUS_PENDING);
        purchase.setOperatorId(SecurityContext.currentAdminId());
        if (purchase.getPurchaseDate() == null) {
            purchase.setPurchaseDate(LocalDate.now());
        }
        purchaseMapper.insert(purchase);

        for (PurchaseItem item : items) {
            item.setPurchaseId(purchase.getId());
            item.setId(null);
            purchaseItemMapper.insert(item);
        }
        return purchase;
    }

    /**
     * 生成采购单号:PO + yyyyMMdd + 6 位随机数
     */
    private String generatePurchaseNo() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int random = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "PO" + dateStr + random;
    }

    /**
     * 分页查询采购单列表,填充供应商名称与操作人名称
     */
    public IPage<Purchase> getPurchaseList(Long storeId, int page, int size,
                                           Integer status, LocalDate startDate, LocalDate endDate) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Purchase> wrapper = new LambdaQueryWrapper<Purchase>()
                .eq(Purchase::getStoreId, storeId)
                .orderByDesc(Purchase::getId);
        if (status != null) {
            wrapper.eq(Purchase::getStatus, status);
        }
        if (startDate != null) {
            wrapper.ge(Purchase::getPurchaseDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(Purchase::getPurchaseDate, endDate);
        }
        IPage<Purchase> p = purchaseMapper.selectPage(new Page<>(page, size), wrapper);
        fillTransientFields(p.getRecords());
        return p;
    }

    /** 批量填充 supplierName / operatorName */
    private void fillTransientFields(List<Purchase> records) {
        if (records.isEmpty()) return;
        // 一次性查所有 supplier
        Map<Long, String> supplierMap = new HashMap<>();
        for (Purchase p : records) {
            if (p.getSupplierId() != null && !supplierMap.containsKey(p.getSupplierId())) {
                supplierMap.put(p.getSupplierId(), null);
            }
        }
        for (Long sid : new ArrayList<>(supplierMap.keySet())) {
            Supplier s = supplierMapper.selectById(sid);
            supplierMap.put(sid, s == null ? null : s.getName());
        }
        // 一次性查所有 admin
        Map<Long, String> adminMap = new HashMap<>();
        for (Purchase p : records) {
            if (p.getOperatorId() != null && !adminMap.containsKey(p.getOperatorId())) {
                adminMap.put(p.getOperatorId(), null);
            }
        }
        for (Long aid : new ArrayList<>(adminMap.keySet())) {
            Admin a = adminMapper.selectById(aid);
            adminMap.put(aid, a == null ? null : a.getName());
        }
        for (Purchase p : records) {
            if (p.getSupplierId() != null) {
                p.setSupplierName(supplierMap.get(p.getSupplierId()));
            }
            if (p.getOperatorId() != null) {
                p.setOperatorName(adminMap.get(p.getOperatorId()));
            }
        }
    }

    /**
     * 查询采购单详情(含明细)
     */
    public PurchaseDetailDTO getPurchaseDetail(Long id) {
        Purchase purchase = purchaseMapper.selectById(id);
        if (purchase == null) {
            throw new BusinessException("采购单不存在");
        }
        SecurityContext.checkStoreAccess(purchase.getStoreId());
        // 填充名称
        if (purchase.getSupplierId() != null) {
            Supplier s = supplierMapper.selectById(purchase.getSupplierId());
            if (s != null) purchase.setSupplierName(s.getName());
        }
        if (purchase.getOperatorId() != null) {
            Admin a = adminMapper.selectById(purchase.getOperatorId());
            if (a != null) purchase.setOperatorName(a.getName());
        }
        List<PurchaseItem> items = purchaseItemMapper.selectList(
                new LambdaQueryWrapper<PurchaseItem>()
                        .eq(PurchaseItem::getPurchaseId, id)
                        .orderByAsc(PurchaseItem::getId));
        PurchaseDetailDTO dto = new PurchaseDetailDTO();
        dto.setPurchase(purchase);
        dto.setItems(items);
        return dto;
    }

    /**
     * 更新采购单状态(入库/取消)
     * - 入库:仅待入库可入库
     * - 取消:仅待入库可取消
     */
    public Purchase updateStatus(Long id, int targetStatus) {
        Purchase existing = purchaseMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("采购单不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (existing.getStatus() != STATUS_PENDING) {
            throw new BusinessException("当前采购单状态不允许此操作");
        }
        if (targetStatus != STATUS_INBOUND && targetStatus != STATUS_CANCELLED) {
            throw new BusinessException("非法的目标状态");
        }
        existing.setStatus(targetStatus);
        purchaseMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除采购单(仅待入库可删),同时删除明细
     */
    @Transactional
    public void deletePurchase(Long id) {
        Purchase existing = purchaseMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("采购单不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (existing.getStatus() != STATUS_PENDING) {
            throw new BusinessException("仅待入库状态的采购单可删除");
        }
        purchaseItemMapper.delete(new LambdaQueryWrapper<PurchaseItem>()
                .eq(PurchaseItem::getPurchaseId, id));
        purchaseMapper.deleteById(id);
    }
}
