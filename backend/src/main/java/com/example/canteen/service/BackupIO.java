package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 备份文件 I/O 工具。
 *
 * 职责:
 * - 文件名白名单校验 + 路径穿越防护(validateName / resolveSafeFile)
 * - 备份目录管理(ensureDir)
 * - GZIP+JSON 文档读写(readMeta / readDocument / writeDocument)
 * - 备份文件加载入口(loadDocument):校验文件名 → 安全路径 → 读取 GZIP 文档
 * - 文件名生成(generateFileName)与文件大小格式化(formatFileSize)
 *
 * 从 BackupService 拆分。无业务依赖,易于单元测试。
 *
 * 可测试性:提供包级别构造器 BackupIO(String backupDir) 注入备份目录,
 * 单元测试可传临时目录,避免依赖环境变量与反射改 static final 字段。
 */
@Service
public class BackupIO {

    private final String backupDir;
    private final ObjectMapper objectMapper;

    public BackupIO() {
        this(BackupConstants.BACKUP_DIR);
    }

    /** 测试用:可注入备份目录。 */
    BackupIO(String backupDir) {
        this.backupDir = backupDir;
        this.objectMapper = new ObjectMapper();
        // 注册 JSR310 模块以支持 java.time.* 类型(LocalDateTime/Instant 等)
        // JdbcTemplate.queryForList 会把 MySQL DATETIME 列转为 LocalDateTime,
        // 默认 ObjectMapper 无法序列化,会抛 "Java 8 date/time type not supported by default"
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /** 确保备份目录存在,不存在则创建。 */
    public File ensureDir() {
        File dir = new File(backupDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BusinessException("无法创建备份目录: " + backupDir);
        }
        return dir;
    }

    /** 校验备份文件名:必须匹配白名单,且不含路径分隔符。 */
    public void validateName(String name) {
        if (name == null || !BackupConstants.BACKUP_NAME_PATTERN.matcher(name).matches()) {
            throw new BusinessException("非法备份文件名");
        }
        // 防路径穿越
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new BusinessException("非法备份文件名");
        }
    }

    /**
     * 安全解析备份文件:在白名单校验基础上,再做 canonical path 双重校验,
     * 确保解析后的绝对路径确实位于备份目录之内(防 Zip Slip / 符号链接穿越)。
     */
    public File resolveSafeFile(String name) {
        validateName(name);
        File dir = ensureDir();
        File file = new File(dir, name);
        try {
            String canonicalDir = dir.getCanonicalPath();
            String canonicalFile = file.getCanonicalPath();
            // 必须以备份目录为前缀(兼容跨平台路径分隔符)
            if (!canonicalFile.startsWith(canonicalDir + File.separator)
                    && !canonicalFile.equals(canonicalDir)) {
                throw new BusinessException("非法备份文件路径");
            }
        } catch (IOException e) {
            throw new BusinessException("解析备份文件路径失败: " + e.getMessage());
        }
        return file;
    }

    /** 读取备份文件的元信息(不加载全部数据,但需完整解析 JSON)。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMeta(File file) {
        try (InputStream is = new GZIPInputStream(new FileInputStream(file))) {
            // 解析整个文档以获取元信息(JSON 元信息在前,但需完整解析)
            Map<String, Object> doc = objectMapper.readValue(is, Map.class);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("version", doc.get("version"));
            meta.put("type", doc.get("type"));
            meta.put("storeId", doc.get("storeId"));
            meta.put("storeName", doc.get("storeName"));
            meta.put("tableCount", doc.get("tableCount"));
            meta.put("totalRows", doc.get("totalRows"));
            return meta;
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readDocument(File file) {
        try (InputStream is = new GZIPInputStream(new FileInputStream(file))) {
            return objectMapper.readValue(is, Map.class);
        } catch (IOException e) {
            throw new BusinessException("读取备份文件失败: " + e.getMessage());
        }
    }

    /** 从 InputStream 读取 GZIP+JSON 文档(用于导入场景,文件尚未落盘)。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readDocument(InputStream is) {
        try (InputStream gz = new GZIPInputStream(is)) {
            return objectMapper.readValue(gz, Map.class);
        } catch (IOException e) {
            throw new BusinessException("读取备份文件失败: " + e.getMessage());
        }
    }

    /**
     * 加载备份文档(供 RestoreService 调用):校验文件名 + 解析安全路径 + 读取 GZIP 文档。
     * 封装了 validateName / resolveSafeFile / readDocument 三个 helper。
     */
    public Map<String, Object> loadDocument(String backupName) {
        validateName(backupName);
        File file = resolveSafeFile(backupName);
        if (!file.exists()) {
            throw new BusinessException("备份文件不存在: " + backupName);
        }
        return readDocument(file);
    }

    /** 将备份文档以 GZIP+JSON 写入备份目录。 */
    public File writeDocument(Map<String, Object> document, String fileName) {
        File outputFile = new File(ensureDir(), fileName);
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(outputFile))) {
            objectMapper.writeValue(os, document);
        } catch (IOException e) {
            throw new BusinessException("写入备份文件失败: " + e.getMessage());
        }
        return outputFile;
    }

    /**
     * 生成规范备份文件名:prefix + "_" + yyyyMMdd_HHmmss + ".json.gz"。
     * 使用线程安全的 DateTimeFormatter 替代 SimpleDateFormat。
     */
    public String generateFileName(String prefix) {
        return prefix + "_" + LocalDateTime.now().format(BackupConstants.FILE_TS) + ".json.gz";
    }

    /** 文件大小人类可读格式(B/KB/MB/GB)。 */
    public String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    /** 列表展示用:把 epochMillis 转为 "yyyy-MM-dd HH:mm:ss" 字符串。 */
    public String formatDisplayTime(long epochMillis) {
        return BackupConstants.DISPLAY_TS.format(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }
}
