package com.example.canteen.migration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.logging.Logger;

/**
 * 启动时执行版本化迁移(仅 DML,DDL 已全部移交 Flyway)。
 *
 * 设计变更历史:
 * - 旧版:此 Runner 同时执行 DDL(CREATE TABLE/ALTER TABLE/CREATE INDEX)和 DML(INSERT 配置项)。
 *   问题:Runner 注入的是运行时数据源(canteen_app,仅 DML 权限),执行 DDL 时报 1142 权限错
 *   (被 Spring 误报为 bad SQL grammar),导致 supplier/purchase/material 等业务表无法创建。
 * - 新版:所有 DDL 迁移到 Flyway 脚本(V10/V11/V15/V16),由 root 用户执行。
 *   此 Runner 仅保留 DML(配置项插入)和向后兼容的幂等检查。
 *
 * 当前职责:
 * - 检查 schema_version 表是否存在(Flyway V10 创建),不存在则跳过(说明 Flyway 未执行)
 * - 执行轻量 DML 迁移(配置项插入,幂等)
 * - 所有 DDL 已移交 Flyway,此 Runner 不再执行任何 CREATE/ALTER/INDEX
 *
 * 注:schema_version 表由 Flyway V10 创建,此 Runner 仅读写其记录。
 */
@Component
public class SchemaMigrationRunner {

    private static final Logger LOG = Logger.getLogger(SchemaMigrationRunner.class.getName());

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(50)
    public void run() {
        // 不再自行创建 schema_version 表(DDL 已移交 Flyway V10)。
        // 仅检查表是否存在:不存在说明 Flyway 未正常执行,跳过所有迁移避免异常。
        if (!schemaVersionTableExists()) {
            LOG.warning("[SchemaMigration] schema_version 表不存在(Flyway V10 可能未执行),跳过所有运行时迁移");
            return;
        }
        for (Migration m : MIGRATIONS) {
            applyMigration(m);
        }
    }

    /** 检查 schema_version 表是否存在(Flyway V10 创建)。不执行 CREATE,避免 DDL 权限问题。 */
    private boolean schemaVersionTableExists() {
        try {
            jdbcTemplate.queryForObject("SELECT 1 FROM schema_version LIMIT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 幂等插入 sys_config 配置项(已存在则跳过)。仅 DML,无 DDL。 */
    private void insertConfigIfNotExists(String key, String value, String description) {
        try {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES (?, ?, ?)",
                    key, value, description);
        } catch (Exception e) {
            // 忽略,配置项可能已由 Flyway 插入
        }
    }

    private void applyMigration(Migration m) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schema_version WHERE version = ? AND success = TRUE",
                    Integer.class, m.version);
            if (count != null && count > 0) {
                return; // 已成功应用
            }
            LOG.info("[SchemaMigration] 应用迁移 v" + m.version + " - " + m.name);
            m.runner.run(this);
            // upsert 记录(用 ON DUPLICATE KEY UPDATE 避免并发主键冲突)
            jdbcTemplate.update(
                    "INSERT INTO schema_version (version, name, applied_at, success) VALUES (?, ?, ?, TRUE) " +
                    "ON DUPLICATE KEY UPDATE success = TRUE, error_msg = NULL, applied_at = VALUES(applied_at)",
                    m.version, m.name, LocalDateTime.now());
        } catch (Exception e) {
            LOG.severe("[SchemaMigration] 迁移 v" + m.version + " 失败: " + e.getMessage());
            try {
                jdbcTemplate.update(
                    "INSERT INTO schema_version (version, name, applied_at, success, error_msg) " +
                    "VALUES (?, ?, ?, FALSE, ?) " +
                    "ON DUPLICATE KEY UPDATE success = FALSE, error_msg = VALUES(error_msg), applied_at = VALUES(applied_at)",
                    m.version, m.name, LocalDateTime.now(), e.getMessage());
            } catch (Exception ignore) {}
        }
    }

    /** 迁移执行接口 */
    @FunctionalInterface
    private interface MigrationRunner {
        void run(SchemaMigrationRunner self) throws Exception;
    }

    /** 迁移定义:版本号、名称、执行逻辑 */
    private record Migration(String version, String name, MigrationRunner runner) {}

    /**
     * 已登记的迁移列表(仅 DML,DDL 已全部移交 Flyway)。
     *
     * 迁移归属说明:
     * - 1.1.0~1.1.4: 已由 Flyway V10 完成(password_updated_at/token_blacklist/配置项)
     * - 1.2.0: 已由 Flyway V11 完成(store 品牌字段)
     * - 1.3.0~1.3.3, 1.5.0, 1.5.1: 已由 Flyway V16 完成(业务表 DDL)
     * - 1.4.0: 已由 Flyway V11 完成(store 营业时间字段)
     * - 1.6.0: 已由 Flyway V15 完成(employee.must_change_password)
     *
     * 此处保留空数组以维持类结构与未来 DML 迁移能力。新增 DML 迁移时按版本号升序追加。
     */
    private static final Migration[] MIGRATIONS = {
        // 所有 DDL 迁移已移交 Flyway,当前无运行时 DML 迁移。
        // 如需新增仅 DML 的迁移(如配置项初始化),在此追加:
        // new Migration("x.y.z", "description", self -> {
        //     self.insertConfigIfNotExists("key", "value", "description");
        // }),
    };
}
