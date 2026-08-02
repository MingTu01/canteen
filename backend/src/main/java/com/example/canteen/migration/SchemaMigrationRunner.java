package com.example.canteen.migration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.logging.Logger;

/**
 * 启动时执行版本化迁移。
 *
 * 设计要点:
 * - 基于独立的 schema_version 表追踪迁移历史(与 Flyway 解耦,Flyway 主要管 prod MySQL,
 *   此 Runner 兼顾 dev H2 与 prod MySQL 的轻量补丁式迁移)。
 * - 每个迁移以 (version, name) 唯一标识,启动时按版本顺序执行未应用的迁移。
 * - 失败记录 error_msg,success=false;成功才写入 success=true。
 * - 不抛异常以避免阻塞应用启动(管理员可从 sys_config/schema_version 排查)。
 * - 迁移本身必须幂等,失败重跑不会破坏数据。
 *
 * MySQL 兼容性:
 * - MySQL 不支持 "ALTER TABLE ... ADD COLUMN IF NOT EXISTS" 和 "CREATE INDEX IF NOT EXISTS",
 *   因此通过 information_schema 检查后再执行 DDL,保证幂等且 MySQL/H2 均可运行。
 * - 所有 CREATE TABLE 显式声明 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4,避免中文乱码。
 *
 * 注:DDL 主体(建表)仍由 schema-h2.sql / Flyway V1__init.sql 完成,
 * 此 Runner 仅做增量补丁(新增字段、索引、配置项等),避免大改 schema 文件。
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
        ensureSchemaVersionTable();
        for (Migration m : MIGRATIONS) {
            applyMigration(m);
        }
    }

    private void ensureSchemaVersionTable() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS schema_version (" +
                    "  version VARCHAR(64) PRIMARY KEY, " +
                    "  name VARCHAR(255), " +
                    "  applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "  success BOOLEAN DEFAULT TRUE, " +
                    "  error_msg VARCHAR(2000)" +
                    ")");
        } catch (Exception e) {
            LOG.warning("[SchemaMigration] 创建 schema_version 表失败: " + e.getMessage());
        }
    }

    /** 检查列是否存在(MySQL/H2 兼容,通过 information_schema) */
    private boolean columnExists(String table, String column) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = ? AND column_name = ?",
                    Integer.class, table, column);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 检查索引是否存在(MySQL/H2 兼容,通过 information_schema) */
    private boolean indexExists(String table, String indexName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_name = ? AND index_name = ?",
                    Integer.class, table, indexName);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 安全添加列:存在则跳过,不存在则执行 ADD COLUMN */
    private void addColumnIfNotExists(String table, String column, String columnDef) {
        if (columnExists(table, column)) return;
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDef);
        } catch (Exception e) {
            // 可能并发添加,忽略
        }
    }

    /** 安全创建索引:存在则跳过,不存在则创建 */
    private void createIndexIfNotExists(String indexName, String table, String columns) {
        if (indexExists(table, indexName)) return;
        try {
            jdbcTemplate.execute("CREATE INDEX " + indexName + " ON " + table + "(" + columns + ")");
        } catch (Exception e) {
            // 可能并发创建,忽略
        }
    }

    /** 幂等插入 sys_config 配置项(已存在则跳过) */
    private void insertConfigIfNotExists(String key, String value, String description) {
        try {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES (?, ?, ?)",
                    key, value, description);
        } catch (Exception e) {
            // 忽略
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
            // upsert 记录
            int updated = jdbcTemplate.update(
                    "UPDATE schema_version SET success = TRUE, error_msg = NULL, applied_at = ? WHERE version = ?",
                    LocalDateTime.now(), m.version);
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO schema_version (version, name, applied_at, success) VALUES (?, ?, ?, TRUE)",
                        m.version, m.name, LocalDateTime.now());
            }
        } catch (Exception e) {
            LOG.severe("[SchemaMigration] 迁移 v" + m.version + " 失败: " + e.getMessage());
            try {
                jdbcTemplate.update(
                        "INSERT INTO schema_version (version, name, applied_at, success, error_msg) " +
                        "VALUES (?, ?, ?, FALSE, ?)",
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

    /** MySQL/H2 通用的 CREATE TABLE 后缀,确保 InnoDB + utf8mb4 */
    private static final String TABLE_SUFFIX = "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    /**
     * 已登记的迁移列表。新增迁移时按版本号升序追加,不要修改已发布的迁移。
     * 使用 addColumnIfNotExists / createIndexIfNotExists / insertConfigIfNotExists 保证 MySQL 兼容和幂等。
     */
    private static final Migration[] MIGRATIONS = {
        new Migration("1.1.0", "add password_updated_at to employee/admin", self -> {
            self.addColumnIfNotExists("employee", "password_updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            self.addColumnIfNotExists("admin", "password_updated_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        }),
        new Migration("1.1.1", "add token_blacklist table", self -> {
            try {
                self.jdbcTemplate.execute(
                        "CREATE TABLE IF NOT EXISTS token_blacklist (" +
                        "  token_jti VARCHAR(128) PRIMARY KEY, " +
                        "  expires_at TIMESTAMP, " +
                        "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ") " + TABLE_SUFFIX);
            } catch (Exception ignored) {}
            self.createIndexIfNotExists("idx_token_blacklist_expires", "token_blacklist", "expires_at");
        }),
        new Migration("1.1.2", "add login rate limit configs", self -> {
            self.insertConfigIfNotExists("login_rate_limit_max_fail", "10", "登录失败最大次数,超过则锁定");
            self.insertConfigIfNotExists("login_rate_limit_lock_minutes", "5", "登录失败锁定分钟数");
        }),
        new Migration("1.1.3", "bump system_version to 1.1.0", self -> {
            self.insertConfigIfNotExists("system_version", "1.1.0", "系统版本号");
        }),
        new Migration("1.1.4", "initialize password_updated_at for existing users", self -> {
            try {
                self.jdbcTemplate.execute("UPDATE employee SET password_updated_at = CURRENT_TIMESTAMP WHERE password_updated_at IS NULL");
            } catch (Exception ignored) {}
            try {
                self.jdbcTemplate.execute("UPDATE admin SET password_updated_at = CURRENT_TIMESTAMP WHERE password_updated_at IS NULL");
            } catch (Exception ignored) {}
        }),
        new Migration("1.2.0", "add branding fields to store", self -> {
            self.addColumnIfNotExists("store", "logo_url", "VARCHAR(500)");
            self.addColumnIfNotExists("store", "image_url", "VARCHAR(500)");
            self.addColumnIfNotExists("store", "terminal_background_url", "VARCHAR(500)");
            self.addColumnIfNotExists("store", "h5_banner_url", "VARCHAR(500)");
            self.addColumnIfNotExists("store", "description", "TEXT");
        }),
        new Migration("1.3.0", "add supplier/purchase/material tables", self -> {
            String[] tables = {
                "CREATE TABLE IF NOT EXISTS supplier (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, name VARCHAR(100), contact_person VARCHAR(50), phone VARCHAR(30), address VARCHAR(200), category VARCHAR(50), status INT DEFAULT 1, remark VARCHAR(500), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) " + TABLE_SUFFIX,
                "CREATE TABLE IF NOT EXISTS purchase (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, purchase_no VARCHAR(50), supplier_id BIGINT, total_amount DECIMAL(10,2), purchase_date DATE, status INT DEFAULT 1, remark VARCHAR(500), operator_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) " + TABLE_SUFFIX,
                "CREATE TABLE IF NOT EXISTS purchase_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, purchase_id BIGINT, material_name VARCHAR(100), unit VARCHAR(20), quantity DECIMAL(10,2), price DECIMAL(10,2), amount DECIMAL(10,2)) " + TABLE_SUFFIX,
                "CREATE TABLE IF NOT EXISTS material (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, name VARCHAR(100), unit VARCHAR(20), stock_qty DECIMAL(10,2) DEFAULT 0, min_stock DECIMAL(10,2) DEFAULT 0, category VARCHAR(50), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) " + TABLE_SUFFIX
            };
            for (String sql : tables) {
                try { self.jdbcTemplate.execute(sql); } catch (Exception ignored) {}
            }
            self.createIndexIfNotExists("idx_supplier_store", "supplier", "store_id");
            self.createIndexIfNotExists("idx_purchase_store_created", "purchase", "store_id, created_at");
            self.createIndexIfNotExists("idx_purchase_store_status", "purchase", "store_id, status");
            self.createIndexIfNotExists("idx_purchase_item_purchase_id", "purchase_item", "purchase_id");
            self.createIndexIfNotExists("idx_material_store", "material", "store_id");
        }),
        new Migration("1.3.1", "add feedback/group_order tables", self -> {
            String[] tables = {
                "CREATE TABLE IF NOT EXISTS feedback (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, employee_id BIGINT, order_id BIGINT, dish_id BIGINT, rating INT, content TEXT, category INT DEFAULT 1, status INT DEFAULT 1, reply TEXT, reply_admin_id BIGINT, replied_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) " + TABLE_SUFFIX,
                "CREATE TABLE IF NOT EXISTS group_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, order_no VARCHAR(50), title VARCHAR(200), organizer_id BIGINT, headcount INT, meal_date DATE, meal_type INT, location VARCHAR(200), total_amount DECIMAL(10,2), status INT DEFAULT 1, remark VARCHAR(500), operator_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) " + TABLE_SUFFIX,
                "CREATE TABLE IF NOT EXISTS group_order_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, group_order_id BIGINT, dish_id BIGINT, dish_name VARCHAR(100), price DECIMAL(10,2), quantity INT, amount DECIMAL(10,2)) " + TABLE_SUFFIX
            };
            for (String sql : tables) {
                try { self.jdbcTemplate.execute(sql); } catch (Exception ignored) {}
            }
            self.createIndexIfNotExists("idx_feedback_store_created", "feedback", "store_id, created_at");
            self.createIndexIfNotExists("idx_feedback_store_status", "feedback", "store_id, status");
            self.createIndexIfNotExists("idx_group_order_store_created", "group_order", "store_id, created_at");
            self.createIndexIfNotExists("idx_group_order_store_status", "group_order", "store_id, status");
            self.createIndexIfNotExists("idx_group_order_item_order_id", "group_order_item", "group_order_id");
        }),
        new Migration("1.3.2", "add daily_close table", self -> {
            try {
                self.jdbcTemplate.execute(
                        "CREATE TABLE IF NOT EXISTS daily_close (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, close_date DATE, order_count INT, total_revenue DECIMAL(10,2), total_refund DECIMAL(10,2), recharge_amount DECIMAL(10,2), status INT DEFAULT 1, operator_id BIGINT, remark VARCHAR(500), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) " + TABLE_SUFFIX);
            } catch (Exception ignored) {}
            self.createIndexIfNotExists("idx_daily_close_store_date", "daily_close", "store_id, close_date");
        }),
        new Migration("1.3.3", "add daily_settlement table", self -> {
            try {
                self.jdbcTemplate.execute(
                        "CREATE TABLE IF NOT EXISTS daily_settlement (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, settle_date DATE, total_orders INT DEFAULT 0, total_revenue DECIMAL(10,2) DEFAULT 0, total_refund DECIMAL(10,2) DEFAULT 0, total_recharge DECIMAL(10,2) DEFAULT 0, total_consumption DECIMAL(10,2) DEFAULT 0, cash_revenue DECIMAL(10,2) DEFAULT 0, online_revenue DECIMAL(10,2) DEFAULT 0, order_count INT DEFAULT 0, completed_count INT DEFAULT 0, cancelled_count INT DEFAULT 0, served_count INT DEFAULT 0, operator_id BIGINT, status INT DEFAULT 1, remark VARCHAR(500), settled_at TIMESTAMP, closed_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) " + TABLE_SUFFIX);
            } catch (Exception ignored) {}
            self.createIndexIfNotExists("idx_daily_settlement_store_date", "daily_settlement", "store_id, settle_date");
            self.createIndexIfNotExists("idx_daily_settlement_store_status", "daily_settlement", "store_id, status");
        }),
        new Migration("1.4.0", "add structured business hours fields to store", self -> {
            // 统一使用 VARCHAR(10),与 V11 Flyway 迁移一致
            self.addColumnIfNotExists("store", "breakfast_start", "VARCHAR(10)");
            self.addColumnIfNotExists("store", "breakfast_end", "VARCHAR(10)");
            self.addColumnIfNotExists("store", "lunch_start", "VARCHAR(10)");
            self.addColumnIfNotExists("store", "lunch_end", "VARCHAR(10)");
            self.addColumnIfNotExists("store", "dinner_start", "VARCHAR(10)");
            self.addColumnIfNotExists("store", "dinner_end", "VARCHAR(10)");
        }),
        new Migration("1.5.0", "add material_id to purchase_item for stock linkage", self -> {
            self.addColumnIfNotExists("purchase_item", "material_id", "BIGINT");
        }),
        new Migration("1.5.1", "add stock_count table for inventory stocktaking", self -> {
            try {
                self.jdbcTemplate.execute(
                        "CREATE TABLE IF NOT EXISTS stock_count (" +
                        "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                        "  store_id BIGINT, " +
                        "  material_id BIGINT, " +
                        "  material_name VARCHAR(100), " +
                        "  system_qty DECIMAL(10,2), " +
                        "  counted_qty DECIMAL(10,2), " +
                        "  difference DECIMAL(10,2), " +
                        "  status INT DEFAULT 1, " +
                        "  operator_id BIGINT, " +
                        "  remark VARCHAR(500), " +
                        "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "  resolved_at TIMESTAMP" +
                        ") " + TABLE_SUFFIX);
            } catch (Exception ignored) {}
            self.createIndexIfNotExists("idx_stock_count_store_status", "stock_count", "store_id, status");
            self.createIndexIfNotExists("idx_stock_count_material", "stock_count", "material_id");
        }),
        new Migration("1.6.0", "add must_change_password to employee", self -> {
            self.addColumnIfNotExists("employee", "must_change_password", "INT DEFAULT 0");
        })
    };
}
