package com.example.canteen.service;

import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 备份相关常量集中管理。
 *
 * 包括:
 * - 备份目录(BACKUP_DIR):优先环境变量,回退 ./backups
 * - 文件名白名单正则(BACKUP_NAME_PATTERN):防路径穿越
 * - 备份格式版本(FORMAT_VERSION):用于导入时校验兼容性
 * - 业务表清单与删除顺序:供 BackupExporter / RestoreService / BackupSchedulerService 共用
 * - 时间戳格式(线程安全 DateTimeFormatter)
 */
public final class BackupConstants {

    private BackupConstants() {}

    /** 备份目录:优先使用环境变量,回退到 /app/backup(容器)或 ./backups(本地) */
    public static final String BACKUP_DIR =
            System.getenv().getOrDefault("BACKUP_DIR",
                    System.getenv().getOrDefault("APP_BACKUP_DIR",
                            Paths.get(System.getProperty("user.dir"), "backups").toString()));

    /** 备份文件名白名单:字母数字下划线连字符 + .json.gz */
    public static final Pattern BACKUP_NAME_PATTERN = Pattern.compile("^[\\w\\-]+\\.json\\.gz$");

    /** 备份格式版本 */
    public static final String FORMAT_VERSION = "2.0";

    /** 导出时敏感字段(password/wx_openid/wx_unionid)的脱敏占位符。
     *  恢复时据此识别"已脱敏"的 admin 行并跳过,避免把坏密码写回数据库。 */
    public static final String REDACTED_PLACEHOLDER = "***REDACTED***";

    /** 备份包含的业务表(按依赖顺序:父表在前)。
     *  admin 表不参与备份/恢复:密码脱敏后恢复会导致账号丢失,
     *  管理员账号由 canteen.sh「重置管理员密码」或后台「账号管理」维护。
     *  sys_operation_log 不备份:操作日志属于审计流水,不随业务数据恢复。 */
    public static final List<String> TABLES_IN_ORDER = List.of(
            "store", "department", "supplier", "material",
            "dish", "dish_category", "employee", "dining_time_slot",
            "menu", "menu_item", "notification", "feedback",
            "order", "order_item", "recharge_record",
            "group_order", "group_order_item",
            "purchase", "purchase_item",
            "stock_count", "daily_close", "daily_settlement"
    );

    /** 带 store_id 列的表(menu_item/order_item/purchase_item/group_order_item 通过关联表过滤) */
    public static final Set<String> STORE_DIRECT_TABLES = Set.of(
            "store", "department", "supplier", "material",
            "dish", "dish_category", "employee", "dining_time_slot",
            "menu", "notification", "feedback",
            "order", "recharge_record",
            "group_order", "purchase",
            "stock_count", "daily_close", "daily_settlement"
    );

    /** 删除顺序(子表在前,避免引用残留)。
     *  不含 admin:恢复时不删除管理员账号。 */
    public static final List<String> DELETE_ORDER = List.of(
            "order_item", "order", "recharge_record",
            "menu_item", "menu",
            "group_order_item", "group_order",
            "purchase_item", "purchase",
            "stock_count", "daily_close", "daily_settlement",
            "notification", "feedback", "dining_time_slot",
            "employee", "dish_category", "dish",
            "department", "supplier", "material",
            "store"
    );

    /** 备份文件名时间戳格式(线程安全,替代 SimpleDateFormat) */
    public static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** 列表展示用时间戳格式 */
    public static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}
