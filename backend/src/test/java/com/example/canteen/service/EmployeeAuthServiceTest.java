package com.example.canteen.service;

import com.example.canteen.entity.Employee;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.LoginRateLimiter;
import com.example.canteen.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EmployeeAuthService 单元测试。
 *
 * 覆盖拆分后新加的认证服务:
 * 1. login:卡号+密码登录,员工不存在 / 密码错误应返回 fail。
 * 2. phoneLogin:手机号登录。
 * 3. changePassword:校验原密码 + 新密码长度 + 仅本人可改。
 * 4. generateQrcode:员工不存在返回 null。
 */
@DisplayName("员工鉴权服务测试")
class EmployeeAuthServiceTest {

    private EmployeeMapper employeeMapper;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private LoginRateLimiter rateLimiter;
    private EmployeeAuthService authService;

    @BeforeEach
    void setUp() {
        employeeMapper = mock(EmployeeMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        rateLimiter = mock(LoginRateLimiter.class);
        authService = new EmployeeAuthService(employeeMapper, passwordEncoder, jwtTokenProvider, rateLimiter);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /* ----- login ----- */

    @Test
    @DisplayName("login - 员工不存在 - 应返回 fail(卡号或密码错误)")
    void login_employeeNotFound_returnsFail() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        EmployeeAuthService.LoginResult result = authService.login("C001", "pass", 5L);
        assertFalse(result.isSuccess());
        assertEquals("卡号或密码错误", result.getErrorMessage());
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("login - 密码错误 - 应返回 fail")
    void login_wrongPassword_returnsFail() {
        Employee emp = new Employee();
        emp.setId(1L);
        emp.setPassword("encodedHash");
        when(employeeMapper.selectOne(any())).thenReturn(emp);
        when(passwordEncoder.matches("wrong", "encodedHash")).thenReturn(false);

        EmployeeAuthService.LoginResult result = authService.login("C001", "wrong", 5L);
        assertFalse(result.isSuccess());
        assertEquals("卡号或密码错误", result.getErrorMessage());
    }

    @Test
    @DisplayName("login - 密码正确 - 应返回 success 并生成 token")
    void login_correctPassword_returnsSuccess() {
        Employee emp = new Employee();
        emp.setId(1L);
        emp.setPassword("encodedHash");
        when(employeeMapper.selectOne(any())).thenReturn(emp);
        when(passwordEncoder.matches("right", "encodedHash")).thenReturn(true);
        when(jwtTokenProvider.generateEmployeeToken(emp)).thenReturn("token-xyz");

        EmployeeAuthService.LoginResult result = authService.login("C001", "right", 5L);
        assertTrue(result.isSuccess());
        assertEquals("token-xyz", result.getToken());
        assertEquals(1L, result.getEmployee().getId());
    }

    /* ----- phoneLogin ----- */

    @Test
    @DisplayName("phoneLogin - 手机号不存在 - 应返回 fail")
    void phoneLogin_phoneNotFound_returnsFail() {
        when(employeeMapper.selectByPhone("13800000000")).thenReturn(null);
        EmployeeAuthService.LoginResult result = authService.phoneLogin("13800000000", "pass");
        assertFalse(result.isSuccess());
        assertEquals("手机号或密码错误", result.getErrorMessage());
    }

    @Test
    @DisplayName("phoneLogin - 密码正确 - 应返回 success")
    void phoneLogin_correctPassword_returnsSuccess() {
        Employee emp = new Employee();
        emp.setId(2L);
        emp.setPassword("encodedHash");
        when(employeeMapper.selectByPhone("13800000000")).thenReturn(emp);
        when(passwordEncoder.matches("right", "encodedHash")).thenReturn(true);
        when(jwtTokenProvider.generateEmployeeToken(emp)).thenReturn("token-phone");

        EmployeeAuthService.LoginResult result = authService.phoneLogin("13800000000", "right");
        assertTrue(result.isSuccess());
        assertEquals("token-phone", result.getToken());
    }

    /* ----- changePassword ----- */

    @Test
    @DisplayName("changePassword - 未登录 - 应抛 SecurityException")
    void changePassword_notLoggedIn_throws() {
        // 不设置 RequestContextHolder,SecurityContext.currentEmployeeId() 返回 null
        assertThrows(com.example.canteen.exception.SecurityException.class,
                () -> authService.changePassword(1L, "old", "newpass12"));
    }

    @Test
    @DisplayName("changePassword - 修改他人密码 - 应抛 SecurityException")
    void changePassword_others_throws() {
        setEmployeeContext(1L);
        assertThrows(com.example.canteen.exception.SecurityException.class,
                () -> authService.changePassword(2L, "old", "newpass12"));
    }

    @Test
    @DisplayName("changePassword - 原密码错误 - 应抛 BusinessException(并记录限流失败)")
    void changePassword_wrongOld_throws() {
        setEmployeeContext(1L);
        Employee emp = new Employee();
        emp.setId(1L);
        emp.setPassword("encodedHash");
        when(employeeMapper.selectById(1L)).thenReturn(emp);
        when(passwordEncoder.matches("wrong", "encodedHash")).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> authService.changePassword(1L, "wrong", "newpass12"));
    }

    @Test
    @DisplayName("changePassword - 新密码短于 8 位 - 应抛 BusinessException")
    void changePassword_shortNew_throws() {
        setEmployeeContext(1L);
        Employee emp = new Employee();
        emp.setId(1L);
        emp.setPassword("encodedHash");
        when(employeeMapper.selectById(1L)).thenReturn(emp);
        when(passwordEncoder.matches("right", "encodedHash")).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> authService.changePassword(1L, "right", "short"));
    }

