package com.example.canteen.controller;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.EmployeeVO;
import com.example.canteen.entity.Employee;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.AuthCookieUtil;
import com.example.canteen.security.LoginRateLimiter;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.EmployeeAuthService;
import com.example.canteen.service.WechatAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 员工鉴权接口:登录 / 手机号登录 / 改密 / 二维码。
 * URL 前缀 /api/employee,与原 EmployeeController 保持一致,前端零改动。
 * 业务逻辑下沉到 EmployeeAuthService;Controller 仅负责参数提取、限流调用、Cookie 写入。
 */
@RestController
@RequestMapping("/api/employee")
public class EmployeeAuthController {
    private final EmployeeAuthService authService;
    private final EmployeeMapper employeeMapper;
    private final LoginRateLimiter rateLimiter;
    private final AuthCookieUtil authCookieUtil;
    private final WechatAuthService wechatAuthService;

    public EmployeeAuthController(EmployeeAuthService authService,
                                  EmployeeMapper employeeMapper,
                                  LoginRateLimiter rateLimiter,
                                  AuthCookieUtil authCookieUtil,
                                  WechatAuthService wechatAuthService) {
        this.authService = authService;
        this.employeeMapper = employeeMapper;
        this.rateLimiter = rateLimiter;
        this.authCookieUtil = authCookieUtil;
        this.wechatAuthService = wechatAuthService;
    }

    /**
     * 卡号 + 密码登录。
     * 请求体:{ cardNo, password, storeId }
     * 返回:{ token, employee }
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> loginRequest,
                                                   HttpServletRequest httpRequest,
                                                   HttpServletResponse httpResponse) {
        String cardNo = loginRequest.get("cardNo") == null ? null : String.valueOf(loginRequest.get("cardNo"));
        String password = loginRequest.get("password") == null ? null : String.valueOf(loginRequest.get("password"));
        // 卡号可能跨店重复,必须指定门店
        Long storeId = null;
        Object storeIdObj = loginRequest.get("storeId");
        if (storeIdObj != null && !"".equals(storeIdObj.toString())) {
            try {
                storeId = Long.valueOf(storeIdObj.toString());
            } catch (NumberFormatException ignore) {
                storeId = null;
            }
        }
        // 参数校验(与原实现顺序一致:先校验再限流,避免用空 key 调用限流器)
        if (storeId == null) {
            return ApiResponse.error(400, "需指定门店");
        }
        if (cardNo == null || cardNo.isBlank()) {
            return ApiResponse.error(400, "卡号不能为空");
        }
        rateLimiter.checkLocked(cardNo);

        EmployeeAuthService.LoginResult result = authService.login(cardNo, password, storeId);
        if (!result.isSuccess()) {
            rateLimiter.recordFail(cardNo);
            return ApiResponse.error(401, result.getErrorMessage());
        }
        rateLimiter.recordSuccess(cardNo);
        authCookieUtil.setEmployeeCookie(httpResponse, result.getToken(), httpRequest);
        Map<String, Object> data = new HashMap<>();
        data.put("token", result.getToken());
        data.put("employee", EmployeeVO.from(result.getEmployee()));
        return ApiResponse.success(data);
    }

    /**
     * 手机号登录(H5/小程序):手机号 + 密码 → 员工 token。
     * phone 已建全局唯一索引,后端按手机号自动定位员工所属门店,前端无需选食堂。
     * 请求体:{ phone, password }
     * 返回:{ token, employee }
     */
    @PostMapping("/phone-login")
    public ApiResponse<Map<String, Object>> phoneLogin(@RequestBody Map<String, Object> req,
                                                        HttpServletRequest httpRequest,
                                                        HttpServletResponse httpResponse) {
        String phone = req.get("phone") == null ? null : String.valueOf(req.get("phone")).trim();
        String password = req.get("password") == null ? null : String.valueOf(req.get("password"));
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.error(400, "手机号和密码不能为空");
        }

        String lockKey = "phone:" + phone;
        rateLimiter.checkLocked(lockKey);

