package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Department;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.DepartmentService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/department")
public class DepartmentController {
    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @GetMapping("/store/{storeId}")
    public ApiResponse<Map<String, Object>> getDepartmentsByStore(@PathVariable Long storeId,
                                                                  @RequestParam(defaultValue = "1") int page,
                                                                  @RequestParam(defaultValue = "10") int size,
                                                                  @RequestParam(required = false) String keyword) {
        SecurityContext.checkStoreAccess(storeId);
        IPage<Department> p = departmentService.getDepartmentsByStore(storeId, page, size, keyword);
        Map<String, Object> result = new HashMap<>();
        result.put("records", p.getRecords());
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Department> getDepartmentById(@PathVariable Long id) {
        Department dept = departmentService.getDepartmentById(id);
        if (dept != null) {
            SecurityContext.checkStoreAccess(dept.getStoreId());
        }
        return ApiResponse.success(dept);
    }

    @OperationLog("创建部门")
    @PostMapping
    public ApiResponse<Department> createDepartment(@RequestBody Department department) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        SecurityContext.checkStoreAccess(department.getStoreId());
        return ApiResponse.success(departmentService.createDepartment(department));
    }

    @OperationLog("更新部门")
    @PutMapping("/{id}")
    public ApiResponse<Department> updateDepartment(@PathVariable Long id, @RequestBody Department department) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        department.setId(id);
        SecurityContext.checkStoreAccess(department.getStoreId());
        return ApiResponse.success(departmentService.updateDepartment(department));
    }

    @OperationLog("删除部门")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDepartment(@PathVariable Long id) {
        if (SecurityContext.isEmployee()) {
            throw new com.example.canteen.exception.SecurityException("员工无权执行此操作");
        }
        Department existing = departmentService.getDepartmentById(id);
        if (existing != null) {
            SecurityContext.checkStoreAccess(existing.getStoreId());
        }
        departmentService.deleteDepartment(id);
        return ApiResponse.success(null);
    }
}
