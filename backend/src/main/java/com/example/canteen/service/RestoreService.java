package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 备份恢复服务。
 *
 * 从原 BackupService 拆分,专门负责数据恢复相关逻辑:
 * - restoreBackup:在事务内执行"删除目标范围数据 + 按备份插入 + 行数校验"
 * - createPreRestoreSnapshot:恢复前自动快照(便于回滚)
 * - verifyRestoredData:恢复后只读校验,行数不一致则抛异常回滚事务
 * - deleteAllBusinessData / deleteStoreData / insertRows:删除与插入的具体实现
 * - evictCache:恢复成功后清理 Redis 缓存(dish/menu),避免前端读到旧数据
 *
 * 关键修复:原 BackupService.importBackup 通过 this.restoreBackup() 自调用,
 * Spring AOP 代理不生效,@Transactional 注解失效,恢复失败时已 DELETE 的数据无法回滚,
 * 导致数据丢失。现由独立的 RestoreService Bean 承载 @Transactional restoreBackup 方法,
 * BackupService.importBackup 改为注入 RestoreService 调用 restoreService.restoreBackup(),
 * 通过 Spring 代理让事务生效,失败自动回滚。
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    /** Redis 缓存 key 前缀(与 DishService/MenuService 保持一致) */
    private static final String DISH_CACHE_PREFIX = "dish:store:";
    private static final String MENU_CACHE_PREFIX = "menu:store:";

    private final JdbcTemplate jdbcTemplate;
    private final BackupService backupService;
    private final RedisTemplate<String, Object> redisTemplate;

    public RestoreService(JdbcTemplate jdbcTemplate, BackupService backupService,
                          RedisTemplate<String, Object> redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.backupService = backupService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 从备份文件恢复。在事务内执行:先删除目标范围数据,再按备份插入。
     * 超管可恢复任意备份;门店管理员仅可恢复本门店备份。
     *
     * 加固:
     * - 恢复前自动创建 pre_restore_ 快照(便于回滚)
     * - 恢复后做只读校验(关键表行数与备份声明一致)
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restoreBackup(String backupName) {
        // 通过 BackupService 加载并校验备份文件(复用文件名白名单 / 路径穿越防护 / GZIP 读取逻辑)
        Map<String, Object> document = backupService.loadBackupDocument(backupName);
        String type = (String) document.getOrDefault("type", "full");
        Long docStoreId = backupService.toLong(document.get("storeId"));

        boolean isSuperAdmin = SecurityContext.isSuperAdmin();
        Long currentStore = SecurityContext.currentStoreId();

        // 权限校验
        if ("full".equals(type)) {
            if (!isSuperAdmin) {
                throw new SecurityException("仅超级管理员可执行全库恢复");
            }
        } else {
            // 门店备份:门店管理员仅能恢复自己的;超管可恢复任意门店
            if (!isSuperAdmin) {
                if (docStoreId == null || currentStore == null || !docStoreId.equals(currentStore)) {
                    throw new SecurityException("只能恢复本门店的备份");
                }
            }
        }

        // 恢复前自动快照(失败不影响恢复流程)
        String snapshotName = null;
        try {
            snapshotName = createPreRestoreSnapshot(type, docStoreId);
        } catch (Exception e) {
            log.warn("恢复前快照失败(忽略): {}", e.getMessage());
        }

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> data =
                (Map<String, List<Map<String, Object>>>) document.getOrDefault("data", new LinkedHashMap<>());

        // 先删除目标范围数据
        if ("full".equals(type)) {
            deleteAllBusinessData();
        } else if (docStoreId != null) {
            deleteStoreData(docStoreId);
        }

        // 按顺序插入
        int restoredRows = 0;
        int redactedAdminsSkipped = 0;
        List<String> restoredTables = new ArrayList<>();
        for (String table : BackupConstants.TABLES_IN_ORDER) {
            List<Map<String, Object>> rows = data.get(table);
            if (rows == null || rows.isEmpty()) continue;
            // P0-5 门店备份恢复时跳过 store 表插入(store 记录不删除,直接插入会主键冲突)
            if (!"full".equals(type) && "store".equals(table)) continue;
            int[] counts = insertRows(table, rows);
            restoredRows += counts[0];
            redactedAdminsSkipped += counts[1];
            restoredTables.add(table);
        }

        // 恢复后只读校验:关键表行数应与备份声明一致(不一致则抛异常回滚事务)
        verifyRestoredData(document, data, redactedAdminsSkipped);

        // 恢复成功后清理 Redis 缓存(dish/menu),避免前端读到旧数据
        // 全库恢复清理所有门店缓存;门店恢复只清理该门店缓存
        evictCache("full".equals(type) ? null : docStoreId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backupName", backupName);
        result.put("type", type);
        result.put("storeId", docStoreId);
        result.put("storeName", document.get("storeName"));
        result.put("restoredTables", restoredTables);
        result.put("restoredRows", restoredRows);
        result.put("redactedAdminsSkipped", redactedAdminsSkipped);
        result.put("preRestoreSnapshot", snapshotName);
        return result;
    }

    /**
     * 创建恢复前快照(便于回滚)。
     * 命名:pre_restore_<type><storeId>_<yyyyMMdd_HHmmss>.json.gz
     */
    private String createPreRestoreSnapshot(String type, Long storeId) {
        Map<String, List<Map<String, Object>>> snapData = backupService.exportData(type, storeId);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", BackupConstants.FORMAT_VERSION);
        doc.put("type", type);
        doc.put("storeId", storeId);
        doc.put("storeName", backupService.getStoreName(storeId));
        doc.put("createdAt", LocalDateTime.now().toString());
        doc.put("createdBy", "pre-restore");
        int total = 0;
        List<String> tables = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : snapData.entrySet()) {
            tables.add(e.getKey());
            total += e.getValue().size();
        }
        doc.put("tableNames", tables);
        doc.put("tableCount", tables.size());
        doc.put("totalRows", total);
        doc.put("data", snapData);

        String prefix = "full".equals(type) ? "pre_restore_full"
                : "pre_restore_store" + storeId;
        String fileName = backupService.generateBackupFileName(prefix);
        backupService.writeBackupDocument(doc, fileName);
        return fileName;
    }

    /**
     * 恢复后只读校验:对每个表执行 COUNT(*),应与备份声明行数一致。
     * 不一致则抛异常,触发事务回滚。
     */
    private void verifyRestoredData(Map<String, Object> document,
                                    Map<String, List<Map<String, Object>>> data,
                                    int redactedAdminsSkipped) {
        String type = (String) document.getOrDefault("type", "full");
        for (Map.Entry<String, List<Map<String, Object>>> e : data.entrySet()) {
            String table = e.getKey();
            int expected = e.getValue().size();
            // 脱敏 admin 行被跳过,admin 表预期行数相应减少
            if ("admin".equals(table)) {
                expected -= redactedAdminsSkipped;
            }
            // P0-5 门店备份恢复时跳过 store 表校验(store 记录不删除,全表总数不匹配)
            if (!"full".equals(type) && "store".equals(table)) continue;
            int actual;
            try {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + backupService.quoteTable(table), Integer.class);
                actual = count == null ? 0 : count;
            } catch (Exception ex) {
                throw new BusinessException("恢复后校验失败:无法查询表 " + table + " - " + ex.getMessage());
            }
            if (actual != expected) {
                throw new BusinessException(String.format(
                        "恢复后校验失败:表 %s 预期 %d 行,实际 %d 行(事务已回滚)", table, expected, actual));
            }
        }
    }

    /** 删除全库业务数据(按子表在前顺序)。 */
    private void deleteAllBusinessData() {
        for (String table : BackupConstants.DELETE_ORDER) {
            jdbcTemplate.update("DELETE FROM " + backupService.quoteTable(table));
        }
    }

    /** 删除指定门店的业务数据(含级联子表)。 */
    private void deleteStoreData(Long storeId) {
        // 子表先删
        jdbcTemplate.update("DELETE oi FROM order_item oi INNER JOIN `order` o ON oi.order_id = o.id WHERE o.store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM `order` WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM recharge_record WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE mi FROM menu_item mi INNER JOIN menu m ON mi.menu_id = m.id WHERE m.store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM menu WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM notification WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM dining_time_slot WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM employee WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM dish_category WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM dish WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM department WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM admin WHERE store_id = ?", storeId);
        // store 表本身不删(门店记录保留)
    }

    /**
     * 批量插入行。动态构建 INSERT,保留备份中的原始值(含 id)。
     * 返回 [插入行数, 因敏感脱敏跳过的 admin 行数]。
     */
    private int[] insertRows(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return new int[]{0, 0};
        // 取列名(以第一行为准)
        Map<String, Object> first = rows.get(0);
        List<String> columns = new ArrayList<>(first.keySet());

        // P0-3 防提权:恢复 admin 表时,门店管理员(role>=2)不能创建比自己角色更高的账号(如超管 role=1)
        Integer callerRole = SecurityContext.currentRole();
        boolean clampAdminRole = "admin".equals(table) && callerRole != null && callerRole >= 2;

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(backupService.quoteTable(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(backupService.quoteColumn(columns.get(i)));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
        }
        sql.append(")");

        List<Object[]> batchArgs = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            // 脱敏 admin 行跳过:password 为占位符时说明该账号密码未随备份导出,
            // 直接写入会得到无效密码导致无法登录。跳过该行,由部署脚本(INIT_ADMIN_*)或
            // 后续管理员重置密码重建,避免恢复出不可登录账号。
            if ("admin".equals(table)
                    && BackupConstants.REDACTED_PLACEHOLDER.equals(row.get("password"))) {
                skipped++;
                continue;
            }
            // P0-3 对 admin 表的 role 字段做 clamp:不低于调用者角色(防注入超管)
            if (clampAdminRole && row.containsKey("role")) {
                Object roleObj = row.get("role");
                Integer rowRole = null;
                if (roleObj instanceof Number) {
                    rowRole = ((Number) roleObj).intValue();
                } else if (roleObj != null) {
                    try { rowRole = Integer.parseInt(roleObj.toString()); } catch (Exception ignore) {}
                }
                if (rowRole != null && rowRole < callerRole) {
                    row.put("role", callerRole); // clamp to caller's role
                }
            }
            Object[] args = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                args[i] = backupService.convertValue(row.get(columns.get(i)));
            }
            batchArgs.add(args);
        }
        int inserted = 0;
        if (!batchArgs.isEmpty()) {
            int[] counts = jdbcTemplate.batchUpdate(sql.toString(), batchArgs);
            for (int c : counts) inserted += c;
        }
        return new int[]{inserted, skipped};
    }

    /**
     * 清理 Redis 缓存。恢复后调用,避免前端读到旧数据。
     * @param storeId 门店 ID;null 表示全库恢复,清理所有门店缓存
     *
     * 清理范围:
     * - dish:store:{storeId}:*  (菜品列表/全量)
     * - menu:store:{storeId}:*  (菜单按日/按月)
     *
     * 使用 SCAN 而非 KEYS,避免阻塞 Redis(SCAN 游标式扫描,单次返回 count=100)
     */
    private void evictCache(Long storeId) {
        try {
            if (storeId != null) {
                // 门店级清理:删除该门店的 dish/menu 缓存
                String dishPattern = DISH_CACHE_PREFIX + storeId + ":*";
                String menuPattern = MENU_CACHE_PREFIX + storeId + ":*";
                deleteByPattern(dishPattern);
                deleteByPattern(menuPattern);
                log.info("恢复后清理门店 {} 的 Redis 缓存", storeId);
            } else {
                // 全库清理:删除所有门店的 dish/menu 缓存
                deleteByPattern(DISH_CACHE_PREFIX + "*");
                deleteByPattern(MENU_CACHE_PREFIX + "*");
                log.info("恢复后清理所有门店的 Redis 缓存");
            }
        } catch (Exception e) {
            // 缓存清理失败不影响恢复结果(缓存有 TTL,会自然过期)
            log.warn("恢复后缓存清理失败(忽略,缓存会自然过期): {}", e.getMessage());
        }
    }

    /** SCAN 游标式删除匹配 pattern 的 key,避免 KEYS 阻塞 Redis */
    private void deleteByPattern(String pattern) {
        try {
            // 使用 SCAN 替代 KEYS,避免阻塞 Redis 主线程
            Set<String> keys = new java.util.HashSet<>();
            org.springframework.data.redis.core.ScanOptions options =
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern)
                            .count(100)
                            .build();
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor =
                         redisTemplate.getConnectionFactory()
                                 .getConnection()
                                 .scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next()));
                }
            }
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("删除 {} 个缓存 key: {}", keys.size(), pattern);
            }
        } catch (Exception e) {
            log.debug("删除缓存 key 失败(pattern={}): {}", pattern, e.getMessage());
        }
    }
}
