package com.example.canteen.security;

import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Employee;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT Token 生成与验证测试
 */
@DisplayName("JWT Token 测试")
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private final String testSecret = "enterprise-canteen-secret-key-2026-test-key-32bytes!";

    @BeforeEach
    void setUp() {
        SecretKey secretKey = Keys.hmacShaKeyFor(testSecret.getBytes());
        JwtParser jwtParser = Jwts.parser().verifyWith(secretKey).build();
        jwtTokenProvider = new JwtTokenProvider(secretKey, jwtParser);
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", 86400000L);
    }

    @Test
    @DisplayName("生成管理员Token - 包含正确的claims")
    void generateToken_Admin_ContainsCorrectClaims() {
        Admin admin = new Admin();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setName("超级管理员");
        admin.setStoreId(0L);
        admin.setRole(1);

        String token = jwtTokenProvider.generateToken(admin);

        assertNotNull(token);
        assertFalse(token.isEmpty());

        Map<String, Object> claims = jwtTokenProvider.validateToken(token);
        assertEquals(1, claims.get("id"));
        assertEquals("admin", claims.get("username"));
        assertEquals("超级管理员", claims.get("name"));
        assertEquals(0, claims.get("storeId"));
        assertEquals(1, claims.get("role"));
    }

    @Test
    @DisplayName("生成员工Token - 包含正确的claims")
    void generateEmployeeToken_ContainsCorrectClaims() {
        Employee employee = new Employee();
        employee.setId(1L);
        employee.setCardNo("CARD001");
        employee.setName("张明");
        employee.setStoreId(1L);
        employee.setDepartmentId(1L);
        employee.setBalance(new BigDecimal("500.00"));

        String token = jwtTokenProvider.generateEmployeeToken(employee);

        assertNotNull(token);
        assertFalse(token.isEmpty());

        Map<String, Object> claims = jwtTokenProvider.validateToken(token);
        assertEquals(1, claims.get("id"));
        assertEquals("CARD001", claims.get("cardNo"));
        assertEquals("张明", claims.get("name"));
        assertEquals(1, claims.get("storeId"));
        assertEquals(0, claims.get("role"));
    }

    @Test
    @DisplayName("验证Token - 无效Token抛出异常")
    void validateToken_InvalidToken_ThrowsException() {
        String invalidToken = "invalid.jwt.token";

        assertThrows(Exception.class, () -> jwtTokenProvider.validateToken(invalidToken));
    }

    @Test
    @DisplayName("验证Token - 篡改的Token抛出异常")
    void validateToken_TamperedToken_ThrowsException() {
        Admin admin = new Admin();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setName("管理员");
        admin.setStoreId(1L);
        admin.setRole(2);

        String token = jwtTokenProvider.generateToken(admin);
        String tamperedToken = token.substring(0, token.length() - 5) + "XXXXX";

        assertThrows(Exception.class, () -> jwtTokenProvider.validateToken(tamperedToken));
    }

    @Test
    @DisplayName("生成Token - 每次生成不同(含唯一 jti)")
    void generateToken_DifferentEachTime() {
        Admin admin = new Admin();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setName("管理员");
        admin.setStoreId(0L);
        admin.setRole(1);

        String token1 = jwtTokenProvider.generateToken(admin);
        String token2 = jwtTokenProvider.generateToken(admin);

        assertNotEquals(token1, token2, "每次生成的Token应该不同（因 jti 与 issuedAt 不同）");

        // 但两者都应验证通过
        assertNotNull(jwtTokenProvider.validateToken(token1));
        assertNotNull(jwtTokenProvider.validateToken(token2));
    }
}
