package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Material;
import com.example.canteen.entity.StockCount;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.MaterialMapper;
import com.example.canteen.mapper.StockCountMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MaterialService {
    private final MaterialMapper materialMapper;
    private final StockCountMapper stockCountMapper;

    public MaterialService(MaterialMapper materialMapper, StockCountMapper stockCountMapper) {
        this.materialMapper = materialMapper;
        this.stockCountMapper = stockCountMapper;
    }

    /**
     * 分页查询食材列表
     * @param lowStock true=仅显示预警(stockQty < minStock)
     */
    public IPage<Material> getMaterialList(Long storeId, int page, int size, String keyword, Boolean lowStock) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Material> wrapper = new LambdaQueryWrapper<Material>()
                .eq(Material::getStoreId, storeId)
                .orderByDesc(Material::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Material::getName, keyword)
                    .or().like(Material::getCategory, keyword));
        }
        if (Boolean.TRUE.equals(lowStock)) {
            // 列与列比较用原生 SQL
            wrapper.apply("stock_qty < min_stock");
        }
        return materialMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public Material getMaterialById(Long id) {
        return materialMapper.selectById(id);
    }

    public Material createMaterial(Material material) {
        SecurityContext.checkStoreAccess(material.getStoreId());
        if (material.getStockQty() == null) material.setStockQty(BigDecimal.ZERO);
        if (material.getMinStock() == null) material.setMinStock(BigDecimal.ZERO);
        materialMapper.insert(material);
        return material;
    }

    public Material updateMaterial(Material material) {
        Material existing = materialMapper.selectById(material.getId());
        if (existing == null) {
            throw new BusinessException("食材不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 防止越权改门店
        material.setStoreId(existing.getStoreId());
        materialMapper.updateById(material);
        return material;
    }

    public void deleteMaterial(Long id) {
        Material existing = materialMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("食材不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        materialMapper.deleteById(id);
    }

    /**
     * 入库:增加库存
     */
    @Transactional
    public Material inbound(Long id, BigDecimal qty, String remark) {
        Material existing = materialMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("食材不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("入库数量必须大于 0");
        }
        BigDecimal newStock = (existing.getStockQty() == null ? BigDecimal.ZERO : existing.getStockQty()).add(qty);
        existing.setStockQty(newStock);
        materialMapper.updateById(existing);
        // remark 仅作日志用,本表不存储流水;若需要可后续扩展库存流水表
        return existing;
    }

    /**
     * 出库:减少库存,不能为负
     */
    @Transactional
    public Material outbound(Long id, BigDecimal qty, String remark) {
        Material existing = materialMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("食材不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("出库数量必须大于 0");
        }
        BigDecimal current = existing.getStockQty() == null ? BigDecimal.ZERO : existing.getStockQty();
        if (current.compareTo(qty) < 0) {
            throw new BusinessException("库存不足,当前库存:" + current + " " + (existing.getUnit() == null ? "" : existing.getUnit()));
        }
        existing.setStockQty(current.subtract(qty));
        materialMapper.updateById(existing);
        return existing;
    }

    /**
     * 预警列表:stockQty < minStock
     */
    public List<Material> getLowStockList(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return materialMapper.selectList(new LambdaQueryWrapper<Material>()
                .eq(Material::getStoreId, storeId)
                .apply("stock_qty < min_stock")
                .orderByAsc(Material::getStockQty));
    }

    // ==================== 库存盘点 ====================

    /**
     * 创建盘点记录:记录系统库存与实际盘点数量,计算差异
     * @return 盘点记录(含差异)
     */
    @Transactional
    public StockCount createStockCount(Long materialId, BigDecimal countedQty, String remark) {
        Material material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new BusinessException("食材不存在");
        }
        SecurityContext.checkStoreAccess(material.getStoreId());
        if (countedQty == null || countedQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("盘点数量不能为负");
        }
        BigDecimal systemQty = material.getStockQty() == null ? BigDecimal.ZERO : material.getStockQty();
        BigDecimal difference = countedQty.subtract(systemQty);

        StockCount sc = new StockCount();
        sc.setStoreId(material.getStoreId());
        sc.setMaterialId(materialId);
        sc.setMaterialName(material.getName());
        sc.setSystemQty(systemQty);
        sc.setCountedQty(countedQty);
        sc.setDifference(difference);
        sc.setStatus(difference.compareTo(BigDecimal.ZERO) == 0 ? 2 : 1); // 无差异直接标记已处理
        sc.setOperatorId(SecurityContext.currentAdminId());
        sc.setRemark(remark);
        if (difference.compareTo(BigDecimal.ZERO) == 0) {
            sc.setResolvedAt(LocalDateTime.now());
        }
        stockCountMapper.insert(sc);
        return sc;
    }

    /**
     * 查询盘点记录列表
     * @param status null=全部 1=待处理 2=已处理
     */
    public IPage<StockCount> getStockCountList(Long storeId, int page, int size, Integer status) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<StockCount> wrapper = new LambdaQueryWrapper<StockCount>()
                .eq(StockCount::getStoreId, storeId)
                .orderByDesc(StockCount::getId);
        if (status != null) {
            wrapper.eq(StockCount::getStatus, status);
        }
        return stockCountMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 恢复差异:将库存调整为盘点数量,标记盘点记录为已处理
     */
    @Transactional
    public StockCount resolveStockCount(Long stockCountId) {
        StockCount sc = stockCountMapper.selectById(stockCountId);
        if (sc == null) {
            throw new BusinessException("盘点记录不存在");
        }
        SecurityContext.checkStoreAccess(sc.getStoreId());
        if (sc.getStatus() == 2) {
            throw new BusinessException("该盘点记录已处理");
        }
        Material material = materialMapper.selectById(sc.getMaterialId());
        if (material != null) {
            material.setStockQty(sc.getCountedQty());
            materialMapper.updateById(material);
        }
        sc.setStatus(2);
        sc.setResolvedAt(LocalDateTime.now());
        stockCountMapper.updateById(sc);
        return sc;
    }

    /**
     * 批量恢复差异:将该门店所有待处理盘点记录一次性恢复
     */
    @Transactional
    public int resolveAllStockCount(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        List<StockCount> pending = stockCountMapper.selectList(
                new LambdaQueryWrapper<StockCount>()
                        .eq(StockCount::getStoreId, storeId)
                        .eq(StockCount::getStatus, 1));
        int count = 0;
        for (StockCount sc : pending) {
            Material material = materialMapper.selectById(sc.getMaterialId());
            if (material != null) {
                material.setStockQty(sc.getCountedQty());
                materialMapper.updateById(material);
            }
            sc.setStatus(2);
            sc.setResolvedAt(LocalDateTime.now());
            stockCountMapper.updateById(sc);
            count++;
        }
        return count;
    }
}
