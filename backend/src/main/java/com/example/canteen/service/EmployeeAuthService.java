package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.entity.Employee;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.LoginRateLimiter;
import com.example.canteen.security.PasswordValidator;
import com.example.canteen.security.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 员工鉴权服务:登录 / 改密 / 二维码。
 * 负责查员工 → 校验密码 → 生成 token 等业务逻辑;
 * 登录限流(LoginRateLimiter)和 Cookie 写入由 Controller 处理。
 */
@Service
public class EmployeeAuthService {
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginRateLimiter rateLimiter;

    public EmployeeAuthService(EmployeeMapper employeeMapper,
                               PasswordEncoder passwordEncoder,
                               JwtTokenProvider jwtTokenProvider,
                               LoginRateLimiter rateLimiter) {
        this.employeeMapper = employeeMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 卡号 + 密码登录。
     * 流程:查员工(cardNo + storeId,status=1,未删除)→ 校验密码 → 生成 token。
     * 参数校验(门店/卡号非空)由 Controller 完成,以保证限流调用顺序与原实现一致。
     *
     * @return 登录结果;失败时 success=false 并携带 errorMessage
     */
    public LoginResult login(String cardNo, String password, Long storeId) {
        Employee employee = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getCardNo, cardNo)
                .eq(Employee::getStoreId, storeId)
                .eq(Employee::getStatus, 1)
                .eq(Employee::getIsDeleted, 0));
        return authenticate(employee, password, "卡号或密码错误");
    }

    /**
     * 手机号 + 密码登录(H5/小程序)。
     * phone 全局唯一,后端自动定位门店。
     * 参数校验(手机号/密码非空)由 Controller 完成。
     *
     * @return 登录结果;失败时 success=false 并携带 errorMessage
     */
    public LoginResult phoneLogin(String phone, String password) {
        Employee employee = employeeMapper.selectByPhone(phone);
        return authenticate(employee, password, "手机号或密码错误");
    }

    /**
     * 通用认证流程:校验密码 → 生成 token。login/phoneLogin 共用,消除重复。
     */
    private LoginResult authenticate(Employee employee, String password, String failMessage) {
        if (employee == null || employee.getPassword() == null
                || !passwordEncoder.matches(password == null ? "" : password, employee.getPassword())) {
            return LoginResult.fail(failMessage);
        }
        String token = jwtTokenProvider.generateEmployeeToken(employee);
        return LoginResult.success(token, employee);
    }

    /**
     * 员工修改自己的密码:需校验原密码,新密码至少 8 位。
     * 仅员工本人可改;改成功后同步更新 passwordUpdatedAt,使其他设备的旧 token 在 5 秒宽限期后失效,
     * 同时签发新 token 返回(当前会话用新 token 不中断;Controller 负责写入 Cookie 覆盖旧 token)。
     */
    public String changePassword(Long employeeId, String oldPassword, String newPassword) {
        Long currentId = SecurityContext.currentEmployeeId();
        if (currentId == null) {
            throw new com.example.canteen.exception.SecurityException("未登录");
        }
        if (!currentId.equals(employeeId)) {
            throw new com.example.canteen.exception.SecurityException("无权修改他人密码");
        }
        // 限流 key:基于员工 ID,防止原密码暴力枚举
        String rateLimitKey = "pwd:emp:" + employeeId;
        rateLimiter.checkLocked(rateLimitKey);

        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        // 已设置过密码:必须校验原密码
        if (employee.getPassword() != null && !employee.getPassword().isBlank()) {
            if (oldPassword == null || !passwordEncoder.matches(oldPassword, employee.getPassword())) {
                rateLimiter.recordFail(rateLimitKey);
                throw new BusinessException("原密码错误");
            }
        }
        if (newPassword == null) {
            throw new BusinessException("新密码不能为空");
        }
        // P2-1 密码复杂度校验(员工修改密码时必须满足复杂度要求)
        PasswordValidator.validate(newPassword);
        employee.setPassword(passwordEncoder.encode(newPassword));
        // 同步更新密码修改时间,使旧 token 失效(JwtAuthenticationFilter 校验 iat < passwordUpdatedAt)
        employee.setPasswordUpdatedAt(LocalDateTime.now());
        // 清除"必须修改密码"标志
        employee.setMustChangePassword(0);
        employeeMapper.updateById(employee);
        rateLimiter.recordSuccess(rateLimitKey);
        // 签发新 token 返回:iat 与 passwordUpdatedAt 同时刻,满足 PasswordFreshnessValidator 校验,
        // 当前会话不中断;其他设备仍持有旧 token(iat 早于 passwordUpdatedAt)会被拒,安全不丢。
        return jwtTokenProvider.generateEmployeeToken(employee);
    }

    /**
     * 生成当前登录员工的身份二维码内容(供取餐终端扫码,等同刷卡)。
     * 含 HMAC-SHA256 签名防伪,7 天有效期。
     *
     * @return 二维码字段 Map;员工不存在时返回 null
     */
    public Map<String, Object> generateQrcode(Long employeeId) {
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            return null;
        }
        long expire = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000;
        String sign = jwtTokenProvider.generateQrcodeSign(employee.getCardNo(),
                employee.getStoreId(), employee.getId(), expire);
        Map<String, Object> qrcode = new HashMap<>();
        qrcode.put("cardNo", employee.getCardNo());
        qrcode.put("storeId", employee.getStoreId());
        qrcode.put("employeeId", employee.getId());
        qrcode.put("name", employee.getName());
        qrcode.put("expire", expire);
        qrcode.put("sign", sign);
        return qrcode;
    }

    /**
     * 登录结果:成功时携带 token + employee;失败时携带 errorMessage。
     */
    public static class LoginResult {
        private final boolean success;
        private final String token;
        private final Employee employee;
        private final String errorMessage;

        private LoginResult(boolean success, String token, Employee employee, String errorMessage) {
            this.success = success;
            this.token = token;
            this.employee = employee;
            this.errorMessage = errorMessage;
        }

        public static LoginResult success(String token, Employee employee) {
            return new LoginResult(true, token, employee, null);
        }

        public static LoginResult fail(String errorMessage) {
            return new LoginResult(false, null, null, errorMessage);
        }

        public boolean isSuccess() { return success; }
        public String getToken() { return token; }
        public Employee getEmployee() { return employee; }
        public String getErrorMessage() { return errorMessage; }
    }
}
