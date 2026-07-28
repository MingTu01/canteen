package com.example.canteen.security;

import com.example.canteen.exception.SecurityException;

/**
 * 权限校验工具类:统一封装常用权限检查,提升可读性,避免业务代码重复样板。
 *
 * 角色定义:
 * - ROLE_EMPLOYEE=0:  员工(H5/小程序用户)
 * - ROLE_SUPER_ADMIN=1: 超级管理员(可跨门店)
 * - ROLE_STORE_ADMIN=2: 门店管理员(仅本门店)
 * - role=3:           终端(扫码设备)
 */
public final class PermissionUtils {

    private PermissionUtils() {}

    /** 要求任意已登录用户(token 有效) */
    public static void requireLogin() {
        Long adminId = SecurityContext.currentAdminId();
        Long employeeId = SecurityContext.currentEmployeeId();
        if (adminId == null && employeeId == null) {
            throw new SecurityException("未登录");
        }
    }

    /** 要求员工身份 */
    public static void requireEmployee() {
        if (!SecurityContext.isEmployee()) {
            throw new SecurityException("仅员工可访问此接口");
        }
    }

    /** 要求管理员身份(超管或门店管理员) */
    public static void requireAdmin() {
        Integer role = SecurityContext.currentRole();
        if (role == null || (role != SecurityContext.ROLE_SUPER_ADMIN
                && role != SecurityContext.ROLE_STORE_ADMIN)) {
            throw new SecurityException("仅管理员可访问此接口");
        }
    }

    /** 要求超级管理员 */
    public static void requireSuperAdmin() {
        if (!SecurityContext.isSuperAdmin()) {
            throw new SecurityException("仅超级管理员可执行此操作");
        }
    }

    /** 要求门店管理员或超管,且能访问指定门店 */
    public static void requireStoreAccess(Long targetStoreId) {
        requireAdmin();
        SecurityContext.checkStoreAccess(targetStoreId);
    }

    /**
     * 要求当前用户就是指定员工本人(用于"修改自己密码""查看我的订单"等场景)。
     * 超管可绕过(便于排障);非超管管理员不允许伪装员工本人操作。
     *
     * 注意:之前实现存在运算符优先级 bug,
     * `(currentEmp != null && !currentEmp.equals(target)) && (currentAdmin != null)`
     * 在员工 token(currentAdmin=null)下永远为 false,导致任意员工可互访他人账号。
     * 已修复为显式分支判断。
     */
    public static void requireSelfOrSuperAdmin(Long targetEmployeeId) {
        if (SecurityContext.isSuperAdmin()) return;
        Long currentEmp = SecurityContext.currentEmployeeId();
        Long currentAdmin = SecurityContext.currentAdminId();
        if (currentEmp == null && currentAdmin == null) {
            throw new SecurityException("未登录");
        }
        if (targetEmployeeId == null) {
            throw new SecurityException("缺少员工参数");
        }
        // 员工身份:必须本人
        if (currentEmp != null && !currentEmp.equals(targetEmployeeId)) {
            throw new SecurityException("无权操作他人账号");
        }
        // 管理员(非超管)不允许伪装员工本人操作
        if (currentEmp == null && currentAdmin != null) {
            throw new SecurityException("管理员账号无权操作员工本人接口");
        }
    }

    /** 判断当前用户能否访问指定门店(不抛异常) */
    public static boolean canAccessStore(Long targetStoreId) {
        if (targetStoreId == null) return false;
        if (SecurityContext.isSuperAdmin()) return true;
        Long current = SecurityContext.currentStoreId();
        return current != null && current.equals(targetStoreId);
    }
}
