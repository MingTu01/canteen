package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 企业级备份服务(协调器)。
 *
 * 设计要点:
 * 1. JSON+GZIP 格式,同时兼容 H2(dev)与 MySQL(prod),不依赖外部 mysqldump。
 * 2. 多租户隔离:超管做全库备份/恢复;门店管理员仅备份/恢复本门店数据。
 * 3. 导出/导入:下载备份为真实文件流;支持上传备份文件导入。
 * 4. 数据导出与文件 I/O 已下沉到 BackupExporter / BackupIO / BackupConstants。
 * 5. 恢复逻辑(@Transactional)与定时备份调度已拆分至 RestoreService / BackupSchedulerService。
 *
 * 事务修复:原 importBackup 通过 this.restoreBackup() 自调用,Spring AOP 代理不生效,
 * @Transactional 失效。现改为注入 RestoreService 调用 restoreService.restoreBackup(),
 * 通过 Spring 代理让事务生效,恢复失败时已 DELETE 的数据可正常回滚。
 */
@Service
public class BackupService {

    private final BackupExporter exporter;
    private final BackupIO io;
    private final RestoreService restoreService;

    public BackupService(BackupExporter exporter,
                         BackupIO io,
                         @Lazy RestoreService restoreService) {
        this.exporter = exporter;
        this.io = io;
        this.restoreService = restoreService;
    }

    /* ============================================================
     * 列表 / 元信息
     * ============================================================ */

