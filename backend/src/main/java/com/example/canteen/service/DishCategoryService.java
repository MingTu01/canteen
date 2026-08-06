package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.entity.DishCategory;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DishCategoryMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DishCategoryService {
    private final DishCategoryMapper dishCategoryMapper;

    public DishCategoryService(DishCategoryMapper dishCategoryMapper) {
        this.dishCategoryMapper = dishCategoryMapper;
    }

    public List<DishCategory> getCategoriesByStore(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return dishCategoryMapper.selectList(new LambdaQueryWrapper<DishCategory>()
                .eq(DishCategory::getStoreId, storeId)
                .eq(DishCategory::getIsDeleted, 0)
                .orderByAsc(DishCategory::getSort)
                .orderByDesc(DishCategory::getId));
    }

    public DishCategory createCategory(DishCategory category) {
        SecurityContext.checkStoreAccess(category.getStoreId());
        if (category.getIsDeleted() == null) category.setIsDeleted(0);
        if (category.getStatus() == null) category.setStatus(1);
        if (category.getSort() == null) category.setSort(0);
        dishCategoryMapper.insert(category);
        return category;
    }

    public DishCategory updateCategory(DishCategory category) {
        DishCategory existing = dishCategoryMapper.selectById(category.getId());
        if (existing == null) {
            throw new BusinessException("分类不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 用 existing.storeId 覆盖请求体,防止越权改门店
        category.setStoreId(existing.getStoreId());
        dishCategoryMapper.updateById(category);
        return category;
    }

    public void deleteCategory(Long id) {
        DishCategory existing = dishCategoryMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("分类不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 注意:不能手动 setIsDeleted(1)+updateById(逻辑删除字段被全局配置托管,updateById 会跳过)。
        // 用 deleteById,MyBatis-Plus 自动转换为 UPDATE SET is_deleted=1。
        dishCategoryMapper.deleteById(id);
    }
}