    @Test
    @DisplayName("changePassword - 正常流程 - 应更新密码与 passwordUpdatedAt")
    void changePassword_success_updates() {
        setEmployeeContext(1L);
        Employee emp = new Employee();
        emp.setId(1L);
        emp.setPassword("encodedHash");
        when(employeeMapper.selectById(1L)).thenReturn(emp);
        when(passwordEncoder.matches("right", "encodedHash")).thenReturn(true);
        when(passwordEncoder.encode("newpass12")).thenReturn("newEncodedHash");

        authService.changePassword(1L, "right", "newpass12");

        assertEquals("newEncodedHash", emp.getPassword());
        assertNotNull(emp.getPasswordUpdatedAt());
        verify(employeeMapper).updateById(emp);
    }

    /* ----- generateQrcode ----- */

    @Test
    @DisplayName("generateQrcode - 员工不存在 - 应返回 null")
    void generateQrcode_employeeNotFound_returnsNull() {
        when(employeeMapper.selectById(999L)).thenReturn(null);
        assertNull(authService.generateQrcode(999L));
    }

    @Test
    @DisplayName("generateQrcode - 正常流程 - 应返回含 sign 与 expire 的 Map")
    void generateQrcode_success_returnsMap() {
        Employee emp = new Employee();
        emp.setId(1L);
        emp.setCardNo("C001");
        emp.setStoreId(5L);
        emp.setName("张三");
        when(employeeMapper.selectById(1L)).thenReturn(emp);
        // 全部用 matcher,不能混用 raw value 与 matcher
        when(jwtTokenProvider.generateQrcodeSign(eq("C001"), eq(5L), eq(1L), anyLong()))
                .thenReturn("signed-hmac");

        var result = authService.generateQrcode(1L);
        assertNotNull(result);
        assertEquals("C001", result.get("cardNo"));
        assertEquals(5L, result.get("storeId"));
        assertEquals(1L, result.get("employeeId"));
        assertEquals("张三", result.get("name"));
        assertEquals("signed-hmac", result.get("sign"));
        assertNotNull(result.get("expire"));
    }

    /**
     * 设置 SecurityContext 静态上下文为员工身份。
     */
    private void setEmployeeContext(Long employeeId) {
        RequestContextHolder.resetRequestAttributes();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(SecurityContext.ATTR_EMPLOYEE_ID)).thenReturn(employeeId);
        when(request.getAttribute(SecurityContext.ATTR_ADMIN_ID)).thenReturn(null);
        when(request.getAttribute(SecurityContext.ATTR_ROLE)).thenReturn(0); // 员工 role=0
        ServletRequestAttributes attrs = mock(ServletRequestAttributes.class);
        when(attrs.getRequest()).thenReturn(request);
        RequestContextHolder.setRequestAttributes(attrs);
    }
}