    /** 列出当前身份可见的备份文件。超管看全部;门店管理员仅看本门店备份。 */
    public List<Map<String, Object>> listBackups() {
        List<Map<String, Object>> result = new ArrayList<>();
        File dir = io.ensureDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json.gz"));
        if (files == null) return result;

        boolean isSuperAdmin = SecurityContext.isSuperAdmin();
        Long currentStore = SecurityContext.currentStoreId();

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) {
            Map<String, Object> meta = io.readMeta(file);
            if (meta == null) continue;
            String type = (String) meta.getOrDefault("type", "full");
            Long storeId = exporter.toLong(meta.get("storeId"));

            // 门店管理员只能看到自己门店的备份
            if (!isSuperAdmin && !"store".equals(type)) continue;
            if (!isSuperAdmin && storeId != null && currentStore != null
                    && !storeId.equals(currentStore)) {
                continue;
            }

            Map<String, Object> backup = new LinkedHashMap<>();
            backup.put("name", file.getName());
            backup.put("size", file.length());
            backup.put("sizeText", io.formatFileSize(file.length()));
            backup.put("lastModified", file.lastModified());
            backup.put("lastModifiedText", io.formatDisplayTime(file.lastModified()));
            backup.put("type", type);
            backup.put("storeId", storeId);
            backup.put("storeName", meta.get("storeName"));
            backup.put("formatVersion", meta.get("version"));
            backup.put("tableCount", meta.get("tableCount"));
            backup.put("totalRows", meta.get("totalRows"));
            result.add(backup);
        }
        return result;
    }

    /* ============================================================
     * 创建备份
     * ============================================================ */

    /**
     * 创建备份。
     * 超管:type=full 时全库备份;type=store 且指定 storeId 时备份该门店。
     * 门店管理员:仅能备份本门店(type 自动为 store,storeId 取当前门店)。
     */
    public Map<String, Object> createBackup(String type, Long storeId) {
        boolean isSuperAdmin = SecurityContext.isSuperAdmin();
        Long currentStore = SecurityContext.currentStoreId();
        String operator = SecurityContext.currentAdminId() == null ? "system"
                : ("admin#" + SecurityContext.currentAdminId());

        String actualType;
        Long actualStoreId;
        String storeName = null;

        if ("full".equalsIgnoreCase(type)) {
            if (!isSuperAdmin) {
                throw new SecurityException("仅超级管理员可执行全库备份");
            }
            actualType = "full";
            actualStoreId = null;
        } else {
            // 门店备份
            actualType = "store";
            // 门店管理员:若未传 storeId 则默认本门店;若传了则校验是否为本门店(防越权)
            // 超管:必须显式指定 storeId
            if (isSuperAdmin) {
                actualStoreId = storeId;
            } else {
                actualStoreId = storeId != null ? storeId : currentStore;
            }
            if (actualStoreId == null) {
                throw new BusinessException("未指定门店,无法备份");
            }
            // 非超管只能备份自己的门店(传了别人的 storeId 会被拒绝)
            if (!isSuperAdmin) {
                SecurityContext.checkStoreAccess(actualStoreId);
            }
            storeName = exporter.getStoreName(actualStoreId);
        }

        // 导出数据
        Map<String, List<Map<String, Object>>> data = exporter.exportData(actualType, actualStoreId);

        // 构建备份文档
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", BackupConstants.FORMAT_VERSION);
        document.put("type", actualType);
        document.put("storeId", actualStoreId);
        document.put("storeName", storeName);
        document.put("createdAt", LocalDateTime.now().toString());
        document.put("createdBy", operator);
        int totalRows = 0;
        List<String> tableNames = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : data.entrySet()) {
            tableNames.add(e.getKey());
            totalRows += e.getValue().size();
        }
        document.put("tableNames", tableNames);
        document.put("tableCount", tableNames.size());
        document.put("totalRows", totalRows);
        document.put("data", data);

        // 写文件
        String prefix = "full".equals(actualType) ? "full" : "store" + actualStoreId;
        String fileName = io.generateFileName(prefix);
        File outputFile = io.writeDocument(document, fileName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", fileName);
        result.put("size", outputFile.length());
        result.put("sizeText", io.formatFileSize(outputFile.length()));
        result.put("type", actualType);
        result.put("storeId", actualStoreId);
        result.put("storeName", storeName);
        result.put("tableCount", tableNames.size());
        result.put("totalRows", totalRows);
        return result;
    }

    /* ============================================================
     * 恢复(委托给 RestoreService,通过 Spring 代理让 @Transactional 生效)
     * ============================================================ */

    /**
     * 恢复备份(委托给 RestoreService)。
     * 保留此 public 方法以兼容 BackupController 的调用;
     * 实际恢复逻辑与 @Transactional 事务边界在 RestoreService.restoreBackup 中,
     * 此处通过 restoreService 引用调用,确保经过 Spring AOP 代理,事务注解生效。
     */
    public Map<String, Object> restoreBackup(String backupName) {
        return restoreService.restoreBackup(backupName);
    }

    /* ============================================================
     * 删除 / 下载 / 导入
     * ============================================================ */

    public void deleteBackup(String backupName) {
        File file = io.resolveSafeFile(backupName);
        if (!file.exists()) {
            throw new BusinessException("备份文件不存在");
        }
        // 权限:门店管理员只能删除自己门店的备份
        checkStoreBackupAccess(file, "删除");
        if (!file.delete()) {
            throw new BusinessException("删除备份文件失败");
        }
    }

    /** 下载备份:返回真实文件流。调用方需做权限校验。 */
    public File getBackupFile(String backupName) {
        File file = io.resolveSafeFile(backupName);
        if (!file.exists()) {
            throw new BusinessException("备份文件不存在");
        }
        // 门店管理员只能下载自己门店的备份
        checkStoreBackupAccess(file, "下载");
        return file;
    }

    /**
     * 导入(上传)备份文件:保存到备份目录,可选立即恢复。
     * 文件名会被重命名为规范格式,避免路径穿越。
     */
    public Map<String, Object> importBackup(InputStream inputStream, String originalFilename, boolean restore) {
        if (originalFilename == null || !originalFilename.endsWith(".json.gz")) {
            throw new BusinessException("仅支持 .json.gz 备份文件");
        }
        // 解析文档校验合法性(readDocument 内部会做 GZIP 解压,失败抛 BusinessException)
        byte[] content;
        try {
            content = inputStream.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败: " + e.getMessage());
        }
        Map<String, Object> document = io.readDocument(new java.io.ByteArrayInputStream(content));
        if (!BackupConstants.FORMAT_VERSION.equals(String.valueOf(document.get("version")))) {
            throw new BusinessException("备份格式版本不兼容: " + document.get("version"));
        }

        String type = String.valueOf(document.getOrDefault("type", "full"));
        Long docStoreId = exporter.toLong(document.get("storeId"));

        // 权限校验:门店管理员只能导入本门店备份
        boolean isSuperAdmin = SecurityContext.isSuperAdmin();
        Long currentStore = SecurityContext.currentStoreId();
        if (!isSuperAdmin) {
            if (!"store".equals(type)) {
                throw new SecurityException("门店管理员不能导入全库备份");
            }
            if (docStoreId == null || currentStore == null || !docStoreId.equals(currentStore)) {
                throw new SecurityException("只能导入本门店的备份");
            }
        }

        // 生成文件名并保存
        String prefix = "full".equals(type) ? "full" : "store" + docStoreId;
        String fileName = io.generateFileName(prefix + "_import");
        File outputFile = new File(io.ensureDir(), fileName);
        try (OutputStream os = new FileOutputStream(outputFile)) {
            os.write(content);
        } catch (IOException e) {
            throw new BusinessException("保存备份文件失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", fileName);
        result.put("size", outputFile.length());
        result.put("type", type);
        result.put("storeId", docStoreId);
        result.put("imported", true);

        if (restore) {
            // 立即恢复:通过 restoreService 调用,经 Spring 代理让 @Transactional 生效
            // (修复原 this.restoreBackup() 自调用导致事务失效的 bug)
            result.put("restored", true);
            result.putAll(restoreService.restoreBackup(fileName));
        }
        return result;
    }

    /* ============================================================
     * 暴露给 RestoreService / BackupSchedulerService 的便捷方法
     * (避免外部直接依赖 BackupExporter / BackupIO,降低耦合)
     * ============================================================ */

    /** 供 RestoreService / BackupSchedulerService 复用:导出数据。 */
    public Map<String, List<Map<String, Object>>> exportData(String type, Long storeId) {
        return exporter.exportData(type, storeId);
    }

    /** 供 RestoreService 复用:加载备份文档。 */
    public Map<String, Object> loadBackupDocument(String backupName) {
        return io.loadDocument(backupName);
    }

    /** 供 RestoreService / BackupSchedulerService 复用:写 GZIP 文档。 */
    public File writeBackupDocument(Map<String, Object> document, String fileName) {
        return io.writeDocument(document, fileName);
    }

    /** 供 RestoreService / BackupSchedulerService 复用:生成文件名。 */
    public String generateBackupFileName(String prefix) {
        return io.generateFileName(prefix);
    }

    /** 供 RestoreService / BackupSchedulerService 复用:确保备份目录存在。 */
    public File ensureBackupDir() {
        return io.ensureDir();
    }

    /** 供 RestoreService 复用:引用表名。 */
    public String quoteTable(String table) {
        return exporter.quoteTable(table);
    }

    /** 供 RestoreService 复用:引用列名。 */
    public String quoteColumn(String column) {
        return exporter.quoteColumn(column);
    }

    /** 供 RestoreService 复用:Jackson 反序列化值类型转换。 */
    public Object convertValue(Object v) {
        return exporter.convertValue(v);
    }

    /** 供 RestoreService / RestoreService 复用:安全转 Long。 */
    public Long toLong(Object o) {
        return exporter.toLong(o);
    }

    /** 供 BackupSchedulerService 复用:查询门店名。 */
    public String getStoreName(Long storeId) {
        return exporter.getStoreName(storeId);
    }

    /* ============================================================
     * 私有辅助:门店备份访问权限校验
     * ============================================================ */

    /**
     * 门店备份访问权限校验(消除 deleteBackup / getBackupFile 两处重复)。
     * 超管直接放行;非超管仅可操作本门店的 store 类型备份。
     */
    private void checkStoreBackupAccess(File file, String action) {
        boolean isSuperAdmin = SecurityContext.isSuperAdmin();
        if (!isSuperAdmin) {
            Map<String, Object> meta = io.readMeta(file);
            if (meta == null || !"store".equals(meta.get("type"))) {
                throw new SecurityException("无权" + action + "该备份");
            }
            Long docStoreId = exporter.toLong(meta.get("storeId"));
            Long currentStore = SecurityContext.currentStoreId();
            if (docStoreId == null || currentStore == null || !docStoreId.equals(currentStore)) {
                throw new SecurityException("无权" + action + "该备份");
            }
        }
    }
}
