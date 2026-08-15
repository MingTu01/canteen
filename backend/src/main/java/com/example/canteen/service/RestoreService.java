package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * - restoreBackup:加载备份 → 恢复前快照 → 逐表恢复(每表独立事务)
 * - restoreSingleTable:单表恢复的独立事务单元(删除目标范围数据 + 按备份插入 + 行数校验)
 * - createPreRestoreSnapshot:恢复前自动快照(拆分事务后的回滚兜底)
 * - insertRows / deleteTableData:删除与插入的具体实现
 * - evictCache:恢复成功后清理 Redis 缓存(dish/menu),避免前端读到旧数据
 *
 * 事务设计(拆分大事务):
 * - 原实现为单个大事务内 DELETE+INSERT 全部 20+ 张表,长事务持锁时间长;
 *   现改为每张表一个独立事务(同表的 DELETE+INSERT+校验在同一事务内),
 *   任一表失败立即停止后续表,已成功表不回滚——恢复前快照就是全量兜底。
 * - 单表事务必须经 Spring 代理调用(直接 this.xxx() 自调用会绕过 AOP 代理,
 *   @Transactional 失效),参考 BackupService 注入 RestoreService 经代理调用的先例,
 *   此处通过 ObjectProvider<RestoreService> 注入自身代理。
 *
 * 快照策略:恢复前快照失败必须中止恢复(抛 BusinessException),
 * 不允许静默继续——拆分事务后快照是唯一的全量回滚手段。
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
    /** 自身代理:单表恢复的 @Transactional 需经 Spring 代理调用才生效 */
    private final ObjectProvider<RestoreService> selfProvider;

    public RestoreService(JdbcTemplate jdbcTemplate, BackupService backupService,
                          RedisTemplate<String, Object> redisTemplate,
                          ObjectProvider<RestoreService> selfProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.backupService = backupService;
        this.redisTemplate = redisTemplate;
        this.selfProvider = selfProvider;
    }

    /**
     * 从备份文件恢复。
     * 超管可恢复任意备份;门店管理员仅可恢复本门店备份。
     *
     * 流程(拆分大事务,不再整体 @Transactional):
     * 1. 加载并校验备份文件(复用文件名白名单 / 路径穿越防护)
     * 2. 恢复前自动创建 pre_restore_ 快照——失败立即中止恢复
     * 3. 按 TABLES_IN_ORDER 逐表恢复,每表独立事务(DELETE+INSERT 同事务);
     *    任一表失败立即停止后续表,记录失败表与原因
     * 4. 清理 Redis 缓存(部分恢复同样需要,避免读到旧数据)
     */
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

        // 恢复前自动快照:拆分事务后快照是唯一的全量回滚兜底,失败必须中止恢复
        String snapshotName;
        try {
            snapshotName = createPreRestoreSnapshot(type, docStoreId);
        } catch (Exception e) {
            log.error("恢复前快照失败,中止恢复: {}", e.getMessage(), e);
            throw new BusinessException("恢复前快照失败,已中止恢复: " + e.getMessage());
        }

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> data =
                (Map<String, List<Map<String, Object>>>) document.getOrDefault("data", new LinkedHashMap<>());

        // 逐表恢复:每张表一个独立事务,失败即停,不再全量回滚(快照兜底)
        RestoreService proxy = self();
        Map<String, Object> tableResults = new LinkedHashMap<>();
        List<String> restoredTables = new ArrayList<>();
        int restoredRows = 0;
        String failedTable = null;
        String failureReason = null;
        for (String table : BackupConstants.TABLES_IN_ORDER) {
            List<Map<String, Object>> rows = data.get(table);
            try {
                int inserted = proxy.restoreSingleTable(table, rows, type, docStoreId);
                if (rows != null && !rows.isEmpty()) {
                    restoredTables.add(table);
                    restoredRows += inserted;
                }
                tableResults.put(table, "成功:插入 " + inserted + " 行");
            } catch (Exception ex) {
                // 本表事务已在 restoreSingleTable 内回滚;停止后续表,返回已完成清单与失败原因
                failedTable = table;
                failureReason = ex.getMessage();
                tableResults.put(table, "失败:" + failureReason + "(本表事务已回滚)");
                log.error("恢复表 {} 失败,停止恢复后续表: {}", table, failureReason, ex);
                break;
            }
        }
        if (failedTable == null) {
            log.info("备份 {} 恢复完成:共 {} 张表有数据,合计 {} 行", backupName, restoredTables.size(), restoredRows);
        } else {
            log.warn("备份 {} 恢复中断:表 {} 失败({}),已完成 {} 张表;可用快照 {} 兜底回滚",
                    backupName, failedTable, failureReason, restoredTables.size(), snapshotName);
        }

        // 恢复后清理 Redis 缓存(dish/menu),避免前端读到旧数据
        // 全库恢复清理所有门店缓存;门店恢复只清理该门店缓存
        evictCache("full".equals(type) ? null : docStoreId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backupName", backupName);
        result.put("type", type);
        result.put("storeId", docStoreId);
        result.put("storeName", document.get("storeName"));
        result.put("restoredTables", restoredTables);
        result.put("restoredRows", restoredRows);
        result.put("preRestoreSnapshot", snapshotName);
        // 新增字段(原 key 全保留):逐表恢复状态与失败信息,供前端展示拆分事务后的部分恢复结果
        result.put("tableResults", tableResults);
        result.put("failedTable", failedTable);
        result.put("failureReason", failureReason);
        return result;
    }

    /** 获取自身 Spring 代理;代理不可用时回退 this(仅出现在无容器的单元测试场景,事务注解不生效) */
    private RestoreService self() {
        if (selfProvider != null) {
            RestoreService proxy = selfProvider.getIfAvailable();
            if (proxy != null) {
                return proxy;
            }
        }
        return this;
    }

    /**
     * 单表恢复(独立事务单元):删除该表目标范围数据 + 按备份插入 + 全库行数校验。
     * 必须经 Spring 代理调用(见 self()),否则 @Transactional 不生效。
     * 任一步失败抛异常 → 本表事务回滚,由调用方记录并停止后续表。
     *
     * @return 实际插入行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int restoreSingleTable(String table, List<Map<String, Object>> rows,
                                  String type, Long storeId) {
        // 1. 删除该表现有数据(全库:整表清空;门店:按门店范围,子表经关联父表过滤)
        deleteTableData(table, type, storeId);
        // 2. 按备份插入(保留备份原始值,含 id)
        int inserted = (rows == null || rows.isEmpty()) ? 0 : insertRows(table, rows);
        // 3. 全库恢复:行数校验(同事务内可见本表未提交插入)
        //    门店恢复跳过 COUNT 校验:多门店场景下全表总数不等于该门店备份行数,
        //    插入失败会抛异常触发本表回滚。
        if ("full".equals(type) && rows != null && !rows.isEmpty()) {
            Integer count;
            try {
                count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + backupService.quoteTable(table), Integer.class);
            } catch (Exception ex) {
                throw new BusinessException("恢复表 " + table + " 校验失败:无法查询表 - " + ex.getMessage());
            }
            int actual = count == null ? 0 : count;
            if (actual != rows.size()) {
                throw new BusinessException(String.format(
                        "恢复表 %s 校验失败:预期 %d 行,实际 %d 行(本表事务已回滚)", table, rows.size(), actual));
            }
        }
        return inserted;
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
     * 删除单表现有数据。
     * 全库恢复:整表清空;门店恢复:按门店范围删除(无 store_id 列的子表经关联父表过滤,
     * store 表按主键删,与原 deleteStoreData 的语句保持一致)。
     */
    private void deleteTableData(String table, String type, Long storeId) {
        if ("full".equals(type)) {
            jdbcTemplate.update("DELETE FROM " + backupService.quoteTable(table));
            return;
        }
        switch (table) {
            case "order_item" -> jdbcTemplate.update(
                    "DELETE oi FROM order_item oi INNER JOIN `order` o ON oi.order_id = o.id WHERE o.store_id = ?", storeId);
            case "menu_item" -> jdbcTemplate.update(
                    "DELETE mi FROM menu_item mi INNER JOIN menu m ON mi.menu_id = m.id WHERE m.store_id = ?", storeId);
            case "purchase_item" -> jdbcTemplate.update(
                    "DELETE pi FROM purchase_item pi INNER JOIN purchase p ON pi.purchase_id = p.id WHERE p.store_id = ?", storeId);
            case "group_order_item" -> jdbcTemplate.update(
                    "DELETE goi FROM group_order_item goi INNER JOIN group_order go ON goi.group_order_id = go.id WHERE go.store_id = ?", storeId);
            case "store" -> jdbcTemplate.update("DELETE FROM store WHERE id = ?", storeId);
            default -> jdbcTemplate.update(
                    "DELETE FROM " + backupService.quoteTable(table) + " WHERE store_id = ?", storeId);
        }
    }

    /**
     * 批量插入行。动态构建 INSERT,保留备份中的原始值(含 id)。
     * 返回插入行数。
     */
    private int insertRows(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return 0;
        // 取列名(以第一行为准)
        Map<String, Object> first = rows.get(0);
        List<String> columns = new ArrayList<>(first.keySet());

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
        for (Map<String, Object> row : rows) {
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
        return inserted;
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
