package com.example.canteen.service;

import com.example.canteen.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备份数据导出器。
 *
 * 职责:从数据库读取业务表数据,组装为 JSON 可序列化的 Map 结构。
 * 包括:
 * - 全库 / 门店级数据导出(exportData)
 * - 单表的门店过滤导出(exportStoreTable)
 * - SQL 工具方法:quoteTable(保留字)、quoteColumn(防注入)、convertValue / toLong(Jackson 反序列化值转换)
 * - 门店名查询(getStoreName)
 *
 * 从 BackupService 拆分,让 BackupService 退化为协调器。
 */
@Service
public class BackupExporter {

    private final JdbcTemplate jdbcTemplate;

    public BackupExporter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 导出数据。type=full 全库;type=store 按门店过滤。
     * 返回 LinkedHashMap 保留表顺序,便于 JSON 输出与导入时按依赖顺序插入。
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> exportData(String type, Long storeId) {
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        for (String table : BackupConstants.TABLES_IN_ORDER) {
            List<Map<String, Object>> rows;
            if ("full".equals(type)) {
                rows = jdbcTemplate.queryForList("SELECT * FROM " + quoteTable(table));
            } else {
                rows = exportStoreTable(table, storeId);
            }
            data.put(table, rows != null ? rows : new ArrayList<>());
        }
        return data;
    }

    /** 按门店导出单表数据。menu_item/order_item 通过关联表过滤。 */
    public List<Map<String, Object>> exportStoreTable(String table, Long storeId) {
        String sql;
        switch (table) {
            case "store":
                sql = "SELECT * FROM store WHERE id = ?";
                return jdbcTemplate.queryForList(sql, storeId);
            case "menu_item":
                sql = "SELECT mi.* FROM menu_item mi INNER JOIN menu m ON mi.menu_id = m.id WHERE m.store_id = ?";
                return jdbcTemplate.queryForList(sql, storeId);
            case "order_item":
                sql = "SELECT oi.* FROM order_item oi INNER JOIN `order` o ON oi.order_id = o.id WHERE o.store_id = ?";
                return jdbcTemplate.queryForList(sql, storeId);
            default:
                if (BackupConstants.STORE_DIRECT_TABLES.contains(table)) {
                    sql = "SELECT * FROM " + quoteTable(table) + " WHERE store_id = ?";
                    return jdbcTemplate.queryForList(sql, storeId);
                }
                return new ArrayList<>();
        }
    }

    /** 查询门店名称(用于备份元信息)。失败返回 null,不阻塞备份流程。 */
    public String getStoreName(Long storeId) {
        if (storeId == null) return null;
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                    "SELECT name FROM store WHERE id = ?", storeId);
            if (list.isEmpty()) return null;
            return String.valueOf(list.get(0).get("name"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 引用表名(order 是保留字)。校验白名单防止恢复恶意备份文件时 SQL 注入。 */
    public String quoteTable(String table) {
        if (!BackupConstants.TABLES_IN_ORDER.contains(table)) {
            throw new BusinessException("非法表名: " + table);
        }
        if ("order".equals(table)) return "`order`";
        return table;
    }

    /** P0-4 引用列名:统一加反引号,防止列名拼接 SQL 注入(MySQL/H2 均支持)。
     *  校验列名仅含字母/数字/下划线,防止反引号断裂注入。 */
    public String quoteColumn(String column) {
        if (column == null || !column.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new BusinessException("非法列名: " + column);
        }
        return "`" + column + "`";
    }

    /** Jackson 反序列化后值类型转换(Long/Integer/Double/String)。 */
    public Object convertValue(Object v) {
        if (v == null) return null;
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Long) return v;
        if (v instanceof Double) return new BigDecimal(v.toString());
        if (v instanceof Float) return new BigDecimal(v.toString());
        return v;
    }

    /** 安全转 Long,失败返回 null(用于读取备份文档中的 ID 字段)。 */
    public Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
