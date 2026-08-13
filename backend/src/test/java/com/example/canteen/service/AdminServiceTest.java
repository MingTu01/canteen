package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.dto.AdminVO;
import com.example.canteen.dto.LoginDTO;
import com.example.canteen.entity.Admin;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.StoreMapper;
import com.example.canteen.security.JwtAuthenticationFilter;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 管理员服务单元测试
 */
@DisplayName("管理员服务测试")
class AdminServiceTest {

    private AdminMapper adminMapper;
    private StoreMapper storeMapper;
    private JwtTokenProvider jwtTokenProvider;
    private PasswordEncoder passwordEncoder;
    private JwtAuthenticationFilter jwtFilter;
    private LoginRateLimiter rateLimiter;
    private AdminService adminService;

    private Admin testAdmin;

    @BeforeEach
    void setUp() {
        adminMapper = mock(AdminMapper.class);
        storeMapper = mock(StoreMapper.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtFilter = mock(JwtAuthenticationFilter.class);
        rateLimiter = mock(LoginRateLimiter.class);
        when(jwtFilter.getRateLimiter()).thenReturn(rateLimiter);
        adminService = new AdminService(adminMapper, storeMapper, jwtTokenProvider, passwordEncoder, jwtFilter);

        testAdmin = new Admin();
        testAdmin.setId(1L);
        testAdmin.setUsername("admin");
        testAdmin.setPassword("$2a$10$encodedhash");
        testAdmin.setName("超级管理员");
        testAdmin.setStoreId(0L);
        testAdmin.setRole(1);
        testAdmin.setStatus(1);
    }

    @Test
    @DisplayName("管理员登录 - 正常流程")
    void login_Success() {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("123456");

        when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testAdmin);
        when(passwordEncoder.matches("123456", "$2a$10$encodedhash")).thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Admin.class))).thenReturn("mock-jwt-token");

        Map<String, Object> result = adminService.login(loginDTO);

        assertNotNull(result);
        assertEquals("mock-jwt-token", result.get("token"));
        assertInstanceOf(AdminVO.class, result.get("admin"));
        assertEquals("admin", ((AdminVO) result.get("admin")).getUsername());

        verify(rateLimiter).checkLocked("admin");
        verify(rateLimiter).recordSuccess("admin");
        verify(jwtTokenProvider).generateToken(testAdmin);
    }

    @Test
    @DisplayName("管理员登录 - 用户不存在")
    void login_UserNotFound() {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("nonexistent");
        loginDTO.setPassword("123456");

        when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        SecurityException exception = assertThrows(SecurityException.class,
                () -> adminService.login(loginDTO));
        assertEquals("用户名或密码错误", exception.getMessage());

        verify(rateLimiter).recordFail("nonexistent");
        verify(jwtTokenProvider, never()).generateToken(any(Admin.class));
    }

    @Test
    @DisplayName("管理员登录 - 密码错误")
    void login_WrongPassword() {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("wrongpassword");

        when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testAdmin);
        when(passwordEncoder.matches("wrongpassword", "$2a$10$encodedhash")).thenReturn(false);

        SecurityException exception = assertThrows(SecurityException.class,
                () -> adminService.login(loginDTO));
        assertEquals("用户名或密码错误", exception.getMessage());

        verify(rateLimiter).recordFail("admin");
        verify(jwtTokenProvider, never()).generateToken(any(Admin.class));
    }

    @Test
    @DisplayName("管理员登录 - 正确密码验证")
    void login_PasswordMatches() {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("admin");
        loginDTO.setPassword("123456");

        when(adminMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testAdmin);
        when(passwordEncoder.matches("123456", "$2a$10$encodedhash")).thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Admin.class))).thenReturn("valid-token");

        Map<String, Object> result = adminService.login(loginDTO);

        assertNotNull(result);
        assertNotNull(result.get("token"));
        verify(jwtTokenProvider).generateToken(testAdmin);
    }
}
