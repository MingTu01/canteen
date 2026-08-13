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
import com.example.canteen.service.PayCodeService;
import com.example.canteen.entity.DiningTimeSlot;
import com.example.canteen.service.DiningTimeSlotService;
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
    private final PayCodeService payCodeService;
    private final DiningTimeSlotService diningTimeSlotService;

    public TerminalController(AdminMapper adminMapper, StoreMapper storeMapper,
                              EmployeeMapper employeeMapper, DepartmentMapper departmentMapper,
                              EmployeeService employeeService,
                              JwtTokenProvider jwtTokenProvider, PasswordEncoder passwordEncoder,
                              JwtAuthenticationFilter jwtFilter,
                              PayCodeService payCodeService,
                              DiningTimeSlotService diningTimeSlotService) {
        this.adminMapper = adminMapper;
        this.storeMapper = storeMapper;
        this.employeeMapper = employeeMapper;
        this.departmentMapper = departmentMapper;
        this.employeeService = employeeService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = jwtFilter.getRateLimiter();
        this.payCodeService = payCodeService;
        this.diningTimeSlotService = diningTimeSlotService;
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
        // 仅终端 token(role=3)可续期:员工 token(role=0)同样携带 storeId,
        // 若不校验角色,员工可换取 365 天终端 token,脱离员工生命周期管控(离职/禁用失效)
        Integer role = SecurityContext.currentRole();
        if (role == null || role != 3) {
            throw new SecurityException(SecurityException.FORBIDDEN, "仅终端设备可刷新终端令牌");
        }
        Long storeId = SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }
        Store store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new BusinessException("食堂不存在");
        }
        String newToken = jwtTokenProvider.refreshTerminalToken(storeId, store.getName(),
                SecurityContext.currentDeviceLabel() != null ? SecurityContext.currentDeviceLabel() : "");
        Map<String, Object> result = new HashMap<>();
        result.put("token", newToken);
        result.put("storeId", storeId);
        result.put("storeName", store.getName());
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
        // 仅终端 token(role=3)可刷卡识别:员工 token 也带 storeId,
        // 若不校验角色,员工可遍历本店卡号刺探同事姓名/余额/部门(卡号常为连号)
        Integer role = SecurityContext.currentRole();
        if (role == null || role != 3) {
            throw new SecurityException(SecurityException.FORBIDDEN, "仅终端设备可识别员工");
        }
        // 终端 token 的 storeId 即绑定食堂,只能查本店员工
        Long storeId = SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }
        Employee employee = findEmployeeByCardNo(cardNo, storeId);
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

    /**
     * 多格式卡号匹配:兼容 USB 读卡器(OUR_IDR.dll)和 HID 键盘模拟读卡器。
     * 依次尝试:
     *   1. 精确匹配
     *   2. 去除前导零后匹配
     *   3. 仅保留数字后匹配(HID 可能输出带分隔符的 WG26 格式)
     *   4. 取后10位匹配(HID 可能输出更长的卡号)
     *   5. 取后8位匹配
     */
    private Employee findEmployeeByCardNo(String cardNo, Long storeId) {
        if (cardNo == null || cardNo.isBlank()) return null;
        String trimmed = cardNo.trim();

        // 1. 精确匹配
        Employee emp = employeeService.getEmployeeByCardNoAndStore(trimmed, storeId);
        if (emp != null) return emp;

        // 2. 去除前导零后匹配
        String noLeadingZeros = trimmed.replaceFirst("^0+", "");
        if (!noLeadingZeros.isEmpty() && !noLeadingZeros.equals(trimmed)) {
            emp = employeeService.getEmployeeByCardNoAndStore(noLeadingZeros, storeId);
            if (emp != null) return emp;
        }

        // 3. 仅保留数字后匹配(HID 可能输出带分隔符的 WG26 格式,如 "123;45678")
        String digitsOnly = trimmed.replaceAll("[^0-9]", "");
        if (!digitsOnly.isEmpty() && !digitsOnly.equals(trimmed)) {
            emp = employeeService.getEmployeeByCardNoAndStore(digitsOnly, storeId);
            if (emp != null) return emp;
            String digitsNoZeros = digitsOnly.replaceFirst("^0+", "");
            if (!digitsNoZeros.isEmpty() && !digitsNoZeros.equals(digitsOnly)) {
                emp = employeeService.getEmployeeByCardNoAndStore(digitsNoZeros, storeId);
                if (emp != null) return emp;
            }
        }

        // 4. 取后10位匹配(HID 可能输出更长的卡号)
        if (digitsOnly.length() > 10) {
            emp = employeeService.getEmployeeByCardNoAndStore(
                    digitsOnly.substring(digitsOnly.length() - 10), storeId);
            if (emp != null) return emp;
        }

        // 5. 取后8位匹配
        if (digitsOnly.length() > 8) {
            emp = employeeService.getEmployeeByCardNoAndStore(
                    digitsOnly.substring(digitsOnly.length() - 8), storeId);
            if (emp != null) return emp;
        }

        return null;
    }

    /**
     * 批量获取本食堂所有启用员工(终端启动时全量缓存用)。
     *
     * 返回精简版员工列表(仅刷卡识别所需字段):
     * id / cardNo / name / avatar / departmentId / departmentName / balance / status
     * 不含手机号、密码等敏感字段。
     *
     * 终端启动时调用此接口缓存全量员工到 IndexedDB,
     * 刷卡时优先查本地缓存(毫秒级),未命中再走 /employee/{cardNo} 网络查询。
     * 员工头像也同步预加载到 IndexedDB,避免刷卡时下载头像。
     */
    @GetMapping("/employees")
    public ApiResponse<java.util.List<EmployeeVO>> listStoreEmployees() {
        Integer role = SecurityContext.currentRole();
        if (role == null || role != 3) {
            throw new SecurityException(SecurityException.FORBIDDEN, "仅终端设备可批量获取员工");
        }
        Long storeId = SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }
        List<Employee> employees = employeeService.getEmployeesByStore(storeId);
        // 预加载部门映射
        Map<Long, String> deptMap = new HashMap<>();
        List<Department> depts = departmentMapper.selectByStoreId(storeId);
        if (depts != null) {
            for (Department d : depts) {
                deptMap.put(d.getId(), d.getName());
            }
        }
        List<EmployeeVO> voList = employees.stream()
                .map(e -> {
                    EmployeeVO vo = EmployeeVO.from(e);
                    if (e.getDepartmentId() != null) {
                        vo.setDepartmentName(deptMap.get(e.getDepartmentId()));
                    }
                    return vo;
                })
                .toList();
        return ApiResponse.success(voList);
    }

    /**
     * 终端扫码验证员工身份二维码(H5「我的」页生成)。
     * 请求体即二维码 JSON 内容:{ cardNo, storeId, employeeId, name, expire, sign }
     * 校验顺序(全部通过才放行):
     *   1. 仅终端 token(role=3),fail-closed,防止员工/管理员拿他人二维码查余额
     *   2. 门店隔离:二维码 storeId 必须与终端绑定门店一致(跨食堂二维码不可用)
     *   3. 常量时间比对 HMAC-SHA256 签名(签名已含 storeId,失败即伪码)
     *   4. 有效期:expire 必须晚于当前时间
     *   5. 员工存在且启用(selectByCardNoAndStore 已过滤 status=1 / is_deleted=0)
     * 返回 EmployeeVO(含 departmentName),与刷卡识别返回结构一致。
     */
    @PostMapping("/verify-qrcode")
    public ApiResponse<EmployeeVO> verifyQrcode(@RequestBody Map<String, Object> body) {
        // 1. 仅终端可验证(员工/管理员 token 均带 storeId,必须校验角色防越权)
        Integer role = SecurityContext.currentRole();
        if (role == null || role != 3) {
            throw new SecurityException(SecurityException.FORBIDDEN, "仅终端设备可验证员工二维码");
        }
        Long terminalStoreId = SecurityContext.currentStoreId();
        if (terminalStoreId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }

        // 提取二维码字段
        String cardNo = strVal(body.get("cardNo"));
        Long qrStoreId = longVal(body.get("storeId"));
        Long employeeId = longVal(body.get("employeeId"));
        Long expire = longVal(body.get("expire"));
        String sign = strVal(body.get("sign"));
        if (cardNo == null || cardNo.isBlank() || qrStoreId == null
                || employeeId == null || expire == null || sign == null || sign.isBlank()) {
            throw new BusinessException("二维码内容不完整");
        }

        // 2. 门店隔离:二维码必须属于终端绑定的食堂
        if (!qrStoreId.equals(terminalStoreId)) {
            throw new BusinessException("二维码不属于本食堂");
        }

        // 3. 验签(常量时间比较,防伪码伪造)
        if (!jwtTokenProvider.verifyQrcodeSign(cardNo, qrStoreId, employeeId, expire, sign)) {
            throw new BusinessException("二维码签名校验失败");
        }

        // 4. 有效期
        if (expire <= System.currentTimeMillis()) {
            throw new BusinessException("二维码已过期,请刷新后重试");
        }

        // 5. 员工存在且启用(selectByCardNoAndStore 过滤 status=1 / is_deleted=0)
        Employee employee = employeeService.getEmployeeByCardNoAndStore(cardNo, terminalStoreId);
        if (employee == null || !employee.getId().equals(employeeId)) {
            throw new BusinessException("员工不存在或已失效");
        }

        EmployeeVO vo = EmployeeVO.from(employee);
        if (employee.getDepartmentId() != null) {
            Department dept = departmentMapper.selectById(employee.getDepartmentId());
            if (dept != null) {
                vo.setDepartmentName(dept.getName());
            }
        }
        return ApiResponse.success(vo);
    }

    /**
     * 终端扫码验证一次性支付码(H5「我的」页生成)。
     *
     * 支付码方案:32 位随机 hex 码,5 分钟有效,Redis GETDEL 原子操作核销即失效。
     * 防截图重放:核销后 Redis key 已删除,同一码无法二次使用。
     *
     * 请求体:{ "code": "32位hex支付码" }
     * 返回:EmployeeVO(与刷卡识别/身份二维码验签返回结构一致,前端可无缝复用)
     */
    @PostMapping("/verify-paycode")
    public ApiResponse<EmployeeVO> verifyPayCode(@RequestBody Map<String, Object> body) {
        String code = strVal(body.get("code"));
        if (code == null || code.isBlank()) {
            throw new BusinessException("支付码不能为空");
        }
        EmployeeVO vo = payCodeService.verifyPayCode(code);
        return ApiResponse.success(vo);
    }

    /**
     * 获取本食堂的就餐时段配置(终端用于取餐时段校验和展示)。
     *
     * 终端启动时和刷卡取餐前调用,缓存到本地:
     * - 展示真实时段文字(如"早餐 07:00-10:00")
     * - 判定当前时间是否在就餐时段内(空档期拒绝取餐,提示"未到用餐时间")
     * - 识别当前时段对应的餐次(避免午餐时段核销早餐订单的严重 BUG)
     *
     * 返回当前门店所有餐次的时段配置列表(mealType/startTime/endTime)。
     */
    @GetMapping("/meal-slots")
    public ApiResponse<List<DiningTimeSlot>> getMealSlots() {
        Integer role = SecurityContext.currentRole();
        if (role == null || role != 3) {
            throw new SecurityException(SecurityException.FORBIDDEN, "仅终端设备可获取就餐时段");
        }
        Long storeId = SecurityContext.currentStoreId();
        if (storeId == null) {
            throw new SecurityException(SecurityException.FORBIDDEN, "终端未绑定食堂");
        }
        return ApiResponse.success(diningTimeSlotService.getTimeSlotsByStore(storeId));
    }

    private static String strVal(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long longVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
