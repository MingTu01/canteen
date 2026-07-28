package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BackupService 协调器层单元测试。
 *
 * 拆分后本类只测 BackupService 的协调逻辑(权限校验、参数路由、委托调用),
 * 数据导出与文件 I/O 的测试分别见 BackupExporterTest / BackupIOTest。
 */
@DisplayName("备份服务协调器测试")
class BackupServiceTest {

    private BackupService backupService;
    private RestoreService restoreService;
    private BackupExporter exporter;
    private BackupIO io;

    @BeforeEach
    void setUp() {
        // 拆分后 BackupService 仅依赖 BackupExporter + BackupIO + RestoreService(均 mock)
        restoreService = mock(RestoreService.class);
        exporter = mock(BackupExporter.class);
        io = mock(BackupIO.class);
        backupService = new BackupService(exporter, io, restoreService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("全库备份 - 门店管理员 - 应抛权限异常")
    void createBackup_full_storeAdmin_throws() {
        setSecurityContext(false, 5L, 2);
        assertThrows(SecurityException.class, () -> backupService.createBackup("full", null));
    }

    @Test
    @DisplayName("门店备份 - 门店管理员试图备份其他门店 - 应抛权限异常")
    void createBackup_otherStore_storeAdmin_throws() {
        setSecurityContext(false, 5L, 2);
        // checkStoreAccess 会比对 currentStoreId != targetStoreId
        assertThrows(SecurityException.class, () -> backupService.createBackup("store", 999L));
    }

    @Test
    @DisplayName("删除备份 - 文件不存在 - 应抛业务异常")
    void deleteBackup_nonExistent_throws() {
        setSecurityContext(true, 0L, 1);
        // io.resolveSafeFile 返回不存在的文件(真实文件名校验已下沉到 BackupIO.validateName)
        when(io.resolveSafeFile(anyString())).thenReturn(new java.io.File("non_existent.json.gz"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> backupService.deleteBackup("full_20260101_120000.json.gz"));
        assertNotNull(ex.getMessage());
    }

    @Test
    @DisplayName("导入 - 非 .json.gz 文件 - 应抛业务异常")
    void importBackup_wrongExtension_throws() {
        setSecurityContext(true, 0L, 1);
        assertThrows(BusinessException.class,
                () -> backupService.importBackup(new ByteArrayInputStream(new byte[0]), "evil.zip", false));
    }

    @Test
    @DisplayName("列表 - 应委托给 BackupIO 并返回空列表")
    void listBackups_emptyDir_returnsEmpty() {
        setSecurityContext(true, 0L, 1);
        when(io.ensureDir()).thenReturn(new java.io.File(System.getProperty("java.io.tmpdir")));
        assertDoesNotThrow(() -> backupService.listBackups());
    }

    @Test
    @DisplayName("下载 - 非法文件名 - 应委托给 BackupIO 抛业务异常")
    void getBackupFile_invalidName_throws() {
        setSecurityContext(true, 0L, 1);
        // io.resolveSafeFile 在非法文件名时抛 BusinessException(已在 BackupIOTest 中覆盖)
        when(io.resolveSafeFile("../etc/passwd")).thenThrow(new BusinessException("非法备份文件名"));
        when(io.resolveSafeFile("evil.txt")).thenThrow(new BusinessException("非法备份文件名"));
        assertThrows(BusinessException.class, () -> backupService.getBackupFile("../etc/passwd"));
        assertThrows(BusinessException.class, () -> backupService.getBackupFile("evil.txt"));
    }

    @Test
    @DisplayName("恢复 - 应委托给 RestoreService(经 Spring 代理让事务生效)")
    void restoreBackup_delegatesToRestoreService() {
        setSecurityContext(true, 0L, 1);
        Map<String, Object> expected = new HashMap<>();
        expected.put("restored", true);
        when(restoreService.restoreBackup("full_20260101_120000.json.gz")).thenReturn(expected);
        Map<String, Object> result = backupService.restoreBackup("full_20260101_120000.json.gz");
        assertEquals(true, result.get("restored"));
        // 确实经过 restoreService 引用,而非 this 自调用(防止 @Transactional 失效的 bug 复发)
        verify(restoreService).restoreBackup("full_20260101_120000.json.gz");
    }

    /**
     * 设置 SecurityContext 静态上下文。
     */
    private void setSecurityContext(boolean isSuperAdmin, Long storeId, int role) {
        RequestContextHolder.resetRequestAttributes();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(SecurityContext.ATTR_ADMIN_ID)).thenReturn(1L);
        when(request.getAttribute(SecurityContext.ATTR_STORE_ID)).thenReturn(storeId);
        // isSuperAdmin() 仅看 role == ROLE_SUPER_ADMIN,这里传 role 即可,isSuperAdmin 参数已被 role 覆盖
        when(request.getAttribute(SecurityContext.ATTR_ROLE)).thenReturn(isSuperAdmin ? SecurityContext.ROLE_SUPER_ADMIN : role);
        when(request.getAttribute(SecurityContext.ATTR_EMPLOYEE_ID)).thenReturn(null);
        ServletRequestAttributes attrs = mock(ServletRequestAttributes.class);
        when(attrs.getRequest()).thenReturn(request);
        RequestContextHolder.setRequestAttributes(attrs);
    }
}
