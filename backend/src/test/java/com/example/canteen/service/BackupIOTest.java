package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BackupIO 单元测试。
 *
 * 重点覆盖:
 * 1. 文件名白名单与路径穿越防护(validateName / resolveSafeFile)。
 * 2. GZIP+JSON 文档读写往返(writeDocument / readDocument)。
 * 3. 文件名生成与文件大小格式化。
 *
 * 通过包级别构造器 BackupIO(String backupDir) 注入 @TempDir,
 * 避免依赖环境变量与反射改 static final 字段。
 */
@DisplayName("备份文件 I/O 测试")
class BackupIOTest {

    @TempDir
    Path tempDir;

    private BackupIO io;

    @BeforeEach
    void setUp() {
        // 通过包级别构造器注入临时目录,隔离真实文件系统
        io = new BackupIO(tempDir.toString());
    }

    /* ----- validateName / resolveSafeFile 路径安全 ----- */

    @Test
    @DisplayName("validateName - 合法文件名 - 应通过")
    void validateName_legal_pass() {
        assertDoesNotThrow(() -> io.validateName("full_20260101_120000.json.gz"));
        assertDoesNotThrow(() -> io.validateName("store5_import_20260101_120000.json.gz"));
    }

    @Test
    @DisplayName("validateName - 非法文件名 - 应抛业务异常(防路径穿越)")
    void validateName_illegal_throws() {
        // 不匹配白名单
        assertThrows(BusinessException.class, () -> io.validateName("evil.zip"));
        assertThrows(BusinessException.class, () -> io.validateName("normal.json"));
        assertThrows(BusinessException.class, () -> io.validateName("evil.tar.gz"));
        // 含路径分隔符(即使白名单可匹配,分隔符也应拒绝)
        assertThrows(BusinessException.class, () -> io.validateName("foo/bar.json.gz"));
        assertThrows(BusinessException.class, () -> io.validateName("..\\secret.json.gz"));
        // 路径穿越
        assertThrows(BusinessException.class, () -> io.validateName("../etc/passwd.json.gz"));
        // null
        assertThrows(BusinessException.class, () -> io.validateName(null));
    }

    @Test
    @DisplayName("resolveSafeFile - 合法文件名 - 应返回备份目录下的 File")
    void resolveSafeFile_legal_returnsFileUnderBackupDir() {
        File f = io.resolveSafeFile("full_20260101_120000.json.gz");
        assertTrue(f.getPath().startsWith(tempDir.toString()));
    }

    @Test
    @DisplayName("resolveSafeFile - 非法文件名 - 应抛业务异常")
    void resolveSafeFile_illegal_throws() {
        assertThrows(BusinessException.class, () -> io.resolveSafeFile("../etc/passwd.json.gz"));
        assertThrows(BusinessException.class, () -> io.resolveSafeFile("evil.txt"));
    }

    /* ----- writeDocument / readDocument 往返 ----- */

    @Test
    @DisplayName("writeDocument + readDocument - GZIP+JSON 往返应保持数据一致")
    void writeAndReadDocument_roundtrip_preservesData() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", "2.0");
        doc.put("type", "full");
        doc.put("storeId", null);
        doc.put("tableCount", 3);
        doc.put("totalRows", 100L);
        doc.put("data", Map.of("store", java.util.List.of()));

        File written = io.writeDocument(doc, "roundtrip_test.json.gz");
        assertTrue(written.exists());
        assertTrue(written.length() > 0);

        Map<String, Object> read = io.readDocument(written);
        assertEquals("2.0", read.get("version"));
        assertEquals("full", read.get("type"));
        assertNull(read.get("storeId"));
        assertEquals(3, ((Number) read.get("tableCount")).intValue());
        assertEquals(100, ((Number) read.get("totalRows")).intValue());
    }

    @Test
    @DisplayName("readDocument(InputStream) - 非法 GZIP - 应抛业务异常")
    void readDocumentFromStream_invalidGzip_throws() {
        assertThrows(BusinessException.class,
                () -> io.readDocument(new ByteArrayInputStream("not gzip".getBytes())));
    }

    @Test
    @DisplayName("loadDocument - 文件不存在 - 应抛业务异常")
    void loadDocument_nonExistent_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> io.loadDocument("full_20260101_120000.json.gz"));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("loadDocument - 非法文件名 - 应抛业务异常(先校验文件名再查存在性)")
    void loadDocument_invalidName_throws() {
        assertThrows(BusinessException.class, () -> io.loadDocument("../etc/passwd.json.gz"));
        assertThrows(BusinessException.class, () -> io.loadDocument("evil.tar.gz"));
        assertThrows(BusinessException.class, () -> io.loadDocument("normal.json"));
        assertThrows(BusinessException.class, () -> io.loadDocument("foo/bar.json.gz"));
    }

    /* ----- generateFileName / formatFileSize / formatDisplayTime ----- */

    @Test
    @DisplayName("generateFileName - 应含 prefix 与 .json.gz 后缀")
    void generateFileName_format() {
        String name = io.generateFileName("full");
        assertTrue(name.startsWith("full_"));
        assertTrue(name.endsWith(".json.gz"));
        // "full_"(5) + "yyyyMMdd_HHmmss"(15) + ".json.gz"(8) = 28
        assertEquals(28, name.length());
    }

    @Test
    @DisplayName("formatFileSize - 各量级格式正确")
    void formatFileSize_units() {
        assertEquals("0 B", io.formatFileSize(0));
        assertEquals("1023 B", io.formatFileSize(1023));
        assertEquals("1.0 KB", io.formatFileSize(1024));
        assertEquals("1.0 MB", io.formatFileSize(1024 * 1024));
        assertEquals("1.0 GB", io.formatFileSize(1024L * 1024 * 1024));
    }

    @Test
    @DisplayName("formatDisplayTime - epoch millis 转 yyyy-MM-dd HH:mm:ss")
    void formatDisplayTime_format() {
        // 2026-01-01 00:00:00 UTC = 1767225600000L
        // 系统时区可能不同,这里仅校验格式而非具体值
        String s = io.formatDisplayTime(1767225600000L);
        assertNotNull(s);
        assertTrue(s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    /* ----- ensureDir ----- */

    @Test
    @DisplayName("ensureDir - 应返回存在的目录")
    void ensureDir_returnsExistingDir() {
        File dir = io.ensureDir();
        assertTrue(dir.exists());
        assertTrue(dir.isDirectory());
    }
}