        EmployeeAuthService.LoginResult result = authService.phoneLogin(phone, password);
        if (!result.isSuccess()) {
            rateLimiter.recordFail(lockKey);
            return ApiResponse.error(401, result.getErrorMessage());
        }
        rateLimiter.recordSuccess(lockKey);
        authCookieUtil.setEmployeeCookie(httpResponse, result.getToken(), httpRequest);
        Map<String, Object> data = new HashMap<>();
        data.put("token", result.getToken());
        data.put("employee", EmployeeVO.from(result.getEmployee()));
        return ApiResponse.success(data);
    }

    /**
     * 员工修改自己的密码:需校验原密码,新密码至少 8 位。
     * 请求体:{ oldPassword, newPassword }
     */
    @PutMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody Map<String, String> body) {
        Long employeeId = SecurityContext.currentEmployeeId();
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        authService.changePassword(employeeId, oldPassword, newPassword);
        return ApiResponse.success(null);
    }

    /**
     * 获取当前登录员工的身份二维码内容(供取餐终端扫码,等同刷卡)。
     * 返回 JSON 字符串,前端用 qrcode 库渲染成二维码图片。
     * 内容含 HMAC-SHA256 签名防伪,7 天有效期。
     */
    @GetMapping("/my-qrcode")
    public ApiResponse<Map<String, Object>> myQrcode() {
        Long employeeId = SecurityContext.currentEmployeeId();
        if (employeeId == null) {
            return ApiResponse.error(401, "未登录");
        }
        Map<String, Object> qrcode = authService.generateQrcode(employeeId);
        if (qrcode == null) {
            return ApiResponse.error(404, "员工不存在");
        }
        return ApiResponse.success(qrcode);
    }

    /**
     * 获取当前登录员工的完整信息(基于 token,无需前端传 ID)。
     * 用于 H5 刷新页面/进入"我的"页时获取最新余额等,避免依赖 localStorage 缓存的 ID。
     * 返回 EmployeeVO(不含密码等敏感字段)。
     */
    @GetMapping("/me")
    public ApiResponse<EmployeeVO> me() {
        Long employeeId = SecurityContext.currentEmployeeId();
        if (employeeId == null) {
            return ApiResponse.error(401, "未登录");
        }
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            return ApiResponse.error(404, "员工不存在");
        }
        return ApiResponse.success(EmployeeVO.from(employee));
    }

    // ==================== 微信登录 ====================

    /**
     * 获取微信网页授权 URL。
     * 前端调用后直接 window.location.href 跳转到返回的 URL,微信授权后回调到 redirect。
     * 回调时 URL query 会带 code 参数,前端应调用 /wechat/login 完成登录。
     *
     * @param redirect 授权后回调的 H5 相对路径(如 /login?wechat=1),后端拼接完整域名
     */
    @GetMapping("/wechat/auth-url")
    public ApiResponse<Map<String, Object>> wechatAuthUrl(@RequestParam(required = false) String redirect,
                                                            HttpServletRequest httpRequest) {
        if (!wechatAuthService.isConfigured()) {
            return ApiResponse.error(503, "微信登录未配置,请联系管理员设置 WECHAT_APP_ID 和 WECHAT_APP_SECRET");
        }
        // 拼接完整回调地址:协议 + 域名 + redirect(默认 /login)
        String scheme = httpRequest.getScheme();
        String host = httpRequest.getServerName();
        int port = httpRequest.getServerPort();
        String contextPath = httpRequest.getContextPath();
        String redirectPath = (redirect == null || redirect.isBlank()) ? "/login" : redirect;
        // 反向代理场景:优先使用 X-Forwarded-Proto / X-Forwarded-Host
        String xProto = httpRequest.getHeader("X-Forwarded-Proto");
        String xHost = httpRequest.getHeader("X-Forwarded-Host");
        if (xProto != null && !xProto.isBlank()) scheme = xProto;
        StringBuilder redirectUri = new StringBuilder(scheme).append("://");
        if (xHost != null && !xHost.isBlank()) {
            redirectUri.append(xHost);
        } else {
            redirectUri.append(host);
            // 非标准端口追加端口号(开发环境)
            if ((scheme.equals("http") && port != 80) || (scheme.equals("https") && port != 443)) {
                redirectUri.append(":").append(port);
            }
        }
        redirectUri.append(contextPath).append(redirectPath);

        String authUrl = wechatAuthService.getAuthUrl(redirectUri.toString());
        Map<String, Object> data = new HashMap<>();
        data.put("authUrl", authUrl);
        return ApiResponse.success(data);
    }

    /**
     * 微信授权码登录:用 code 换取 openid,已绑定则直接登录,未绑定返回 bindToken。
     * 请求体:{ code }
     * 返回:
     *   - { status: "login", token, employee }  登录成功
     *   - { status: "need_bind", bindToken }     需要绑定,前端弹窗输入手机号+密码后调用 /wechat/bind
     */
    @PostMapping("/wechat/login")
    public ApiResponse<Map<String, Object>> wechatLogin(@RequestBody Map<String, Object> req,
                                                          HttpServletRequest httpRequest,
                                                          HttpServletResponse httpResponse) {
        String code = req.get("code") == null ? null : String.valueOf(req.get("code"));
        if (code == null || code.isBlank()) {
            return ApiResponse.error(400, "授权码不能为空");
        }

        WechatAuthService.WechatLoginResult result = wechatAuthService.loginByCode(code);
        Map<String, Object> data = new HashMap<>();

        if (result.isSuccess()) {
            authCookieUtil.setEmployeeCookie(httpResponse, result.getToken(), httpRequest);
            data.put("status", "login");
            data.put("token", result.getToken());
            data.put("employee", EmployeeVO.from(result.getEmployee()));
            return ApiResponse.success(data);
        }

        if (result.isNeedBind()) {
            data.put("status", "need_bind");
            data.put("bindToken", result.getBindToken());
            return ApiResponse.success(data);
        }

        return ApiResponse.error(401, result.getErrorMessage());
    }

    /**
     * 微信绑定:通过手机号+密码验证身份,绑定 openid 后自动登录。
     * 请求体:{ bindToken, phone, password }
     * 返回:{ token, employee }
     */
    @PostMapping("/wechat/bind")
    public ApiResponse<Map<String, Object>> wechatBind(@RequestBody Map<String, Object> req,
                                                        HttpServletRequest httpRequest,
                                                        HttpServletResponse httpResponse) {
        String bindToken = req.get("bindToken") == null ? null : String.valueOf(req.get("bindToken"));
        String phone = req.get("phone") == null ? null : String.valueOf(req.get("phone")).trim();
        String password = req.get("password") == null ? null : String.valueOf(req.get("password"));

        if (bindToken == null || bindToken.isBlank()) {
            return ApiResponse.error(400, "绑定令牌不能为空");
        }
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.error(400, "手机号和密码不能为空");
        }

        EmployeeAuthService.LoginResult result = wechatAuthService.bindByPhoneAndPassword(bindToken, phone, password);
        if (!result.isSuccess()) {
            return ApiResponse.error(401, result.getErrorMessage());
        }

        authCookieUtil.setEmployeeCookie(httpResponse, result.getToken(), httpRequest);
        Map<String, Object> data = new HashMap<>();
        data.put("token", result.getToken());
        data.put("employee", EmployeeVO.from(result.getEmployee()));
        return ApiResponse.success(data);
    }
}
