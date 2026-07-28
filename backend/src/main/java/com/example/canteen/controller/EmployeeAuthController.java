package com.example.canteen.controller;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.EmployeeVO;
import com.example.canteen.security.AuthCookieUtil;
import com.example.canteen.security.LoginRateLimiter;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.EmployeeAuthService;
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
    private final LoginRateLimiter rateLimiter;
    private final AuthCookieUtil authCookieUtil;

    public EmployeeAuthController(EmployeeAuthService authService,
                                  LoginRateLimiter rateLimiter,
                                  AuthCookieUtil authCookieUtil) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.authCookieUtil = authCookieUtil;
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
        authCookieUtil.setAuthCookie(httpResponse, result.getToken(), httpRequest);
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
        authCookieUtil.setAuthCookie(httpResponse, result.getToken(), httpRequest);
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
}
