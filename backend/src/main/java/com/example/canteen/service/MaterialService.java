package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Material;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.MaterialMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MaterialService {
    private final MaterialMapper materialMapper;

    public MaterialService(MaterialMapper materialMapper) {
        this.materialMapper = materialMapper;
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
}
