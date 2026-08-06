package com.example.canteen.service;

import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Purchase;
import com.example.canteen.entity.Store;
import com.example.canteen.entity.Supplier;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.PurchaseMapper;
import com.example.canteen.mapper.StoreMapper;
import com.example.canteen.mapper.SupplierMapper;
import org.springframework.stereotype.Component;

/**
 * 操作日志详情名称解析器。
 * 供 OperationLogAspect 注册为 SpEL 变量,使日志模板可将实体 ID 解析为人类可读名称。
 */
@Component
public class LogDetailResolver {

    private final EmployeeMapper employeeMapper;
    private final StoreMapper storeMapper;
    private final SupplierMapper supplierMapper;
    private final PurchaseMapper purchaseMapper;

    public LogDetailResolver(EmployeeMapper employeeMapper,
                             StoreMapper storeMapper,
                             SupplierMapper supplierMapper,
                             PurchaseMapper purchaseMapper) {
        this.employeeMapper = employeeMapper;
        this.storeMapper = storeMapper;
        this.supplierMapper = supplierMapper;
        this.purchaseMapper = purchaseMapper;
    }

    /** 餐次:1-早餐 2-午餐 3-晚餐 */
    public String mealType(Integer type) {
        if (type == null) return "";
        switch (type) {
            case 1: return "早餐";
            case 2: return "午餐";
            case 3: return "晚餐";
            default: return "餐次" + type;
        }
    }

    /** 采购单状态:1-待入库 2-已入库 3-已取消 */
    public String purchaseStatus(Integer status) {
        if (status == null) return "";
        switch (status) {
            case 1: return "待入库";
            case 2: return "已入库";
            case 3: return "已取消";
            default: return "状态" + status;
        }
    }

    /** 员工姓名,查不到则返回 "员工ID xxx" */
    public String employeeName(Long id) {
        if (id == null) return "";
        Employee e = employeeMapper.selectById(id);
        return e != null ? e.getName() : "员工ID " + id;
    }

    /** 食堂名称 */
    public String storeName(Long id) {
        if (id == null) return "";
        Store s = storeMapper.selectById(id);
        return s != null ? s.getName() : "门店ID " + id;
    }

    /** 供应商名称 */
    public String supplierName(Long id) {
        if (id == null) return "";
        Supplier s = supplierMapper.selectById(id);
        return s != null ? s.getName() : "供应商ID " + id;
    }

    /** 采购单号 */
    public String purchaseNo(Long id) {
        if (id == null) return "";
        Purchase p = purchaseMapper.selectById(id);
        return p != null ? (p.getPurchaseNo() != null ? p.getPurchaseNo() : "采购单" + id) : "采购单ID " + id;
    }
}