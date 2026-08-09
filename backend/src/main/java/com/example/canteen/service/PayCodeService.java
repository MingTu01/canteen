package com.example.canteen.service;

import com.example.canteen.entity.Employee;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.entity.Department;
import com.example.canteen.dto.EmployeeVO;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.security.SecurityContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 一次性支付码服务(支付码方案)。
 *
 * 安全特性:
 * - 32 位随机 hex 码(128 bit),防暴力枚举(组合数 2^128)
 * - Redis 存储,TTL=5 分钟自动过期
 * - 验证使用 GETDEL 原子操作,核销即失效,防截图重放
 * - 门店隔离:验证时校验 storeId=终端绑定门店
 * - 不含个人信息:二维码仅含随机码,不含姓名/卡号
 *
 * Redis key 设计:
 *   pay:code:{code}  →  value: {employeeId, storeId}  TTL: 5 分钟
 *
 * 降级策略:
 *   dev 环境无 Redis 时,generatePayCode 抛出明确错误提示需启用 Redis。
 *   支付码方案依赖 Redis 的原子 GETDEL,无法降级为无状态验证。
 */
@Service
public class PayCodeService {
    private final EmployeeMapper employeeMapper;
    private final DepartmentMapper departmentMapper;
    /** 使用 ObjectProvider 支持 dev 环境无 Redis 的降级检测 */
    private final ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider;

    /** 支付码有效期:5 分钟 */
    private static final Duration PAY_CODE_TTL = Duration.ofMinutes(5);
    /** Redis key 前缀 */
    private static final String PAY_CODE_PREFIX = "pay:code:";
    /** 支付码长度:32 位 hex(128 bit 随机数) */
    private static final int CODE_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 原子 GETDEL Lua 脚本:取出即删除,保证一次性消费,防并发重放。
     * Spring Data Redis 的 get() + delete() 是两次独立 RTT,存在竞态:
     * 两个终端并发验证同一支付码时可能都读到值再各自删除,导致一码多用。
     * 用 Lua 脚本在 Redis 单线程内原子完成 GET+DEL。
     */
    private static final DefaultRedisScript<Object> GETDEL_SCRIPT;
    static {
        GETDEL_SCRIPT = new DefaultRedisScript<>(
                "local v = redis.call('GET', KEYS[1]) if v then redis.call('DEL', KEYS[1]) end return v",
                Object.class
        );
    }

    public PayCodeService(EmployeeMapper employeeMapper,
                          DepartmentMapper departmentMapper,
                          ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider) {
        this.employeeMapper = employeeMapper;
        this.departmentMapper = departmentMapper;
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /** 获取 RedisTemplate(降级安全) */
    private RedisTemplate<String, Object> redis() {
        try {
            return redisTemplateProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成一次性支付码(供 H5「我的」页生成二维码)。
     *
     * @param employeeId 当前登录员工 ID(从 token 提取)
     * @return 包含 code 和 expire 的 Map;Redis 不可用时抛异常
     */
    public Map<String, Object> generatePayCode(Long employeeId) {
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            return null;
        }

        RedisTemplate<String, Object> tpl = redis();
        if (tpl == null) {
            throw new BusinessException("支付码服务需要 Redis 支持,请检查 Redis 是否已启用");
        }

        // 生成 32 位随机 hex 码(128 bit)
        byte[] bytes = new byte[16]; // 16 字节 = 128 bit = 32 位 hex
        secureRandom.nextBytes(bytes);
        StringBuilder code = new StringBuilder();
        for (byte b : bytes) {
            code.append(String.format("%02x", b));
        }
        String payCode = code.toString();

        // 存入 Redis:TTL=5 分钟,value 含 employeeId 和 storeId
        String redisKey = PAY_CODE_PREFIX + payCode;
        Map<String, Object> value = new HashMap<>();
        value.put("employeeId", employee.getId());
        value.put("storeId", employee.getStoreId());
        tpl.opsForValue().set(redisKey, value, PAY_CODE_TTL);

        // 返回给前端:仅含 code 和 expire(不含个人信息)
        Map<String, Object> result = new HashMap<>();
        result.put("code", payCode);
        result.put("expire", System.currentTimeMillis() + PAY_CODE_TTL.toMillis());
        return result;
    }

    /**
     * 验证一次性支付码(供终端扫码验证)。
     *
     * 使用 Redis GETDEL 原子操作:取出即删除,保证一次性使用,防截图重放。
     *
     * 校验顺序:
     *   1. 仅终端 token(role=3)
     *   2. 终端已绑定门店
     *   3. 支付码格式校验(32 位 hex)
     *   4. Redis GETDEL 原子取出并删除(一次性消费)
     *   5. 门店隔离:支付码 storeId == 终端绑定 storeId
     *   6. 员工存在且启用
     *
     * @param payCode 32 位 hex 支付码
     * @return 员工信息;失败抛 BusinessException
     */
    @SuppressWarnings("unchecked")
    public EmployeeVO verifyPayCode(String payCode) {
        // 1. 仅终端可验证
        Integer role = SecurityContext.currentRole();
        if (role == null || role != 3) {
            throw new BusinessException("仅终端设备可验证支付码");
        }

        // 2. 终端已绑定门店
        Long terminalStoreId = SecurityContext.currentStoreId();
        if (terminalStoreId == null) {
            throw new BusinessException("终端未绑定食堂");
        }

        // 3. 支付码格式校验(32 位 hex)
        if (payCode == null || payCode.length() != CODE_LENGTH || !payCode.matches("^[0-9a-fA-F]{32}$")) {
            throw new BusinessException("支付码格式无效");
        }

        RedisTemplate<String, Object> tpl = redis();
        if (tpl == null) {
            throw new BusinessException("支付码服务不可用");
        }

        // 4. Redis 原子 GETDEL(Lua 脚本):取出即删除,保证一次性消费,防并发重放
        String redisKey = PAY_CODE_PREFIX + payCode;
        Object raw = tpl.execute(GETDEL_SCRIPT, Collections.singletonList(redisKey));
        if (raw == null) {
            // 码不存在或已被消费(可能已使用过或已过期)
            throw new BusinessException("支付码无效或已使用");
        }

        Map<String, Object> value;
        if (raw instanceof Map) {
            value = (Map<String, Object>) raw;
        } else {
            throw new BusinessException("支付码数据异常");
        }

        Long employeeId = toLong(value.get("employeeId"));
        Long qrStoreId = toLong(value.get("storeId"));
        if (employeeId == null || qrStoreId == null) {
            throw new BusinessException("支付码数据不完整");
        }

        // 5. 门店隔离
        if (!qrStoreId.equals(terminalStoreId)) {
            throw new BusinessException("支付码不属于本食堂");
        }

        // 6. 员工存在且启用
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null || employee.getStatus() != 1 || employee.getIsDeleted() == 1) {
            throw new BusinessException("员工不存在或已失效");
        }
        // 门店一致性二次校验
        if (!employee.getStoreId().equals(terminalStoreId)) {
            throw new BusinessException("员工不属于本食堂");
        }

        EmployeeVO vo = EmployeeVO.from(employee);
        if (employee.getDepartmentId() != null) {
            Department dept = departmentMapper.selectById(employee.getDepartmentId());
            if (dept != null) {
                vo.setDepartmentName(dept.getName());
            }
        }
        return vo;
    }

    /** 安全转换 Long(支持 Number 和 String 类型) */
    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
