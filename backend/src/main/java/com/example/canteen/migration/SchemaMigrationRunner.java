package com.example.canteen.migration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    private void applyMigration(Migration m) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schema_version WHERE version = ? AND success = TRUE",
                    Integer.class, m.version);
            if (count != null && count > 0) {
                return; // 已成功应用
            }
            LOG.info("[SchemaMigration] 应用迁移 v" + m.version + " - " + m.name);
            for (String sql : m.sqls) {
                try {
                    jdbcTemplate.execute(sql);
                } catch (Exception ignored) {
                    // 单条 SQL 失败可能是字段/索引已存在,继续下一条
                }
            }
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

    /** 迁移定义:版本号、名称、SQL 列表 */
    private record Migration(String version, String name, String[] sqls) {}

    /**
     * 已登记的迁移列表。新增迁移时按版本号升序追加,不要修改已发布的迁移。
     */
    private static final Migration[] MIGRATIONS = {
        new Migration("1.1.0", "add password_updated_at to employee/admin", new String[] {
            "ALTER TABLE employee ADD COLUMN IF NOT EXISTS password_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
            "ALTER TABLE admin ADD COLUMN IF NOT EXISTS password_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
        }),
        new Migration("1.1.1", "add token_blacklist table", new String[] {
            "CREATE TABLE IF NOT EXISTS token_blacklist (" +
            "  token_jti VARCHAR(128) PRIMARY KEY, " +
            "  expires_at TIMESTAMP, " +
            "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            ")",
            "CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires ON token_blacklist(expires_at)"
        }),
        new Migration("1.1.2", "add login rate limit configs", new String[] {
            "INSERT INTO sys_config (config_key, config_value, description) " +
            "VALUES ('login_rate_limit_max_fail', '10', '登录失败最大次数,超过则锁定')",
            "INSERT INTO sys_config (config_key, config_value, description) " +
            "VALUES ('login_rate_limit_lock_minutes', '5', '登录失败锁定分钟数')"
        }),
        new Migration("1.1.3", "bump system_version to 1.1.0", new String[] {
            "INSERT INTO sys_config (config_key, config_value, description) " +
            "VALUES ('system_version', '1.1.0', '系统版本号')"
        }),
        new Migration("1.1.4", "initialize password_updated_at for existing users", new String[] {
            "UPDATE employee SET password_updated_at = CURRENT_TIMESTAMP WHERE password_updated_at IS NULL",
            "UPDATE admin SET password_updated_at = CURRENT_TIMESTAMP WHERE password_updated_at IS NULL"
        }),
        new Migration("1.2.0", "add branding fields to store", new String[] {
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS logo_url VARCHAR(500)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS image_url VARCHAR(500)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS terminal_background_url VARCHAR(500)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS h5_banner_url VARCHAR(500)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS description TEXT"
        }),
        new Migration("1.3.0", "add supplier/purchase/material tables", new String[] {
            "CREATE TABLE IF NOT EXISTS supplier (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, name VARCHAR(100), contact_person VARCHAR(50), phone VARCHAR(30), address VARCHAR(200), category VARCHAR(50), status INT DEFAULT 1, remark VARCHAR(500), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE IF NOT EXISTS purchase (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, purchase_no VARCHAR(50), supplier_id BIGINT, total_amount DECIMAL(10,2), purchase_date DATE, status INT DEFAULT 1, remark VARCHAR(500), operator_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE IF NOT EXISTS purchase_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, purchase_id BIGINT, material_name VARCHAR(100), unit VARCHAR(20), quantity DECIMAL(10,2), price DECIMAL(10,2), amount DECIMAL(10,2))",
            "CREATE TABLE IF NOT EXISTS material (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, name VARCHAR(100), unit VARCHAR(20), stock_qty DECIMAL(10,2) DEFAULT 0, min_stock DECIMAL(10,2) DEFAULT 0, category VARCHAR(50), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_supplier_store ON supplier(store_id)",
            "CREATE INDEX IF NOT EXISTS idx_purchase_store_created ON purchase(store_id, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_purchase_store_status ON purchase(store_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_purchase_item_purchase_id ON purchase_item(purchase_id)",
            "CREATE INDEX IF NOT EXISTS idx_material_store ON material(store_id)"
        }),
        new Migration("1.3.1", "add feedback/group_order tables", new String[] {
            "CREATE TABLE IF NOT EXISTS feedback (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, employee_id BIGINT, order_id BIGINT, dish_id BIGINT, rating INT, content TEXT, category INT DEFAULT 1, status INT DEFAULT 1, reply TEXT, reply_admin_id BIGINT, replied_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE IF NOT EXISTS group_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, order_no VARCHAR(50), title VARCHAR(200), organizer_id BIGINT, headcount INT, meal_date DATE, meal_type INT, location VARCHAR(200), total_amount DECIMAL(10,2), status INT DEFAULT 1, remark VARCHAR(500), operator_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE IF NOT EXISTS group_order_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, group_order_id BIGINT, dish_id BIGINT, dish_name VARCHAR(100), price DECIMAL(10,2), quantity INT, amount DECIMAL(10,2))",
            "CREATE INDEX IF NOT EXISTS idx_feedback_store_created ON feedback(store_id, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_feedback_store_status ON feedback(store_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_group_order_store_created ON group_order(store_id, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_group_order_store_status ON group_order(store_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_group_order_item_order_id ON group_order_item(group_order_id)"
        }),
        new Migration("1.3.2", "add daily_close table", new String[] {
            "CREATE TABLE IF NOT EXISTS daily_close (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, close_date DATE, order_count INT, total_revenue DECIMAL(10,2), total_refund DECIMAL(10,2), recharge_amount DECIMAL(10,2), status INT DEFAULT 1, operator_id BIGINT, remark VARCHAR(500), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_daily_close_store_date ON daily_close(store_id, close_date)"
        }),
        new Migration("1.3.3", "add daily_settlement table", new String[] {
            "CREATE TABLE IF NOT EXISTS daily_settlement (id BIGINT AUTO_INCREMENT PRIMARY KEY, store_id BIGINT, settle_date DATE, total_orders INT DEFAULT 0, total_revenue DECIMAL(10,2) DEFAULT 0, total_refund DECIMAL(10,2) DEFAULT 0, total_recharge DECIMAL(10,2) DEFAULT 0, total_consumption DECIMAL(10,2) DEFAULT 0, cash_revenue DECIMAL(10,2) DEFAULT 0, online_revenue DECIMAL(10,2) DEFAULT 0, order_count INT DEFAULT 0, completed_count INT DEFAULT 0, cancelled_count INT DEFAULT 0, served_count INT DEFAULT 0, operator_id BIGINT, status INT DEFAULT 1, remark VARCHAR(500), settled_at TIMESTAMP, closed_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_daily_settlement_store_date ON daily_settlement(store_id, settle_date)",
            "CREATE INDEX IF NOT EXISTS idx_daily_settlement_store_status ON daily_settlement(store_id, status)"
        }),
        new Migration("1.4.0", "add structured business hours fields to store", new String[] {
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS breakfast_start VARCHAR(8)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS breakfast_end VARCHAR(8)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS lunch_start VARCHAR(8)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS lunch_end VARCHAR(8)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS dinner_start VARCHAR(8)",
            "ALTER TABLE store ADD COLUMN IF NOT EXISTS dinner_end VARCHAR(8)"
        })
    };
}
