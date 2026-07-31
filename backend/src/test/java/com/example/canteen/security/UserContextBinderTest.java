package com.example.canteen.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserContextBinder 单元测试
 *
 * 覆盖本次 P2 修复:deviceLabel 传播
 * - role=3(终端)时写入 deviceLabel
 * - role=0/1/2/4/5/6 时不写 deviceLabel
 * - 终端 token 刷新后 deviceLabel 能从请求上下文取回
 */
@DisplayName("用户上下文绑定测试")
class UserContextBinderTest {

    @Test
    @DisplayName("终端 token(role=3) - 写入 deviceLabel")
    void bind_terminalRole_writesDeviceLabel() {
        HttpServletRequest request = new MockHttpServletRequest();
        String deviceLabel = "前台订餐机-01";

        UserContextBinder.bind(request, null, null, 1L, 3, deviceLabel);

        assertEquals(1L, request.getAttribute(SecurityContext.ATTR_STORE_ID));
        assertEquals(3, request.getAttribute(SecurityContext.ATTR_ROLE));
        assertEquals(deviceLabel, request.getAttribute(SecurityContext.ATTR_DEVICE_LABEL));
        // 终端不写 adminId/employeeId
        assertNull(request.getAttribute(SecurityContext.ATTR_ADMIN_ID));
        assertNull(request.getAttribute(SecurityContext.ATTR_EMPLOYEE_ID));
    }

    @Test
    @DisplayName("终端 token(role=3) - deviceLabel 为 null 时写入空字符串")
    void bind_terminalRole_nullDeviceLabel_writesEmptyString() {
        HttpServletRequest request = new MockHttpServletRequest();

        UserContextBinder.bind(request, null, null, 1L, 3, null);

        assertEquals("", request.getAttribute(SecurityContext.ATTR_DEVICE_LABEL));
    }

    @Test
    @DisplayName("员工 token(role=0) - 不写 deviceLabel")
    void bind_employeeRole_doesNotWriteDeviceLabel() {
        HttpServletRequest request = new MockHttpServletRequest();

        UserContextBinder.bind(request, null, 100L, 1L, 0, "should-be-ignored");

        assertEquals(100L, request.getAttribute(SecurityContext.ATTR_EMPLOYEE_ID));
        assertNull(request.getAttribute(SecurityContext.ATTR_DEVICE_LABEL),
                "员工 token 不应写入 deviceLabel");
    }

    @Test
    @DisplayName("管理员 token(role=1) - 不写 deviceLabel")
    void bind_superAdminRole_doesNotWriteDeviceLabel() {
        HttpServletRequest request = new MockHttpServletRequest();

        UserContextBinder.bind(request, 1L, null, 0L, 1, "should-be-ignored");

        assertEquals(1L, request.getAttribute(SecurityContext.ATTR_ADMIN_ID));
        assertNull(request.getAttribute(SecurityContext.ATTR_DEVICE_LABEL),
                "管理员 token 不应写入 deviceLabel");
    }

    @Test
    @DisplayName("门店管理员 token(role=2) - 不写 deviceLabel")
    void bind_storeAdminRole_doesNotWriteDeviceLabel() {
        HttpServletRequest request = new MockHttpServletRequest();

        UserContextBinder.bind(request, 2L, null, 1L, 2, "should-be-ignored");

        assertEquals(2L, request.getAttribute(SecurityContext.ATTR_ADMIN_ID));
        assertNull(request.getAttribute(SecurityContext.ATTR_DEVICE_LABEL));
    }

    @Test
    @DisplayName("SecurityContext.currentDeviceLabel() - 能从请求上下文取回 deviceLabel")
    void currentDeviceLabel_retrievesFromRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String deviceLabel = "取餐机-02";
        request.setAttribute(SecurityContext.ATTR_DEVICE_LABEL, deviceLabel);

        // 模拟 RequestContextHolder(通过 SecurityContext.currentRequest())
        // 注意:SecurityContext.currentRequest() 使用 RequestContextHolder,
        // 需要设置 RequestContextHolder 才能取到
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));

        try {
            assertEquals(deviceLabel, SecurityContext.currentDeviceLabel());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("SecurityContext.currentDeviceLabel() - 无请求上下文时返回 null")
    void currentDeviceLabel_noContext_returnsNull() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        assertNull(SecurityContext.currentDeviceLabel());
    }
}
