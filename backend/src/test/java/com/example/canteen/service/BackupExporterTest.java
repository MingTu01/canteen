package com.example.canteen.service;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BackupExporter 单元测试。
 *
 * 重点覆盖:
 * 1. 全库导出:遍历 TABLES_IN_ORDER,每表调用 SELECT * FROM。
 * 2. 门店导出:store 表按 id 过滤;menu_item/order_item 走关联表;其他表按 store_id 过滤。
 * 3. SQL 工具:quoteTable(order 加反引号)、quoteColumn(加反引号)。
 * 4. Jackson 值转换:Integer→Long、Double→BigDecimal、toLong 安全失败。
 */
@DisplayName("备份数据导出测试")
class BackupExporterTest {

    private JdbcTemplate jdbcTemplate;
    private BackupExporter exporter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        exporter = new BackupExporter(jdbcTemplate);
        // 默认返回空列表(防止 NPE)
        when(jdbcTemplate.queryForList(anyString())).thenReturn(new ArrayList<>());
        when(jdbcTemplate.queryForList(anyString(), any(Object.class))).thenReturn(new ArrayList<>());
    }

    @Test
    @DisplayName("exportData - full 模式 - 应遍历所有表,每表一条 SELECT *")
    void exportData_full_iteratesAllTables() {
        // store 表返回 1 行,验证返回值包含
        Map<String, Object> storeRow = new HashMap<>();
        storeRow.put("id", 1);
        storeRow.put("name", "测试门店");
        when(jdbcTemplate.queryForList(eq("SELECT * FROM store"))).thenReturn(List.of(storeRow));
        when(jdbcTemplate.queryForList(eq("SELECT * FROM `order`"))).thenReturn(new ArrayList<>());

        Map<String, List<Map<String, Object>>> data = exporter.exportData("full", null);

        // 应包含所有业务表
        assertEquals(BackupConstants.TABLES_IN_ORDER.size(), data.size());
        // store 表应返回 1 行
        assertEquals(1, data.get("store").size());
        assertEquals("测试门店", data.get("store").get(0).get("name"));
    }

    @Test
    @DisplayName("exportData - store 模式 - menu_item 应走关联表查询")
    void exportData_store_menuItemUsesJoin() {
        when(jdbcTemplate.queryForList(contains("SELECT mi.* FROM menu_item mi INNER JOIN menu"), eq(5L)))
                .thenReturn(List.of(new HashMap<>()));

        Map<String, List<Map<String, Object>>> data = exporter.exportData("store", 5L);

        // menu_item 应通过关联表查询,返回 mock 的 1 行
        assertEquals(1, data.get("menu_item").size());
    }

    @Test
    @DisplayName("exportData - store 模式 - order_item 应走关联表查询")
    void exportData_store_orderItemUsesJoin() {
        when(jdbcTemplate.queryForList(contains("SELECT oi.* FROM order_item oi INNER JOIN `order`"), eq(5L)))
                .thenReturn(List.of(new HashMap<>()));

        Map<String, List<Map<String, Object>>> data = exporter.exportData("store", 5L);

        assertEquals(1, data.get("order_item").size());
    }

    @Test
    @DisplayName("exportData - store 模式 - 带 store_id 列的表应按 store_id 过滤")
    void exportData_store_storeIdTablesFiltered() {
        when(jdbcTemplate.queryForList(eq("SELECT * FROM dish WHERE store_id = ?"), eq(5L)))
                .thenReturn(List.of(new HashMap<>()));

        Map<String, List<Map<String, Object>>> data = exporter.exportData("store", 5L);

        assertEquals(1, data.get("dish").size());
    }

    @Test
    @DisplayName("getStoreName - 查询成功 - 应返回门店名")
    void getStoreName_success() {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "门店五");
        // 用 doReturn 避免 queryForList 重载歧义(Class vs varargs)
        doReturn(List.of(row)).when(jdbcTemplate)
                .queryForList(eq("SELECT name FROM store WHERE id = ?"), eq(5L));
        assertEquals("门店五", exporter.getStoreName(5L));
    }

    @Test
    @DisplayName("getStoreName - 查询无结果 - 应返回 null")
    void getStoreName_noResult_returnsNull() {
        doReturn(Collections.emptyList()).when(jdbcTemplate)
                .queryForList(eq("SELECT name FROM store WHERE id = ?"), eq(999L));
        assertNull(exporter.getStoreName(999L));
    }

    @Test
    @DisplayName("getStoreName - storeId 为 null - 应返回 null 不查询")
    void getStoreName_nullStoreId_returnsNull() {
        assertNull(exporter.getStoreName(null));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("getStoreName - DB 异常 - 应吞掉异常返回 null(不阻塞备份流程)")
    void getStoreName_dbError_returnsNull() {
        doThrow(new RuntimeException("connection lost")).when(jdbcTemplate)
                .queryForList(anyString(), any(Object[].class));
        assertNull(exporter.getStoreName(5L));
    }

    /* ----- SQL 工具方法 ----- */

    @Test
    @DisplayName("quoteTable - order 是保留字应加反引号,其他表名原样返回")
    void quoteTable_orderAndOthers() {
        assertEquals("`order`", exporter.quoteTable("order"));
        assertEquals("store", exporter.quoteTable("store"));
        assertEquals("menu_item", exporter.quoteTable("menu_item"));
    }

    @Test
    @DisplayName("quoteColumn - 应统一加反引号(防 SQL 注入)")
    void quoteColumn_addsBackticks() {
        assertEquals("`id`", exporter.quoteColumn("id"));
        assertEquals("`store_id`", exporter.quoteColumn("store_id"));
        // 内嵌反引号原样保留(外层再加一对),实际生产场景列名不会含反引号
        assertEquals("``select``", exporter.quoteColumn("`select`"));
    }

    /* ----- Jackson 值转换 ----- */

    @Test
    @DisplayName("convertValue - Integer 应转 Long")
    void convertValue_integerToLong() {
        Object result = exporter.convertValue(42);
        assertInstanceOf(Long.class, result);
        assertEquals(42L, result);
    }

    @Test
    @DisplayName("convertValue - Long 应保持 Long")
    void convertValue_longStaysLong() {
        Object result = exporter.convertValue(42L);
        assertInstanceOf(Long.class, result);
        assertEquals(42L, result);
    }

    @Test
    @DisplayName("convertValue - Double 应转 BigDecimal")
    void convertValue_doubleToBigDecimal() {
        Object result = exporter.convertValue(3.14);
        assertInstanceOf(BigDecimal.class, result);
        assertEquals(new BigDecimal("3.14"), result);
    }

    @Test
    @DisplayName("convertValue - null 应保持 null")
    void convertValue_nullStaysNull() {
        assertNull(exporter.convertValue(null));
    }

    @Test
    @DisplayName("convertValue - String 应保持 String")
    void convertValue_stringStaysString() {
        Object result = exporter.convertValue("hello");
        assertInstanceOf(String.class, result);
        assertEquals("hello", result);
    }

    /* ----- toLong ----- */

    @Test
    @DisplayName("toLong - Number 类型 - 应转 Long")
    void toLong_number() {
        assertEquals(42L, exporter.toLong(42));
        assertEquals(42L, exporter.toLong(42L));
        assertEquals(42L, exporter.toLong(42.0));
    }

    @Test
    @DisplayName("toLong - 数字字符串 - 应转 Long")
    void toLong_numericString() {
        assertEquals(42L, exporter.toLong("42"));
    }

    @Test
    @DisplayName("toLong - 非数字字符串 - 应返回 null")
    void toLong_nonNumericString_returnsNull() {
        assertNull(exporter.toLong("abc"));
    }

    @Test
    @DisplayName("toLong - null - 应返回 null")
    void toLong_null_returnsNull() {
        assertNull(exporter.toLong(null));
    }
}
