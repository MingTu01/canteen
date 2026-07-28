package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.EmployeeVO;
import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Department;
import com.example.canteen.entity.Employee;
import com.example.canteen.entity.Store;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.DepartmentMapper;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.mapper.StoreMapper;
import com.example.canteen.security.JwtAuthenticationFilter;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.LoginRateLimiter;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.EmployeeService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端(X86 订餐机/取餐机)绑定接口。
 *
 * 绑定流程:终端配置程序收集「域名 + 管理员账号 + 管理员密码 + 食堂安全码」,
 * 调用 /api/terminal/bind 完成绑定。后端双重校验:
 *   1. 管理员账号密码正确(必须是 status=1 的有效管理员)
 *   2. 食堂安全码匹配某个 store
 *   3. 该管理员的 storeId 必须与安全码对应的 storeId 一致
 * 校验通过后签发终端专用 token(role=3,storeId 锁定)。
 *
 * 绑定后终端拿 token 调用门店业务接口,所有数据自动隔离到该 storeId。
 * 超管在"食堂管理"页可重置安全码,重置后旧终端下次启动校验失败需重新绑定。
 */
@RestController
@RequestMapping("/api/terminal")
public class TerminalController {
    private final AdminMapper adminMapper;
    private final StoreMapper storeMapper;
    private final EmployeeMapper employeeMapper;
    private final DepartmentMapper departmentMapper;
    private final EmployeeService employeeService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    public TerminalController(AdminMapper adminMapper, StoreMapper storeMapper,
                              EmployeeMapper employeeMapper, DepartmentMapper departmentMapper,
                              EmployeeService employeeService,
                              JwtTokenProvider jwtTokenProvider, PasswordEncoder passwordEncoder,
                              JwtAuthenticationFilter jwtFilter) {
        this.adminMapper = adminMapper;
        this.storeMapper = storeMapper;
        this.employeeMapper = employeeMapper;
        this.departmentMapper = departmentMapper;
        this.employeeService = employeeService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = jwtFilter.getRateLimiter();
    }

    /**
     * 终端绑定:管理员账号密码 + 食堂安全码 → 终端 token。
     * 请求体:{ username, password, securityCode, deviceLabel? }
     * 返回:{ token, storeId, storeName, deviceLabel }
     */
    @PostMapping("/bind")
    public ApiResponse<Map<String, Object>> bind(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String securityCode = body.get("securityCode");
        String deviceLabel = body.get("deviceLabel");

        if (username == null || username.isBlank()
                || password == null || password.isBlank()
                || securityCode == null || securityCode.isBlank()) {
            throw new BusinessException("用户名、密码、食堂安全码均不能为空");
        }

        // 限流:用 username 作为限流键
        rateLimiter.checkLocked("terminal:" + username);

        // 1. 校验管理员账号密码
        Admin admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getUsername, username)
                .eq(Admin::getStatus, 1));
        if (admin == null || !passwordEncoder.matches(password, admin.getPassword())) {
            rateLimiter.recordFail("terminal:" + username);
            throw new SecurityException(SecurityException.UNAUTHORIZED, "管理员账号或密码错误");
        }
        rateLimiter.recordSuccess("terminal:" + username);

        // 2. 校验食堂安全码
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .eq(Store::getSecurityCode, securityCode)
                .eq(Store::getStatus, 1));
        if (store == null) {
            throw new BusinessException("食堂安全码无效");
        }

        // 3. 双重校验:管理员 storeId 必须与安全码对应门店一致
        //    超管(storeId=null)可以绑定任意门店;门店管理员只能绑定自己门店
        if (admin.getRole() != 1) {
            if (admin.getStoreId() == null || !admin.getStoreId().equals(store.getId())) {
                throw new BusinessException("该管理员无权绑定此食堂(门店不匹配)");
            }
        }

        // 4. 签发终端 token
        String token = jwtTokenProvider.generateTerminalToken(store.getId(), store.getName(), deviceLabel);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("storeId", store.getId());
        result.put("storeName", store.getName());
        result.put("deviceLabel", deviceLabel == null ? "" : deviceLabel);
        return ApiResponse.success(result);
    }

    /**
     * 刷新终端 token:用当前有效的终端 token 换取新 token(滚动续期)。
     * 新 token 的过期时间重新计算(默认 365 天),storeName 从 DB 实时查询。
     * 用于 token 即将过期前主动续期,避免终端频繁失绑。
     */
    @GetMapping("/refresh")
    public ApiResponse<Map<String, Object>> refreshToken() {
        Long storeId = SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }
        Store store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new BusinessException("食堂不存在");
        }
        String newToken = jwtTokenProvider.refreshTerminalToken(storeId, store.getName(), "");
        Map<String, Object> result = new HashMap<>();
        result.put("token", newToken);
        result.put("storeId", storeId);
        result.put("storeName", store.getName());
        return ApiResponse.success(result);
    }

    /**
     * 终端本店员工精简列表(用于终端测试面板模拟刷卡)。
     * 用终端 token(role=3)调用,从 token 取 storeId,只返回本店员工。
     * 返回字段仅含 id/cardNo/name,不含 phone 等敏感 PII。
     */
    @GetMapping("/employees")
    public ApiResponse<List<Map<String, Object>>> listStoreEmployees() {
        Long storeId = SecurityContext.currentStoreId();
        // 临时调试日志:排查浏览器端返回空数组问题(定位后移除)
        System.out.println("[DEBUG /terminal/employees] storeId=" + storeId
                + ", role=" + SecurityContext.currentRole()
                + ", adminId=" + SecurityContext.currentAdminId());
        if (storeId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }
        List<Employee> employees = employeeMapper.selectList(
                new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getStoreId, storeId)
                        .eq(Employee::getIsDeleted, 0)
                        .orderByAsc(Employee::getId)
        );
        System.out.println("[DEBUG /terminal/employees] query storeId=" + storeId
                + ", found " + employees.size() + " employees");
        List<Map<String, Object>> result = employees.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("cardNo", e.getCardNo());
            m.put("name", e.getName());
            return m;
        }).collect(java.util.stream.Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * 终端刷卡识别员工:用终端 token(role=3)调用,从 token 取 storeId 查本店员工。
     * 不签发员工 token,终端后续业务调用仍用终端 token(storeId 已锁定)。
     * 路径变量 cardNo 为员工卡号(读卡器读取)。
     * 返回:EmployeeVO(含 id/name/balance/departmentId 等,用于终端展示与下单)
     */
    @GetMapping("/employee/{cardNo}")
    public ApiResponse<EmployeeVO> identifyEmployee(@PathVariable String cardNo) {
        // 终端 token 的 storeId 即绑定食堂,只能查本店员工
        Long storeId = SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }
        Employee employee = employeeService.getEmployeeByCardNoAndStore(cardNo, storeId);
        if (employee == null) {
            throw new BusinessException("卡号不存在或非本食堂员工");
        }
        EmployeeVO vo = EmployeeVO.from(employee);
        // 填充 departmentName(前端取餐页展示依赖)
        if (employee.getDepartmentId() != null) {
            Department dept = departmentMapper.selectById(employee.getDepartmentId());
            if (dept != null) {
                vo.setDepartmentName(dept.getName());
            }
        }
        return ApiResponse.success(vo);
    }
}
