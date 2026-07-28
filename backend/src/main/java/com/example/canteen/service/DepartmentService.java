package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Department;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;

/**
 * 部门 Service:封装 CRUD + 多租户校验 + 软删除(置 status=0)
 */
@Service
public class DepartmentService {
    private final DepartmentMapper departmentMapper;

    public DepartmentService(DepartmentMapper departmentMapper) {
        this.departmentMapper = departmentMapper;
    }

    public IPage<Department> getDepartmentsByStore(Long storeId, int page, int size, String keyword) {
        SecurityContext.checkStoreAccess(storeId);
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<Department>()
                .eq(Department::getStoreId, storeId)
                .eq(Department::getStatus, 1)
                .orderByDesc(Department::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Department::getName, keyword);
        }
        return departmentMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public Department getDepartmentById(Long id) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            throw new BusinessException("部门不存在");
        }
        SecurityContext.checkStoreAccess(department.getStoreId());
        return department;
    }

    public Department createDepartment(Department department) {
        SecurityContext.checkStoreAccess(department.getStoreId());
        if (department.getStatus() == null) {
            department.setStatus(1);
        }
        departmentMapper.insert(department);
        return department;
    }

    public Department updateDepartment(Department department) {
        Department existing = departmentMapper.selectById(department.getId());
        if (existing == null) {
            throw new BusinessException("部门不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 不允许跨门店迁移
        department.setStoreId(existing.getStoreId());
        departmentMapper.updateById(department);
        return department;
    }

    /** 软删除:置 status=0 */
    public void deleteDepartment(Long id) {
        Department existing = departmentMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("部门不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        existing.setStatus(0);
        departmentMapper.updateById(existing);
    }
}
