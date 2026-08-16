package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店级订餐配置 Controller。
 * 把原全局 sys_config 中的订餐配置(order_deadline_time 等)下沉到每个门店独立配置。
 *
 * 读顺序:store_config(按门店) → sys_config(全局) → 代码默认值
 * 写目标:store_config(按门店)
 *
 * 权限:门店管理员(含)以上角色可读写本门店配置,超管可读写任意门店配置。
 */
@RestController
@RequestMapping("/api/store-config")
public class StoreConfigController {

    private final JdbcTemplate jdbcTemplate;

    /** 订餐相关 key 白名单(只允许这些 key 走门店配置) */
    private static final String[] ORDER_CONFIG_KEYS = {
        "order_advance_days", "order_deadline_time", "cancel_deadline_time",
        "max_order_quantity", "allow_cross_day_order",
        "unsolicited_fee_enabled", "unsolicited_fee_breakfast",
        "unsolicited_fee_lunch", "unsolicited_fee_dinner"
    };

    /** key → 默认值 */
    private static final Map<String, String> DEFAULTS = new HashMap<>();
    static {
        DEFAULTS.put("order_advance_days", "7");
        DEFAULTS.put("order_deadline_time", "15:00");
        DEFAULTS.put("cancel_deadline_time", "15:00");
        DEFAULTS.put("max_order_quantity", "10");
        DEFAULTS.put("allow_cross_day_order", "true");
        DEFAULTS.put("unsolicited_fee_enabled", "false");
        DEFAULTS.put("unsolicited_fee_breakfast", "0");
        DEFAULTS.put("unsolicited_fee_lunch", "0");
        DEFAULTS.put("unsolicited_fee_dinner", "0");
    }

    public StoreConfigController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取指定门店的订餐配置(本门店管理员及以上可读)。
     * GET /api/store-config/order?storeId=1
     * 兼容旧路径:GET /api/system/order-config?storeId=1
     */
    @GetMapping("/order")
    public ApiResponse<Map<String, Object>> getOrderConfig(@RequestParam(required = false) Long storeId) {
        // 公开端点(H5/terminal 免登录),但 storeId 必传
        // 若未传 storeId,回退到全局配置(兼容旧调用)
        Map<String, Object> result = new HashMap<>();
        for (String key : ORDER_CONFIG_KEYS) {
            result.put(key, readConfig(storeId, key));
        }
        return ApiResponse.success(result);
    }

    /**
     * 批量保存指定门店的订餐配置(本门店管理员及以上可写)。
     * PUT /api/store-config/order?storeId=1
     * body:[{key,value},...]
     */
    @OperationLog(value = "更新门店订餐配置", detail = "'门店 ' + #resolver.storeName(#storeId) + ' 配置项数 ' + #body.size()")
    @PutMapping("/order")
    public ApiResponse<Void> updateOrderConfig(@RequestParam Long storeId,
                                               @RequestBody List<Map<String, Object>> body) {
        if (storeId == null) {
            throw new BusinessException("缺少 storeId");
        }
        // 权限校验:本门店可写
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException("无权修改门店配置");
        }
        SecurityContext.checkStoreAccess(storeId);

        for (Map<String, Object> item : body) {
            Object k = item.get("key");
            Object v = item.get("value");
            if (k == null) continue;
            String key = k.toString();
            // 白名单校验
            if (!DEFAULTS.containsKey(key)) {
                throw new BusinessException("不支持的配置项: " + key);
            }
            String value = v == null ? "" : v.toString();
            upsertStoreConfig(storeId, key, value);
        }
        return ApiResponse.success(null);
    }

    // ===== 内部方法 =====

    /**
     * 读取配置值:store_config(按门店) → sys_config(全局) → 默认值
     */
    private String readConfig(Long storeId, String key) {
        // 1. 门店级配置
        if (storeId != null) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT config_value FROM store_config WHERE store_id = ? AND config_key = ?",
                    storeId, key);
                if (!rows.isEmpty()) {
                    Object v = rows.get(0).get("config_value");
                    if (v != null && !String.valueOf(v).isBlank()) {
                        return String.valueOf(v);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 2. 全局配置(sys_config)
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT config_value FROM sys_config WHERE config_key = ?", key);
            if (!rows.isEmpty()) {
                Object v = rows.get(0).get("config_value");
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            }
        } catch (Exception ignored) {
        }
        // 3. 默认值
        return DEFAULTS.getOrDefault(key, "");
    }

    /**
     * 写入门店级配置(INSERT ON DUPLICATE KEY UPDATE)
     */
    private void upsertStoreConfig(Long storeId, String key, String value) {
        // MySQL: INSERT ... ON DUPLICATE KEY UPDATE
        // H2: MERGE,但 MySQL 语法也兼容大部分场景。这里用先 UPDATE 后 INSERT 兼容两者
        int rows = jdbcTemplate.update(
            "UPDATE store_config SET config_value = ? WHERE store_id = ? AND config_key = ?",
            value, storeId, key);
        if (rows == 0) {
            jdbcTemplate.update(
                "INSERT INTO store_config (store_id, config_key, config_value) VALUES (?, ?, ?)",
                storeId, key, value);
        }
    }
}
