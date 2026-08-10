package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统版本信息接口。
 *
 * 设计:以 classpath:version.json 为单一事实来源,
 * 启动时读取并缓存;运行时 sys_config.system_version 可覆盖展示。
 */
@RestController
@RequestMapping("/api/system")
public class VersionController {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;
    private final JsonNode versionInfo;

    public VersionController(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.versionInfo = loadVersionInfo();
    }

    private JsonNode loadVersionInfo() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("version.json")) {
            if (is == null) {
                return new ObjectMapper().createObjectNode()
                        .put("version", "unknown")
                        .put("buildTime", "unknown")
                        .put("description", "version.json missing");
            }
            return new ObjectMapper().readTree(is);
        } catch (Exception e) {
            return new ObjectMapper().createObjectNode().put("version", "error");
        }
    }

    @GetMapping("/version")
    public ApiResponse<Map<String, Object>> getSystemVersion() {
        Map<String, Object> result = new HashMap<>();
        // 从 version.json 读取(单一事实来源)
        result.put("version", versionInfo.path("version").asText("unknown"));
        result.put("buildTime", versionInfo.path("buildTime").asText("unknown"));
        result.put("description", versionInfo.path("description").asText(""));
        // 如果 version.json 包含 changes 字段,则一并返回
        if (versionInfo.has("changes")) {
            result.put("changes", versionInfo.get("changes"));
        }

        // 优先使用 sys_config.system_version 覆盖(允许运维不重新打包升级版本号)
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT config_value FROM sys_config WHERE config_key = ?", "system_version");
            if (!rows.isEmpty()) {
                Object v = rows.get(0).get("config_value");
                if (v != null && !v.toString().isBlank()) {
                    result.put("version", v.toString());
                }
            }
        } catch (Exception ignore) {
            // sys_config 读取失败用 version.json 的版本号
        }

        // 只返回迁移数量,不返回迁移详情(含描述等可能敏感信息)
        int migrationsCount = 0;
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
            if (cnt != null) {
                migrationsCount = cnt;
            }
        } catch (Exception ignore) {
            // 表不存在或查询失败时保持 0
        }
        result.put("migrationsCount", migrationsCount);

        return ApiResponse.success(result);
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> healthCheck() {
        Map<String, Object> result = new HashMap<>();
        result.put("timestamp", new Date());
        result.put("version", versionInfo.path("version").asText("unknown"));

        // 检查数据库连接
        boolean dbUp;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbUp = true;
            result.put("database", "UP");
        } catch (Exception e) {
            // 不泄漏数据库错误详情,仅标记 DOWN
            dbUp = false;
            result.put("database", "DOWN");
        }

        // 检查 Redis 连接
        boolean redisUp = false;
        try (org.springframework.data.redis.connection.RedisConnection conn = redisConnectionFactory.getConnection()) {
            conn.ping();
            redisUp = true;
            result.put("redis", "UP");
        } catch (Exception e) {
            // 不泄漏 Redis 错误详情,仅标记 DOWN
            result.put("redis", "DOWN");
        }

        // 综合状态:DB 和 Redis 都 UP 时才为 UP,否则为 DOWN
        boolean allUp = dbUp && redisUp;
        result.put("status", allUp ? "UP" : "DOWN");

        // JVM 内存
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryUsagePercent = maxMemory > 0 ? (usedMemory * 100.0 / maxMemory) : 0;
        result.put("jvmMaxMemory", maxMemory);
        result.put("jvmUsedMemory", usedMemory);
        result.put("jvmFreeMemory", freeMemory);
        result.put("jvmMemoryUsagePercent", Math.round(memoryUsagePercent * 100) / 100.0);

        // 系统内存(通过 OperatingSystemMXBean)
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
                double cpuUsage = sunOsBean.getSystemCpuLoad() * 100;
                double processCpuUsage = sunOsBean.getProcessCpuLoad() * 100;
                long totalPhysicalMemory = sunOsBean.getTotalPhysicalMemorySize();
                long freePhysicalMemory = sunOsBean.getFreePhysicalMemorySize();
                long usedPhysicalMemory = totalPhysicalMemory - freePhysicalMemory;
                double systemMemoryUsagePercent = totalPhysicalMemory > 0 ? (usedPhysicalMemory * 100.0 / totalPhysicalMemory) : 0;
                result.put("cpuUsagePercent", Math.round(cpuUsage * 100) / 100.0);
                result.put("processCpuUsagePercent", Math.round(processCpuUsage * 100) / 100.0);
                result.put("systemTotalMemory", totalPhysicalMemory);
                result.put("systemUsedMemory", usedPhysicalMemory);
                result.put("systemMemoryUsagePercent", Math.round(systemMemoryUsagePercent * 100) / 100.0);
                result.put("availableProcessors", sunOsBean.getAvailableProcessors());
            }
        } catch (Exception e) {
            // 忽略系统指标获取失败
        }

        // 磁盘占用(当前工作目录所在磁盘)
        try {
            java.io.File disk = new java.io.File(".");
            long diskTotal = disk.getTotalSpace();
            long diskFree = disk.getUsableSpace();
            long diskUsed = diskTotal - diskFree;
            double diskUsagePercent = diskTotal > 0 ? (diskUsed * 100.0 / diskTotal) : 0;
            result.put("diskTotal", diskTotal);
            result.put("diskUsed", diskUsed);
            result.put("diskFree", diskFree);
            result.put("diskUsagePercent", Math.round(diskUsagePercent * 100) / 100.0);
        } catch (Exception e) {
            // 忽略磁盘信息获取失败
        }

        // DB 或 Redis 任一 DOWN 时返回 503,以便 Docker healthcheck / 负载均衡器正确摘除节点
        HttpStatus httpStatus = allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(ApiResponse.success(result));
    }

    /**
     * 服务器时间接口(白名单,免登录)。
     * 所有前端时间相关校验统一调用此接口,避免本机时间篡改绕过限制。
     * 返回:
     * - timestamp: 毫秒时间戳
     * - date: yyyy-MM-dd(Asia/Shanghai)
     * - time: HH:mm(Asia/Shanghai)
     * - minutes: 当前小时*60+分钟(便于过点不订判断)
     * - datetime: ISO LocalDateTime
     */
    @GetMapping("/time")
    public ApiResponse<Map<String, Object>> serverTime() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        Map<String, Object> result = new HashMap<>();
        result.put("timestamp", now.toInstant().toEpochMilli());
        result.put("date", now.toLocalDate().toString());
        result.put("time", String.format("%02d:%02d", now.getHour(), now.getMinute()));
        result.put("minutes", now.getHour() * 60 + now.getMinute());
        result.put("datetime", now.toLocalDateTime().toString());
        return ApiResponse.success(result);
    }

    /** 列出全部系统配置(仅超管可读) */
    @GetMapping("/config")
    public ApiResponse<List<Map<String, Object>>> listConfig() {
        com.example.canteen.security.SecurityContext.checkSuperAdmin("仅超级管理员可查看系统配置");
        try {
            List<Map<String, Object>> configs = jdbcTemplate.queryForList(
                "SELECT config_key, config_value, description FROM sys_config ORDER BY config_key");
            return ApiResponse.success(configs);
        } catch (Exception e) {
            return ApiResponse.success(Collections.emptyList());
        }
    }

    /**
     * 公开接口(免登录):返回订餐配置,供 H5/terminal 前端读取截止时间等规则。
     * 只返回订餐相关的 5 个 key,不暴露其他敏感配置。
     * 支持按门店读取:传 storeId 时优先读 store_config,未传或读不到回退 sys_config 全局配置。
     */
    @GetMapping("/order-config")
    public ApiResponse<Map<String, Object>> getOrderConfig(@RequestParam(required = false) Long storeId) {
        Map<String, Object> result = new HashMap<>();
        // 订餐配置的 5 个 key
        String[] orderKeys = {
            "order_advance_days", "order_deadline_time", "cancel_deadline_time",
            "max_order_quantity", "allow_cross_day_order"
        };
        try {
            for (String key : orderKeys) {
                String value = null;
                // 1. 门店级配置(若传了 storeId)
                if (storeId != null) {
                    List<Map<String, Object>> storeRows = jdbcTemplate.queryForList(
                        "SELECT config_value FROM store_config WHERE store_id = ? AND config_key = ?",
                        storeId, key);
                    if (!storeRows.isEmpty()) {
                        Object v = storeRows.get(0).get("config_value");
                        if (v != null && !String.valueOf(v).isBlank()) {
                            value = String.valueOf(v);
                        }
                    }
                }
                // 2. 全局配置(sys_config)
                if (value == null) {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT config_value FROM sys_config WHERE config_key = ?", key);
                    if (!rows.isEmpty()) {
                        Object v = rows.get(0).get("config_value");
                        if (v != null && !String.valueOf(v).isBlank()) {
                            value = v.toString();
                        }
                    }
                }
                // 3. 默认值
                if (value == null) {
                    value = switch (key) {
                        case "order_advance_days" -> "7";
                        case "order_deadline_time", "cancel_deadline_time" -> "15:00";
                        case "max_order_quantity" -> "10";
                        case "allow_cross_day_order" -> "true";
                        default -> "";
                    };
                }
                result.put(key, value);
            }
        } catch (Exception e) {
            // DB 异常时返回默认值
            result.put("order_advance_days", "7");
            result.put("order_deadline_time", "15:00");
            result.put("cancel_deadline_time", "15:00");
            result.put("max_order_quantity", "10");
            result.put("allow_cross_day_order", "true");
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/config/{key}")
    public ApiResponse<Map<String, Object>> getConfig(@org.springframework.web.bind.annotation.PathVariable String key) {
        com.example.canteen.security.SecurityContext.checkSuperAdmin("仅超级管理员可查看系统配置");
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT config_key, config_value, description FROM sys_config WHERE config_key = ?", key);
            if (list.isEmpty()) {
                return ApiResponse.error(404, "配置项不存在");
            }
            return ApiResponse.success(list.get(0));
        } catch (Exception e) {
            return ApiResponse.error(500, "查询配置失败");
        }
    }

    /**
     * 新增或更新配置(UPSERT)。仅超管可调用。
     * Body: { "value": "...", "description": "..."(可选) }
     */
    @OperationLog(value = "更新系统配置", detail = "'配置项 ' + #key + ' 值 ' + #body['value']")
    @org.springframework.web.bind.annotation.PutMapping("/config/{key}")
    public ApiResponse<Void> updateConfig(@org.springframework.web.bind.annotation.PathVariable String key,
                                          @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        if (!com.example.canteen.security.SecurityContext.isSuperAdmin()) {
            throw new com.example.canteen.exception.SecurityException("仅超级管理员可修改系统配置");
        }
        String value = body.get("value") == null ? null : body.get("value").toString();
        String description = body.get("description") == null ? null : body.get("description").toString();
        try {
            int rows = jdbcTemplate.update(
                "UPDATE sys_config SET config_value = ?, description = COALESCE(?, description) WHERE config_key = ?",
                value, description, key);
            if (rows == 0) {
                jdbcTemplate.update(
                    "INSERT INTO sys_config (config_key, config_value, description) VALUES (?, ?, ?)",
                    key, value, description);
            }
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(500, "更新配置失败");
        }
    }

    /** 批量保存配置。Body: [{key,value}, ...]。仅超管可调用。 */
    @OperationLog(value = "批量更新系统配置", detail = "'配置项数量 ' + #items.size()")
    @org.springframework.web.bind.annotation.PutMapping("/config")
    public ApiResponse<Void> batchUpdateConfig(@org.springframework.web.bind.annotation.RequestBody List<Map<String, Object>> items) {
        if (!com.example.canteen.security.SecurityContext.isSuperAdmin()) {
            throw new com.example.canteen.exception.SecurityException("仅超级管理员可修改系统配置");
        }
        try {
            for (Map<String, Object> item : items) {
                Object k = item.get("key");
                Object v = item.get("value");
                if (k == null) continue;
                String key = k.toString();
                String value = v == null ? "" : v.toString();
                int rows = jdbcTemplate.update(
                    "UPDATE sys_config SET config_value = ? WHERE config_key = ?",
                    value, key);
                if (rows == 0) {
                    jdbcTemplate.update(
                        "INSERT INTO sys_config (config_key, config_value, description) VALUES (?, ?, ?)",
                        key, value, item.get("description"));
                }
            }
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(500, "批量更新配置失败");
        }
    }
}
