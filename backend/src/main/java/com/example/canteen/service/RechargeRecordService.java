package com.example.canteen.service;

import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class RechargeRecordService {
    private static final BigDecimal MAX_RECHARGE_AMOUNT = new BigDecimal("100000");
    /** 充值防重锁 TTL(秒):短 TTL 自动过期,不显式释放,避免误删后续请求的锁 */
    private static final Duration RECHARGE_LOCK_TTL = Duration.ofSeconds(3);

    private final RechargeRecordMapper rechargeRecordMapper;
    private final EmployeeMapper employeeMapper;
    private final AdminMapper adminMapper;
    /** 与 PayCodeService 一致:通过 ObjectProvider 注入,Redis 未装配(dev)时降级为无锁 */
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;

    public RechargeRecordService(RechargeRecordMapper rechargeRecordMapper, EmployeeMapper employeeMapper,
                                 AdminMapper adminMapper,
                                 ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.employeeMapper = employeeMapper;
        this.adminMapper = adminMapper;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
    }

    /** 获取当前操作管理员姓名,用于充值记录的 operator 字段 */
    private String currentOperatorName() {
        Long adminId = SecurityContext.currentAdminId();
        if (adminId == null) return null;
        Admin admin = adminMapper.selectById(adminId);
        return admin != null ? admin.getName() : ("admin#" + adminId);
    }

    @Transactional
    public RechargeRecord recharge(Long employeeId, Long storeId, BigDecimal amount, String remark) {
        // B4 金额校验
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("充值金额必须大于0");
        }
        if (amount.compareTo(MAX_RECHARGE_AMOUNT) > 0) {
            throw new BusinessException("充值金额超过上限");
        }

        // 幂等防重锁:同一员工同一金额 3 秒内只允许一次充值(防前端双击重复提交)
        // Redis 异常时降级为无锁放行,避免 Redis 故障阻塞充值
        String lockKey = "recharge:idem:" + employeeId + ":" + amount.toPlainString();
        try {
            StringRedisTemplate redis = stringRedisTemplateProvider.getIfAvailable();
            if (redis != null) {
                Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1", RECHARGE_LOCK_TTL);
                if (Boolean.FALSE.equals(acquired)) {
                    throw new BusinessException("充值请求处理中,请勿重复提交");
                }
                // 不显式释放:依赖 3 秒 TTL 自动过期
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("充值防重锁异常,降级为无锁: {}", e.getMessage());
        }

        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }

        // 校验员工归属门店
        SecurityContext.checkStoreAccess(employee.getStoreId());
        if (storeId != null && !storeId.equals(employee.getStoreId())) {
            throw new BusinessException("员工不属于该门店");
        }

        BigDecimal balanceBefore = employee.getBalance();

        // 原子加余额
        int rows = employeeMapper.addBalance(employeeId, amount);
        if (rows == 0) {
            throw new BusinessException("充值失败");
        }

        // 重新查询获取真实余额,避免并发充值时 balanceAfter 不准
        Employee updated = employeeMapper.selectById(employeeId);
        BigDecimal balanceAfter = updated != null ? updated.getBalance() : balanceBefore.add(amount);

        RechargeRecord record = new RechargeRecord();
        record.setStoreId(employee.getStoreId());
        record.setEmployeeId(employeeId);
        record.setAmount(amount);
        record.setBalanceBefore(balanceBefore);
        record.setBalanceAfter(balanceAfter);
        record.setOperator(currentOperatorName());
        record.setRemark(remark);
        rechargeRecordMapper.insert(record);

        return record;
    }

    public List<RechargeRecord> getRecordsByStore(Long storeId) {
        SecurityContext.checkStoreAccess(storeId);
        return rechargeRecordMapper.selectByStoreId(storeId);
    }

    public List<RechargeRecord> getRecordsByEmployee(Long employeeId) {
        return rechargeRecordMapper.selectByEmployeeId(employeeId);
    }
}
