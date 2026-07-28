package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时备份调度服务。
 *
 * 从原 BackupService 拆分,负责:
 * - 每分钟检查 sys_config 中的 backup_auto_enabled / backup_cron / backup_keep_copies
 * - cron 匹配时以系统身份执行全库自动备份(跳过权限校验)
 * - 按 backup_keep_copies 清理最旧的 full_auto_ 前缀自动备份
 *
 * 调度状态(cronExpression / currentCron / nextRun)在本类内部维护,
 * cron 表达式变化时自动重新解析并重算下次执行时间。
 */
@Service
public class BackupSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(BackupSchedulerService.class);

    private final BackupService backupService;
    private final SystemConfigService systemConfigService;

    private volatile CronExpression cronExpression;
    private volatile String currentCron;
    private volatile LocalDateTime nextRun;

    public BackupSchedulerService(BackupService backupService, SystemConfigService systemConfigService) {
        this.backupService = backupService;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 每分钟检查是否到达定时备份时间。
     * 读取 sys_config 中的 backup_auto_enabled / backup_cron / backup_keep_copies。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void scheduledBackup() {
        try {
            boolean enabled = systemConfigService.getBoolConfig("backup_auto_enabled", true);
            if (!enabled) return;

            String cron = systemConfigService.getStrConfig("backup_cron", "0 0 2 * * ?");
            if (cron == null || cron.isBlank()) return;

            // cron 变化时重新解析
            if (cronExpression == null || !cron.equals(currentCron)) {
                try {
                    cronExpression = CronExpression.parse(cron);
                    currentCron = cron;
                    nextRun = cronExpression.next(LocalDateTime.now());
                } catch (IllegalArgumentException e) {
                    // 无效 cron 表达式,跳过
                    return;
                }
            }

            LocalDateTime now = LocalDateTime.now();
            if (nextRun != null && !now.isBefore(nextRun)) {
                doScheduledFullBackup();
                nextRun = cronExpression.next(now);
            }
        } catch (Exception e) {
            // 定时任务异常不应中断调度
            log.warn("定时备份异常: {}", e.getMessage());
        }
    }

    /** 执行一次全库定时备份,并清理超出保留份数的旧备份。 */
    private void doScheduledFullBackup() {
        // 以系统身份执行,跳过权限校验
        Map<String, List<Map<String, Object>>> data = backupService.exportData("full", null);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", BackupConstants.FORMAT_VERSION);
        document.put("type", "full");
        document.put("storeId", null);
        document.put("storeName", null);
        document.put("createdAt", LocalDateTime.now().toString());
        document.put("createdBy", "scheduler");
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

        String fileName = backupService.generateBackupFileName("full_auto");
        try {
            backupService.writeBackupDocument(document, fileName);
        } catch (BusinessException e) {
            log.warn("定时备份写入失败: {}", e.getMessage());
            return;
        }

        // 清理超出保留份数的旧全库备份(仅清理 auto_ 前缀的全库备份)
        cleanupOldBackups();
    }

    /** 按 backup_keep_copies 清理最旧的自动备份。 */
    private void cleanupOldBackups() {
        int keepCopies = systemConfigService.getIntConfig("backup_keep_copies", 30);
        if (keepCopies <= 0) return;
        File dir = backupService.ensureBackupDir();
        File[] autoBackups = dir.listFiles((d, name) ->
                name.startsWith("full_auto_") && name.endsWith(".json.gz"));
        if (autoBackups == null || autoBackups.length <= keepCopies) return;

        // 按修改时间正序(最旧在前)
        Arrays.sort(autoBackups, Comparator.comparingLong(File::lastModified));
        int toDelete = autoBackups.length - keepCopies;
        for (int i = 0; i < toDelete; i++) {
            if (!autoBackups[i].delete()) {
                log.warn("清理旧备份失败: {}", autoBackups[i].getName());
            }
        }
    }
}
