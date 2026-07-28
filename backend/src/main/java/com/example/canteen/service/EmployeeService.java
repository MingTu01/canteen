package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.canteen.entity.Department;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.RechargeRecord;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.RechargeRecordMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmployeeService {
    private final EmployeeMapper employeeMapper;
    private final DepartmentMapper departmentMapper;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final PasswordEncoder passwordEncoder;

    public EmployeeService(EmployeeMapper employeeMapper, DepartmentMapper departmentMapper,
                           RechargeRecordMapper rechargeRecordMapper,
                           PasswordEncoder passwordEncoder) {
        this.employeeMapper = employeeMapper;
        this.departmentMapper = departmentMapper;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /** 查询时手动加 is_deleted=0 过滤(MyBatis Plus 逻辑删除配置后续会加) */
    public List<Employee> getEmployeesByStore(Long storeId) {
        return employeeMapper.selectByStoreId(storeId);
    }

    public Employee getEmployeeById(Long id) {
        return employeeMapper.selectById(id);
    }

    /**
     * S7 卡号查询加 storeId,防止跨门店卡号查询。
     */
    public Employee getEmployeeByCardNoAndStore(String cardNo, Long storeId) {
        return employeeMapper.selectByCardNoAndStore(cardNo, storeId);
    }

    public Employee createEmployee(Employee employee) {
        SecurityContext.checkStoreAccess(employee.getStoreId());
        if (employee.getIsDeleted() == null) {
            employee.setIsDeleted(0);
        }
        // P1-5 密码哈希:若未提供则生成随机 8 位密码;若已是 BCrypt 则保留
        String pwd = employee.getPassword();
        if (pwd == null || pwd.isBlank()) {
            pwd = generateDefaultPassword();
        }
        if (!pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$")) {
            pwd = passwordEncoder.encode(pwd);
        }
        employee.setPassword(pwd);
        // 初始化密码更新时间(用于 JWT 失效校验)
        if (employee.getPasswordUpdatedAt() == null) {
            employee.setPasswordUpdatedAt(LocalDateTime.now());
        }
        employeeMapper.insert(employee);
        return employee;
    }

    /** P1-5 生成随机 8 位默认密码(排除易混淆字符 0/O/1/I/l) */
    private String generateDefaultPassword() {
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public Employee updateEmployee(Employee employee) {
        Employee existing = employeeMapper.selectById(employee.getId());
        if (existing == null) {
            throw new BusinessException("员工不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 禁止通过 update 修改 storeId(防跨租户移动员工),用 existing.storeId 覆盖
        employee.setStoreId(existing.getStoreId());
        // P0-1 禁止通过 update 修改敏感字段:余额(只能走 recharge)/密码新鲜度/删除标记
        // 设为 null 后,MyBatis Plus 默认 NOT_NULL 策略会跳过这些字段不更新
        employee.setBalance(null);
        employee.setPasswordUpdatedAt(null);
        employee.setIsDeleted(null);
        // 密码为空表示不修改;非空且非 BCrypt 才加密
        String pwd = employee.getPassword();
        if (pwd != null && !pwd.isBlank()
                && !pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$")) {
            // P1-6 校验密码长度
            if (pwd.length() < 6) {
                throw new BusinessException("密码至少6位");
            }
            employee.setPassword(passwordEncoder.encode(pwd));
            // P1-1 同步更新密码修改时间,使旧 token 失效
            employee.setPasswordUpdatedAt(LocalDateTime.now());
        } else if (pwd == null || pwd.isBlank()) {
            // 不更新密码:用 null 标记,updateById 会忽略 null 字段(默认)
            employee.setPassword(null);
        }
        employeeMapper.updateById(employee);
        return employee;
    }

    /**
     * 批量导入员工。逐行处理,失败行记录错误信息但不中断整体导入。
     * 返回 {success, failed, errors:[{row, cardNo, reason}]}。
     */
    public Map<String, Object> batchImport(Long storeId, List<Employee> rows) {
        SecurityContext.checkStoreAccess(storeId);
        // 预加载部门名称 -> id 映射(本门店)
        Map<String, Long> deptNameToId = new HashMap<>();
        List<Department> depts = departmentMapper.selectByStoreId(storeId);
        if (depts != null) {
            for (Department d : depts) {
                deptNameToId.put(d.getName(), d.getId());
            }
        }

        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Employee e = rows.get(i);
            int rowNo = i + 2; // Excel 第 1 行是表头
            try {
                if (e.getCardNo() == null || e.getCardNo().isBlank()) {
                    throw new BusinessException("卡号不能为空");
                }
                if (e.getName() == null || e.getName().isBlank()) {
                    throw new BusinessException("姓名不能为空");
                }
                // 卡号唯一性校验(同门店)
                Long exist = employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getStoreId, storeId)
                        .eq(Employee::getCardNo, e.getCardNo()));
                if (exist != null && exist > 0) {
                    throw new BusinessException("卡号已存在");
                }
                e.setStoreId(storeId);
                if (e.getIsDeleted() == null) e.setIsDeleted(0);
                if (e.getStatus() == null) e.setStatus(1);
                if (e.getBalance() == null) {
                    e.setBalance(java.math.BigDecimal.ZERO);
                }
                // 部门名称 -> id 转换:优先使用 departmentName,其次 departmentId
                if (e.getDepartmentId() == null && e.getDepartmentName() != null) {
                    e.setDepartmentId(deptNameToId.get(e.getDepartmentName().trim()));
                }
                // P1-5 密码处理:未提供则生成随机 8 位密码
                String pwd = e.getPassword();
                if (pwd == null || pwd.isBlank()) {
                    pwd = generateDefaultPassword();
                }
                if (!pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$")) {
                    pwd = passwordEncoder.encode(pwd);
                }
                e.setPassword(pwd);
                employeeMapper.insert(e);
                success++;
            } catch (Exception ex) {
                Map<String, Object> err = new HashMap<>();
                err.put("row", rowNo);
                err.put("cardNo", e.getCardNo());
                err.put("name", e.getName());
                err.put("reason", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                errors.add(err);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("failed", errors.size());
        result.put("errors", errors);
        return result;
    }

    /**
     * 批量充值:给指定门店所有活跃员工(status=1, is_deleted=0)充值指定金额。
     * 使用 EmployeeMapper.addBalance 原子增加余额,同时写入 recharge_record 表。
     * 返回 { successCount, totalAmount }。
     */
    @Transactional
    public Map<String, Object> batchRecharge(Long storeId, BigDecimal amount) {
        SecurityContext.checkStoreAccess(storeId);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("充值金额必须大于0");
        }
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getStatus, 1)
                .eq(Employee::getIsDeleted, 0));
        Long adminId = SecurityContext.currentAdminId();
        String operatorLabel = adminId != null ? "批量充值(admin#" + adminId + ")" : "批量充值";
        int successCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Employee e : employees) {
            BigDecimal balanceBefore = e.getBalance() == null ? BigDecimal.ZERO : e.getBalance();
            employeeMapper.addBalance(e.getId(), amount);
            // 重新查询获取真实余额,避免并发充值时 balanceAfter 不准
            Employee updated = employeeMapper.selectById(e.getId());
            BigDecimal balanceAfter = updated != null && updated.getBalance() != null
                    ? updated.getBalance() : balanceBefore.add(amount);
            // 写入充值记录
            RechargeRecord record = new RechargeRecord();
            record.setStoreId(storeId);
            record.setEmployeeId(e.getId());
            record.setAmount(amount);
            record.setBalanceBefore(balanceBefore);
            record.setBalanceAfter(balanceAfter);
            record.setOperator(operatorLabel);
            rechargeRecordMapper.insert(record);
            successCount++;
            totalAmount = totalAmount.add(amount);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("totalAmount", totalAmount);
        return result;
    }

    /**
     * B11 软删除:不调用 deleteById,改为 is_deleted=1。
     */
    public void deleteEmployee(Long id) {
        Employee employee = employeeMapper.selectById(id);
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        SecurityContext.checkStoreAccess(employee.getStoreId());
        employee.setIsDeleted(1);
        employeeMapper.updateById(employee);
    }

    public List<Department> getDepartmentsByStore(Long storeId) {
        return departmentMapper.selectByStoreId(storeId);
    }

    /**
     * 余额预警名单:查询余额低于阈值的员工列表(分页)。
     * 仅查询本门店未删除员工。
     */
    public Map<String, Object> getLowBalanceList(Long storeId, BigDecimal threshold, int page, int size) {
        SecurityContext.checkStoreAccess(storeId);
        if (threshold == null) {
            throw new BusinessException("阈值不能为空");
        }
        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getIsDeleted, 0)
                .lt(Employee::getBalance, threshold)
                .orderByAsc(Employee::getBalance);
        IPage<Employee> p = employeeMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> records = new ArrayList<>();
        if (p.getRecords() != null) {
            for (Employee e : p.getRecords()) {
                records.add(employeeToVO(e));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", p.getTotal());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * 余额预警统计:返回预警人数、总余额、平均余额。
     */
    public Map<String, Object> getLowBalanceStats(Long storeId, BigDecimal threshold) {
        SecurityContext.checkStoreAccess(storeId);
        if (threshold == null) {
            throw new BusinessException("阈值不能为空");
        }
        List<Employee> list = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getIsDeleted, 0)
                .lt(Employee::getBalance, threshold));
        int count = list.size();
        BigDecimal total = BigDecimal.ZERO;
        for (Employee e : list) {
            if (e.getBalance() != null) {
                total = total.add(e.getBalance());
            }
        }
        BigDecimal avg = count > 0
                ? total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("totalBalance", total);
        result.put("avgBalance", avg);
        result.put("threshold", threshold);
        return result;
    }

    /** 简易员工 VO(脱敏,不含密码) */
    private Map<String, Object> employeeToVO(Employee e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put("storeId", e.getStoreId());
        m.put("cardNo", e.getCardNo());
        m.put("phone", e.getPhone());
        m.put("name", e.getName());
        m.put("avatar", e.getAvatar());
        m.put("departmentId", e.getDepartmentId());
        m.put("balance", e.getBalance());
        m.put("status", e.getStatus());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }
}
