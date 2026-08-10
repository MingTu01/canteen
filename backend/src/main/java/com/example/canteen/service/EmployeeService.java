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
import com.example.canteen.security.PasswordValidator;
import com.example.canteen.security.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * 按标识符自动适配匹配员工(批量头像上传用)。
     * 依次尝试:卡号 → 手机号 → 姓名,命中即返回;均未命中返回 null。
     */
    public Employee findEmployeeByIdentifier(String identifier, Long storeId) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String key = identifier.trim();
        // 1. 卡号
        Employee e = employeeMapper.selectByCardNoAndStore(key, storeId);
        if (e != null) {
            return e;
        }
        // 2. 手机号
        e = employeeMapper.selectByPhoneAndStore(key, storeId);
        if (e != null) {
            return e;
        }
        // 3. 姓名
        return employeeMapper.selectByNameAndStore(key, storeId);
    }

    public Employee createEmployee(Employee employee) {
        SecurityContext.checkStoreAccess(employee.getStoreId());
        // 手机号必填(H5/小程序登录凭证,同店内唯一)
        if (employee.getPhone() == null || employee.getPhone().isBlank()) {
            throw new BusinessException("手机号不能为空");
        }
        // 卡号必填
        if (employee.getCardNo() == null || employee.getCardNo().isBlank()) {
            throw new BusinessException("卡号不能为空");
        }
        // 卡号全局唯一(对齐数据库 employee.card_no 唯一索引,含已逻辑删除记录),避免唯一索引冲突报 500
        if (employeeMapper.countByCardNoExcludeId(employee.getCardNo(), null) > 0) {
            throw new BusinessException("卡号已存在: " + employee.getCardNo());
        }
        // P1-6 修复 Mass Assignment:强制重置敏感字段,防止前端注入
        employee.setId(null);               // 防止覆盖已有记录
        employee.setIsDeleted(0);           // 防止创建即删除
        employee.setPasswordUpdatedAt(LocalDateTime.now()); // 防止绕过 JWT 失效
        // P1-5 密码哈希:若未提供则使用默认密码 12345678;若已是 BCrypt 则保留
        boolean usedDefaultPassword = false;
        String pwd = employee.getPassword();
        if (pwd == null || pwd.isBlank()) {
            pwd = generateDefaultPassword();
            usedDefaultPassword = true;
        }
        if (!pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$")) {
            // P2-1 密码复杂度校验:仅对用户显式提供的密码校验(默认密码跳过,首次登录强制修改)
            if (!usedDefaultPassword) {
                PasswordValidator.validate(pwd);
            } else if (pwd.length() < 8) {
                throw new BusinessException("密码至少8位");
            }
            pwd = passwordEncoder.encode(pwd);
        }
        employee.setPassword(pwd);
        // 使用了默认密码 → 标记首次登录必须修改
        employee.setMustChangePassword(usedDefaultPassword ? 1 : 0);
        employeeMapper.insert(employee);
        return employee;
    }

    /** 默认初始密码(配合 H5 首次登录强制修改密码使用) */
    private static final String DEFAULT_PASSWORD = "12345678";

    /** 返回默认初始密码(未显式指定密码时使用,H5 首次登录会强制修改) */
    private String generateDefaultPassword() {
        return DEFAULT_PASSWORD;
    }

    public Employee updateEmployee(Employee employee) {
        Employee existing = employeeMapper.selectById(employee.getId());
        if (existing == null) {
            throw new BusinessException("员工不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 禁止通过 update 修改 storeId(防跨租户移动员工),用 existing.storeId 覆盖
        employee.setStoreId(existing.getStoreId());
        // 卡号全局唯一校验(编辑场景,排除自身),避免唯一索引冲突报 500
        if (employee.getCardNo() != null && !employee.getCardNo().isBlank()
                && employeeMapper.countByCardNoExcludeId(employee.getCardNo(), employee.getId()) > 0) {
            throw new BusinessException("卡号已存在: " + employee.getCardNo());
        }
        // P0-1 禁止通过 update 修改敏感字段:余额(只能走 recharge)/密码新鲜度/删除标记/强制改密标记
        // 设为 null 后,MyBatis Plus 默认 NOT_NULL 策略会跳过这些字段不更新
        employee.setBalance(null);
        employee.setPasswordUpdatedAt(null);
        employee.setIsDeleted(null);
        employee.setMustChangePassword(null);
        // 密码为空表示不修改;非空且非 BCrypt 才加密
        String pwd = employee.getPassword();
        if (pwd != null && !pwd.isBlank()
                && !pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$")) {
            // P2-1 密码复杂度校验(用户显式修改密码时必须满足复杂度要求)
            PasswordValidator.validate(pwd);
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
     * 重复导入(按姓名匹配同门店已有员工)时,自动更新卡号/手机号/密码(有变化才更新)。
     * 返回 {success, failed, created, updated, errors:[{row, cardNo, reason}]}。
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
        int created = 0;
        int updated = 0;
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
                if (e.getPhone() == null || e.getPhone().isBlank()) {
                    throw new BusinessException("手机号不能为空");
                }
                // 部门名称 -> id 转换:优先使用 departmentName,其次 departmentId
                if (e.getDepartmentId() == null && e.getDepartmentName() != null) {
                    e.setDepartmentId(deptNameToId.get(e.getDepartmentName().trim()));
                }

                // 按姓名匹配同门店已有员工:命中则批量更新(卡号/手机号/密码)
                Employee existing = employeeMapper.selectByNameAndStore(e.getName().trim(), storeId);
                if (existing != null) {
                    boolean changed = false;
                    // 更新卡号(有变化且不与其他人冲突)
                    if (e.getCardNo() != null && !e.getCardNo().equals(existing.getCardNo())) {
                        if (employeeMapper.countByCardNoExcludeId(e.getCardNo(), existing.getId()) > 0) {
                            throw new BusinessException("卡号已存在: " + e.getCardNo());
                        }
                        existing.setCardNo(e.getCardNo());
                        changed = true;
                    }
                    // 更新手机号(有变化才更新)
                    if (e.getPhone() != null && !e.getPhone().equals(existing.getPhone())) {
                        existing.setPhone(e.getPhone());
                        changed = true;
                    }
                    // 更新密码:提供了非空密码则用新密码;密码为空则重置为默认密码 12345678
                    // (导入模板已移除密码列,空密码 = 使用默认密码)
                    String pwd = e.getPassword();
                    if (pwd != null && !pwd.isBlank()
                            && !pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$")) {
                        PasswordValidator.validate(pwd);
                        existing.setPassword(passwordEncoder.encode(pwd));
                        existing.setPasswordUpdatedAt(LocalDateTime.now());
                        existing.setMustChangePassword(0);
                        changed = true;
                    } else {
                        // 密码为空:重置为默认密码 12345678,首次登录强制修改
                        existing.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
                        existing.setPasswordUpdatedAt(LocalDateTime.now());
                        existing.setMustChangePassword(1);
                        changed = true;
                    }
                    // 更新部门(有变化才更新)
                    if (e.getDepartmentId() != null && !e.getDepartmentId().equals(existing.getDepartmentId())) {
                        existing.setDepartmentId(e.getDepartmentId());
                        changed = true;
                    }
                    if (changed) {
                        employeeMapper.updateById(existing);
                        updated++;
                    }
                    success++;
                    continue;
                }

                // 未匹配到已有员工:新建
                // 卡号唯一性校验(全局,对齐数据库唯一索引含已删除记录)
                if (employeeMapper.countByCardNoExcludeId(e.getCardNo(), null) > 0) {
                    throw new BusinessException("卡号已存在");
                }
                e.setStoreId(storeId);
                // P1-6 修复 Mass Assignment:批量导入也强制重置敏感字段
                e.setId(null);
                e.setIsDeleted(0);
                e.setPasswordUpdatedAt(LocalDateTime.now());
                if (e.getStatus() == null) e.setStatus(1);
                if (e.getBalance() == null) {
                    e.setBalance(java.math.BigDecimal.ZERO);
                }
                // P1-5 密码处理:未提供则使用默认密码 12345678
                boolean usedDefault = false;
                String pwd = e.getPassword();
                if (pwd == null || pwd.isBlank()) {
                    pwd = generateDefaultPassword();
                    usedDefault = true;
                }
                if (!pwd.startsWith("$2a$") && !pwd.startsWith("$2b$") && !pwd.startsWith("$2y$")) {
                    // P2-1 密码复杂度校验:仅对用户显式提供的密码校验(默认密码跳过)
                    if (!usedDefault) {
                        PasswordValidator.validate(pwd);
                    } else if (pwd.length() < 8) {
                        throw new BusinessException("密码至少8位");
                    }
                    pwd = passwordEncoder.encode(pwd);
                }
                e.setPassword(pwd);
                e.setMustChangePassword(usedDefault ? 1 : 0);
                employeeMapper.insert(e);
                created++;
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
        result.put("created", created);
        result.put("updated", updated);
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
     * 按阈值批量充值:给指定门店余额低于阈值的员工充值指定金额。
     * 用于余额预警名单的批量充值。
     */
    @Transactional
    public Map<String, Object> rechargeLowBalance(Long storeId, BigDecimal threshold, BigDecimal amount) {
        SecurityContext.checkStoreAccess(storeId);
        if (threshold == null) {
            throw new BusinessException("阈值不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("充值金额必须大于0");
        }
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getIsDeleted, 0)
                .lt(Employee::getBalance, threshold));
        Long adminId = SecurityContext.currentAdminId();
        String operatorLabel = adminId != null ? "余额充值(admin#" + adminId + ")" : "余额充值";
        int successCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Employee e : employees) {
            BigDecimal balanceBefore = e.getBalance() == null ? BigDecimal.ZERO : e.getBalance();
            employeeMapper.addBalance(e.getId(), amount);
            Employee updated = employeeMapper.selectById(e.getId());
            BigDecimal balanceAfter = updated != null && updated.getBalance() != null
                    ? updated.getBalance() : balanceBefore.add(amount);
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
     * 批量重置密码:把指定员工 ID 列表的密码重置为默认密码 12345678,
     * 并标记 must_change_password=1(首次登录强制修改),同时刷新 password_updated_at 使旧 token 失效。
     * 会校验所有员工归属当前门店,防止跨门店越权。
     */
    @Transactional
    public Map<String, Object> resetPasswords(Long storeId, List<Long> employeeIds) {
        SecurityContext.checkStoreAccess(storeId);
        if (employeeIds == null || employeeIds.isEmpty()) {
            throw new BusinessException("未选择员工");
        }
        List<Employee> employees = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .in(Employee::getId, employeeIds)
                .eq(Employee::getIsDeleted, 0));
        // 校验归属:所有员工必须属于当前门店
        for (Employee e : employees) {
            if (!storeId.equals(e.getStoreId())) {
                throw new BusinessException("无权操作员工: " + e.getName());
            }
        }
        String encodedPwd = passwordEncoder.encode(DEFAULT_PASSWORD);
        int count = 0;
        for (Employee e : employees) {
            e.setPassword(encodedPwd);
            e.setMustChangePassword(1);
            e.setPasswordUpdatedAt(LocalDateTime.now());
            employeeMapper.updateById(e);
            count++;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("successCount", count);
        return result;
    }

    /**
     * B11 软删除:MyBatis Plus 全局逻辑删除配置下,deleteById 自动执行 UPDATE SET is_deleted=1。
     * 注意:不能手动 setIsDeleted(1)+updateById,因为逻辑删除字段被全局配置托管,
     * updateById 的 SET 子句会跳过 is_deleted 字段,导致删除无效。
     */
    public void deleteEmployee(Long id) {
        Employee employee = employeeMapper.selectById(id);
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        SecurityContext.checkStoreAccess(employee.getStoreId());
        employeeMapper.deleteById(id);
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

    /** 简易员工 VO(不含密码) */
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
