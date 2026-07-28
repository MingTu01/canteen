package com.example.canteen.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 系统配置读取服务。
 *
 * 从 sys_config 表读取配置项,支持布尔/整数/字符串三种类型。
 * 替代原 BackupService 内部的 getBoolConfig/getIntConfig/getStrConfig 私有方法,
 * 供 BackupSchedulerService 等需要读取运行期配置的服务注入使用。
 */
@Service
public class SystemConfigService {

    private final JdbcTemplate jdbcTemplate;

    public SystemConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean getBoolConfig(String key, boolean def) {
        try {
            String v = getStrConfig(key, def ? "true" : "false").toLowerCase();
            return "true".equals(v) || "1".equals(v);
        } catch (Exception e) {
            return def;
        }
    }

    public int getIntConfig(String key, int def) {
        try {
            return Integer.parseInt(getStrConfig(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    public String getStrConfig(String key, String def) {
        try {
            List<Map<String, Object>> list = jdbcTemplate.queryForList(
                    "SELECT config_value FROM sys_config WHERE config_key = ?", key);
            if (list.isEmpty()) return def;
            Object v = list.get(0).get("config_value");
            return v == null ? def : String.valueOf(v);
        } catch (Exception e) {
            return def;
        }
    }
}
