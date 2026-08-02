package com.example.canteen.migration;

import com.example.canteen.entity.Admin;
import com.example.canteen.mapper.AdminMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.logging.Logger;

/**
 * 启动时初始化超级管理员账号(可选)。
 *
 * 设计要点:
 * - 读取环境变量 INIT_ADMIN_USERNAME / INIT_ADMIN_PASSWORD,若配置则初始化超管账号。
 * - 仅在 admin 表中只有默认 admin 账号(role=1, store_id=0)或为空时生效,
 *   避免覆盖已运营系统中的超管账号。
 * - 幂等:通过环境变量 + 账号匹配判断,已存在同名账号时仅更新密码(若账号是超管),
 *   不重复创建。
 * - 不抛异常以避免阻塞应用启动(仅记录警告日志)。
 *
 * 使用场景:
 * - 首次部署时通过 deploy.sh CLI 向导设置自定义超管账号密码
 * - 重新部署时重置超管密码(需配合 INIT_ADMIN_FORCE=true)
 */
@Component
public class AdminInitializer {

    private static final Logger LOG = Logger.getLogger(AdminInitializer.class.getName());

    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminInitializer(AdminMapper adminMapper, PasswordEncoder passwordEncoder) {
        this.adminMapper = adminMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(60)  // 在 SchemaMigrationRunner(50) 之后执行
    public void init() {
        String username = System.getenv("INIT_ADMIN_USERNAME");
        String password = System.getenv("INIT_ADMIN_PASSWORD");
        String force = System.getenv("INIT_ADMIN_FORCE");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            // 未配置初始化环境变量,跳过
            return;
        }

        if (password.length() < 8) {
            LOG.warning("[AdminInitializer] INIT_ADMIN_PASSWORD 长度不足 8 位,跳过初始化");
            return;
        }

        try {
            // 查询是否已有同名账号
            Admin existing = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                    .eq(Admin::getUsername, username));

            if (existing != null) {
                // 账号已存在
                if (existing.getRole() != null && existing.getRole() == 1) {
                    // 已是超管,更新密码(仅在 force=true 或密码为默认值时)
                    boolean shouldUpdate = "true".equalsIgnoreCase(force)
                            || isDefaultPassword(existing);
                    if (shouldUpdate) {
                        existing.setPassword(passwordEncoder.encode(password));
                        existing.setPasswordUpdatedAt(LocalDateTime.now());
                        adminMapper.updateById(existing);
                        LOG.info("[AdminInitializer] 已更新超管 '" + username + "' 的密码");
                    } else {
                        LOG.info("[AdminInitializer] 超管 '" + username + "' 已存在且非默认密码,跳过(如需强制重置请设置 INIT_ADMIN_FORCE=true)");
                    }
                } else {
                    LOG.warning("[AdminInitializer] 账号 '" + username + "' 已存在但非超管角色,跳过");
                }
                return;
            }

            // 账号不存在
            boolean forceCreate = "true".equalsIgnoreCase(force);

            if (!forceCreate) {
                // 非 force 模式:仅在首次部署(admin 表只有默认 admin 且密码仍为默认值)时允许创建
                // 避免在已运营系统上误创建新超管
                Admin defaultAdmin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                        .eq(Admin::getUsername, "admin")
                        .eq(Admin::getRole, 1));
                long totalCount = adminMapper.selectCount(null);

                // 允许创建的条件:表为空,或只有默认 admin 且密码仍是默认值 123456
                boolean isInitialDeploy = (totalCount == 0)
                        || (totalCount == 1 && defaultAdmin != null && isDefaultPassword(defaultAdmin));

                if (!isInitialDeploy) {
                    LOG.warning("[AdminInitializer] admin 表已有 " + totalCount + " 个账号且非初始状态,跳过创建。如需强制创建请设置 INIT_ADMIN_FORCE=true。");
                    return;
                }
                LOG.info("[AdminInitializer] 检测到首次部署,允许创建自定义超管 '" + username + "'");
            } else {
                // force 模式:由管理员主动触发(canteen → 重置管理员密码),允许直接创建
                LOG.info("[AdminInitializer] force=true,允许创建新超管 '" + username + "'");
            }

            // 创建超管账号
            Admin admin = new Admin();
            admin.setUsername(username);
            admin.setPassword(passwordEncoder.encode(password));
            admin.setName("超级管理员");
            admin.setStoreId(0L);
            admin.setRole(1);
            admin.setStatus(1);
            admin.setPasswordUpdatedAt(LocalDateTime.now());
            adminMapper.insert(admin);
            LOG.info("[AdminInitializer] 已创建超管账号 '" + username + "'");

            // 若默认 admin 账号存在且与新账号不同,删除默认账号
            if (!"admin".equals(username)) {
                Admin defaultAdmin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                        .eq(Admin::getUsername, "admin")
                        .eq(Admin::getRole, 1));
                if (defaultAdmin != null && isDefaultPassword(defaultAdmin)) {
                    adminMapper.deleteById(defaultAdmin.getId());
                    LOG.info("[AdminInitializer] 已删除默认 admin 账号(密码仍为默认值,被自定义超管替代)");
                }
            }

        } catch (Exception e) {
            LOG.warning("[AdminInitializer] 初始化超管失败: " + e.getMessage());
        }
    }

    /**
     * 检查 admin 账号的密码是否为默认密码(123456)。
     * 通过 BCrypt matches 判断,避免硬编码哈希值比较。
     */
    private boolean isDefaultPassword(Admin admin) {
        try {
            return passwordEncoder.matches("123456", admin.getPassword());
        } catch (Exception e) {
            return false;
        }
    }
}
