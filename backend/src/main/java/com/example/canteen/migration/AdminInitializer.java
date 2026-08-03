package com.example.canteen.migration;

import com.example.canteen.entity.Admin;
import com.example.canteen.mapper.AdminMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * 初始化标记文件:首次 FORCE 重置密码后创建,后续重启即使 FORCE=true 也跳过,
     * 避免覆盖管理员通过 UI 修改过的密码。
     * 优先使用 /app/.admin-initialized(Docker 容器内固定路径),
     * 不可写时回退到系统临时目录。
     */
    private static final String INIT_FLAG_FILE_APP = "/app/.admin-initialized";
    private static final String INIT_FLAG_FILE_TMP =
            System.getProperty("java.io.tmpdir") + "/.canteen-admin-initialized";

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
                    boolean forceRequested = "true".equalsIgnoreCase(force);
                    boolean shouldUpdate = forceRequested || isDefaultPassword(existing);

                    // FORCE 模式下,若已初始化过(标记文件存在),跳过密码重置,
                    // 避免每次重启覆盖管理员通过 UI 修改的密码。
                    if (forceRequested && shouldUpdate && alreadyInitialized()) {
                        LOG.info("[AdminInitializer] 管理员密码已初始化过,跳过 FORCE 重置(避免覆盖用户修改的密码)");
                        return;
                    }

                    if (shouldUpdate) {
                        existing.setPassword(passwordEncoder.encode(password));
                        existing.setPasswordUpdatedAt(LocalDateTime.now());
                        adminMapper.updateById(existing);
                        LOG.info("[AdminInitializer] 已更新超管 '" + username + "' 的密码");
                        // FORCE 重置成功后写标记文件,后续重启不再覆盖
                        if (forceRequested) {
                            markInitialized();
                        }
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
            // FORCE 模式下创建成功后写标记文件,后续重启不再覆盖
            if (forceCreate) {
                markInitialized();
            }

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

    /**
     * 检查是否已初始化过(标记文件存在)。
     * 优先检查 /app/.admin-initialized(Docker 容器路径),其次检查系统临时目录下的标记文件。
     */
    private boolean alreadyInitialized() {
        try {
            if (Files.exists(Paths.get(INIT_FLAG_FILE_APP))) {
                return true;
            }
            return Files.exists(Paths.get(INIT_FLAG_FILE_TMP));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 创建初始化标记文件。优先写入 /app/.admin-initialized,
     * 若目录不可写则回退到系统临时目录。权限问题时仅 warn,不阻断启动。
     */
    private void markInitialized() {
        // 优先尝试 /app/ 目录
        try {
            Path appPath = Paths.get(INIT_FLAG_FILE_APP);
            if (Files.isWritable(appPath.getParent()) || Files.exists(appPath)) {
                if (!Files.exists(appPath)) {
                    Files.createFile(appPath);
                }
                LOG.info("[AdminInitializer] 已创建初始化标记文件 " + INIT_FLAG_FILE_APP);
                return;
            }
        } catch (Exception e) {
            LOG.warning("[AdminInitializer] 无法创建标记文件 " + INIT_FLAG_FILE_APP + ": " + e.getMessage());
        }
        // 回退到系统临时目录
        try {
            Path tmpPath = Paths.get(INIT_FLAG_FILE_TMP);
            if (!Files.exists(tmpPath)) {
                Files.createFile(tmpPath);
            }
            LOG.info("[AdminInitializer] 已创建初始化标记文件 " + INIT_FLAG_FILE_TMP);
        } catch (Exception e) {
            LOG.warning("[AdminInitializer] 无法创建标记文件 " + INIT_FLAG_FILE_TMP + ": " + e.getMessage());
        }
    }
}
