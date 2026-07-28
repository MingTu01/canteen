package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Supplier;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.SupplierMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SupplierService {
    private final SupplierMapper supplierMapper;

    public SupplierService(SupplierMapper supplierMapper) {
        this.supplierMapper = supplierMapper;
    }

    /** 分页查询供应商列表,支持按名称/联系人/电话关键字模糊搜索 */
    public IPage<Supplier> getSupplierList(Long storeId, int page, int size, String keyword) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<Supplier>()
                .eq(Supplier::getStoreId, storeId)
                .orderByDesc(Supplier::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Supplier::getName, keyword)
                    .or().like(Supplier::getContactPerson, keyword)
                    .or().like(Supplier::getPhone, keyword));
        }
        return supplierMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /** 查询门店下全部合作中的供应商(供采购单下拉选择) */
    public List<Supplier> getActiveSuppliers(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                .eq(Supplier::getStoreId, storeId)
                .eq(Supplier::getStatus, 1)
                .orderByDesc(Supplier::getId));
    }

    public Supplier getSupplierById(Long id) {
        return supplierMapper.selectById(id);
    }

    public Supplier createSupplier(Supplier supplier) {
        SecurityContext.checkStoreAccess(supplier.getStoreId());
        if (supplier.getStatus() == null) {
            supplier.setStatus(1);
        }
        supplierMapper.insert(supplier);
        return supplier;
    }

    public Supplier updateSupplier(Supplier supplier) {
        Supplier existing = supplierMapper.selectById(supplier.getId());
        if (existing == null) {
            throw new BusinessException("供应商不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 防止越权改门店
        supplier.setStoreId(existing.getStoreId());
        supplierMapper.updateById(supplier);
        return supplier;
    }

    public void deleteSupplier(Long id) {
        Supplier existing = supplierMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("供应商不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        supplierMapper.deleteById(id);
    }
}
