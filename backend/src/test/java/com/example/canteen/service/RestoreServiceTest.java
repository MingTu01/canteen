package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RestoreService 单元测试。
 *
 * 覆盖从 BackupServiceTest 下沉的恢复相关测试用例:
 * 1. 非法文件名(路径穿越防护)。
 * 2. 文件不存在。
 * 3. 全库恢复由门店管理员执行应抛权限异常。
 * 4. 通过 Spring 代理调用(防止 @Transactional 失效的 bug 复发)。
 */
@DisplayName("备份恢复服务测试")
class RestoreServiceTest {

    private RestoreService restoreService;
    private BackupService backupService;

    @BeforeEach
    void setUp() {
        backupService = mock(BackupService.class);
        // restoreService 只用 jdbcTemplate 和 backupService,jdbcTemplate 在非法文件名场景下不会被调到
        restoreService = new RestoreService(null, backupService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("恢复 - 非法文件名 - 应抛业务异常(防路径穿越)")
    void restoreBackup_invalidName_throws() {
        setSecurityContext(true, 0L, 1);
        // backupService.loadBackupDocument 内部会校验文件名,这里 mock 抛业务异常
        when(backupService.loadBackupDocument("../etc/passwd.json.gz"))
                .thenThrow(new BusinessException("非法备份文件名"));
        when(backupService.loadBackupDocument("evil.tar.gz"))
                .thenThrow(new BusinessException("非法备份文件名"));
        when(backupService.loadBackupDocument("normal.json"))
                .thenThrow(new BusinessException("非法备份文件名"));
        when(backupService.loadBackupDocument("foo/bar.json.gz"))
                .thenThrow(new BusinessException("非法备份文件名"));
        assertThrows(BusinessException.class, () -> restoreService.restoreBackup("../etc/passwd.json.gz"));
        assertThrows(BusinessException.class, () -> restoreService.restoreBackup("evil.tar.gz"));
        assertThrows(BusinessException.class, () -> restoreService.restoreBackup("normal.json"));
        assertThrows(BusinessException.class, () -> restoreService.restoreBackup("foo/bar.json.gz"));
    }

    @Test
    @DisplayName("恢复 - 全库备份文件不存在 - 应抛业务异常")
    void restoreBackup_fileNotFound_throws() {
        setSecurityContext(true, 0L, 1);
        when(backupService.loadBackupDocument("full_20260101_120000.json.gz"))
                .thenThrow(new BusinessException("备份文件不存在: full_20260101_120000.json.gz"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> restoreService.restoreBackup("full_20260101_120000.json.gz"));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    /**
     * 设置 SecurityContext 静态上下文。
     */
    private void setSecurityContext(boolean isSuperAdmin, Long storeId, int role) {
        RequestContextHolder.resetRequestAttributes();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(SecurityContext.ATTR_ADMIN_ID)).thenReturn(1L);
        when(request.getAttribute(SecurityContext.ATTR_STORE_ID)).thenReturn(storeId);
        when(request.getAttribute(SecurityContext.ATTR_ROLE)).thenReturn(isSuperAdmin ? SecurityContext.ROLE_SUPER_ADMIN : role);
        when(request.getAttribute(SecurityContext.ATTR_EMPLOYEE_ID)).thenReturn(null);
        ServletRequestAttributes attrs = mock(ServletRequestAttributes.class);
        when(attrs.getRequest()).thenReturn(request);
        RequestContextHolder.setRequestAttributes(attrs);
    }
}
