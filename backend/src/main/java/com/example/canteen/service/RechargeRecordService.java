package com.example.canteen.service;

import com.example.canteen.entity.Employee;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class RechargeRecordService {
    private static final BigDecimal MAX_RECHARGE_AMOUNT = new BigDecimal("100000");

    private final RechargeRecordMapper rechargeRecordMapper;
    private final EmployeeMapper employeeMapper;

    public RechargeRecordService(RechargeRecordMapper rechargeRecordMapper, EmployeeMapper employeeMapper) {
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.employeeMapper = employeeMapper;
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
        record.setOperator(remark);
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
