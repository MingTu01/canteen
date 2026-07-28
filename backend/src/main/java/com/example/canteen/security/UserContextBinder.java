package com.example.canteen.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户上下文绑定工具:将 claims 中的角色/ID 信息写入 request attribute。
 *
 * 按角色只写对应属性,避免角色混淆:
 * - role=0(员工)→ 只写 employeeId
 * - role=1/2/4/5/6(管理员)→ 只写 adminId
 * - role=3(终端)→ 只写 storeId,不写 adminId/employeeId
 *
 * storeId 与 role 所有角色都写。
 */
public final class UserContextBinder {
    private UserContextBinder() {
    }

    /**
     * 按角色将上下文写入 request attribute。
     *
     * @param request    HTTP 请求
     * @param adminId    管理员 ID(非管理员角色可传 null)
     * @param employeeId 员工 ID(非员工角色可传 null)
     * @param storeId    门店 ID(所有角色都写)
     * @param role       角色
     */
    public static void bind(HttpServletRequest request, Long adminId, Long employeeId,
                            Long storeId, Integer role) {
        // storeId 所有角色都写
        request.setAttribute(SecurityContext.ATTR_STORE_ID, storeId);
        request.setAttribute(SecurityContext.ATTR_ROLE, role);

        if (role != null && role == 0) {
            // 员工只写 employeeId
            request.setAttribute(SecurityContext.ATTR_EMPLOYEE_ID, employeeId);
        } else if (role != null && (role == 1 || role == 2
                || role == SecurityContext.ROLE_FINANCE
                || role == SecurityContext.ROLE_CHEF
                || role == SecurityContext.ROLE_STORE_MANAGER)) {
            // 管理员(1/2/4/5/6)只写 adminId
            request.setAttribute(SecurityContext.ATTR_ADMIN_ID, adminId);
        }
        // role=3(终端)只写 storeId,不写 adminId/employeeId
    }
}
