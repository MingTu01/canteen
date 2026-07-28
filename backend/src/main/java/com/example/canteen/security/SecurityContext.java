package com.example.canteen.security;

import com.example.canteen.exception.SecurityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 安全上下文工具:从当前请求中提取登录用户信息(adminId/storeId/role)
 * 用于多租户隔离与权限校验
 */
public class SecurityContext {
    public static final String ATTR_ADMIN_ID = "adminId";
    public static final String ATTR_EMPLOYEE_ID = "employeeId";
    public static final String ATTR_STORE_ID = "storeId";
    public static final String ATTR_ROLE = "role";
    /** role=1 超级管理员(可跨门店);role=2 门店管理员;员工 token role=0 */
    public static final int ROLE_SUPER_ADMIN = 1;
    public static final int ROLE_STORE_ADMIN = 2;
    public static final int ROLE_EMPLOYEE = 0;
    /** 财务岗:报表 + 充值 (role=3 保留给终端,见 JwtTokenProvider.generateTerminalToken) */
    public static final int ROLE_FINANCE = 4;
    /** 厨师长:订餐汇总 + 菜品 */
    public static final int ROLE_CHEF = 5;
    /** 店长:全店管理(不可删数据) */
    public static final int ROLE_STORE_MANAGER = 6;

    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

    public static Long currentAdminId() {
        return getLong(ATTR_ADMIN_ID);
    }

    public static Long currentEmployeeId() {
        return getLong(ATTR_EMPLOYEE_ID);
    }

    public static Long currentStoreId() {
        return getLong(ATTR_STORE_ID);
    }

    public static Integer currentRole() {
        Object v = currentRequest() == null ? null : currentRequest().getAttribute(ATTR_ROLE);
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    public static boolean isSuperAdmin() {
        Integer r = currentRole();
        return r != null && r == ROLE_SUPER_ADMIN;
    }

    /** 校验当前用户是否为超级管理员,不通过抛 SecurityException */
    public static void checkSuperAdmin(String message) {
        if (!isSuperAdmin()) {
            throw new SecurityException(message);
        }
    }

    /** 校验当前用户是否为超级管理员 */
    public static void checkSuperAdmin() {
        checkSuperAdmin("仅超级管理员可执行此操作");
    }

    public static boolean isEmployee() {
        Integer r = currentRole();
        return r != null && r == ROLE_EMPLOYEE;
    }

    /** 判断当前用户是否有指定角色之一 */
    public static boolean hasAnyRole(int... roles) {
        Integer r = currentRole();
        if (r == null) return false;
        for (int role : roles) {
            if (r == role) return true;
        }
        return false;
    }

    /** 是否管理级别角色(1/2/4/5/6),非员工(0)和终端(3) */
    public static boolean hasAdminLevel() {
        Integer r = currentRole();
        return r != null && r != ROLE_EMPLOYEE && r != 3
                && r >= ROLE_SUPER_ADMIN && r <= ROLE_STORE_MANAGER;
    }

    /** 是否有报表/财务访问权限 */
    public static boolean canViewFinance() {
        Integer role = currentRole();
        return role != null && (role == ROLE_SUPER_ADMIN || role == ROLE_STORE_ADMIN
                || role == ROLE_FINANCE || role == ROLE_STORE_MANAGER);
    }

    /** 是否有菜品管理权限(厨师可管理菜品) */
    public static boolean canManageDish() {
        Integer role = currentRole();
        return role != null && (role == ROLE_SUPER_ADMIN || role == ROLE_STORE_ADMIN
                || role == ROLE_CHEF || role == ROLE_STORE_MANAGER);
    }

    /** 是否有采购/库存管理权限 */
    public static boolean canManageProcurement() {
        Integer role = currentRole();
        return role != null && (role == ROLE_SUPER_ADMIN || role == ROLE_STORE_ADMIN
                || role == ROLE_CHEF || role == ROLE_STORE_MANAGER);
    }

    /** 是否有系统管理权限 */
    public static boolean canManageSystem() {
        Integer role = currentRole();
        return role != null && (role == ROLE_SUPER_ADMIN || role == ROLE_STORE_ADMIN);
    }

    /** 是否有对账/关店权限 */
    public static boolean canSettle() {
        Integer role = currentRole();
        return role != null && (role == ROLE_SUPER_ADMIN || role == ROLE_STORE_ADMIN
                || role == ROLE_FINANCE || role == ROLE_STORE_MANAGER);
    }

    /**
     * 校验当前用户是否有权访问目标门店。
     * 超管可访问任意门店;否则仅能访问自己所属门店。
     * 不通过则抛 SecurityException,由全局异常处理器转为 403。
     */
    public static void checkStoreAccess(Long targetStoreId) {
        if (targetStoreId == null) {
            throw new SecurityException("缺少门店参数");
        }
        if (isSuperAdmin()) {
            return;
        }
        Long current = currentStoreId();
        if (current == null || !current.equals(targetStoreId)) {
            throw new SecurityException("无权访问其他门店数据");
        }
    }

    /**
     * 校验当前用户是门店管理员或超管。
     * 等价于 StoreController 中重复 3 次的判断:
     *   if (role == null || (role != ROLE_SUPER_ADMIN && role != ROLE_STORE_ADMIN)) throw;
     */
    public static void checkStoreAdminOrAbove() {
        Integer role = currentRole();
        if (role == null || (role != ROLE_SUPER_ADMIN && role != ROLE_STORE_ADMIN)) {
            throw new SecurityException("仅门店管理员或超管可访问");
        }
    }

    /**
     * 校验当前用户是否有权操作指定员工的资源(订单/取餐等)。
     * fail-closed:未认证(role==null)或员工身份但 employeeId 缺失时拒绝,避免越权放行。
     * 员工仅能操作本人;其他角色(超管/门店管理员/终端/财务等)放行,由 checkStoreAccess 等其他校验把关。
     */
    public static void checkOrderOwnerOrAdmin(Long targetEmployeeId) {
        Integer role = currentRole();
        if (role == null) {
            // 未认证或JWT解析异常,拒绝而非放行
            throw new SecurityException("未认证或登录态异常");
        }
        if (role == ROLE_EMPLOYEE) {
            Long currentEmp = currentEmployeeId();
            if (currentEmp == null || !currentEmp.equals(targetEmployeeId)) {
                throw new SecurityException("无权操作他人订单");
            }
        }
        // 超管/门店管理员/终端/财务等角色放行(由 checkStoreAccess 等其他校验把关)
    }

    private static Long getLong(String attr) {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        Object v = req.getAttribute(attr);
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }
}
