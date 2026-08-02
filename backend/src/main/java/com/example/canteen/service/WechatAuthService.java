package com.example.canteen.service;

import com.example.canteen.entity.Employee;
import com.example.canteen.mapper.EmployeeMapper;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.LoginRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信公众号网页授权登录服务。
 *
 * 流程:
 * 1. 前端调用 getAuthUrl 获取微信授权 URL,重定向到微信
 * 2. 微信回调 H5 带 code 参数,前端调用 loginByCode(code)
 * 3. 后端用 code 换取 openid:
 *    - openid 已绑定员工 → 直接登录返回 token
 *    - openid 未绑定 → 返回 bindToken(临时凭证),前端弹窗输入手机号+密码
 * 4. 前端调用 bindByPhoneAndPassword(bindToken, phone, password) 完成绑定并登录
 *
 * 配置(环境变量,未配置时微信登录功能不可用):
 * - WECHAT_APP_ID:公众号 AppID
 * - WECHAT_APP_SECRET:公众号 AppSecret
 *
 * 使用 snsapi_base 静默授权(仅获取 openid,无需用户确认),适合内部企业食堂场景。
 */
@Service
public class WechatAuthService {
    private static final Logger log = LoggerFactory.getLogger(WechatAuthService.class);

    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginRateLimiter rateLimiter;
    private final RestTemplate restTemplate;

    @Value("${wechat.app-id:}")
    private String appId;

    @Value("${wechat.app-secret:}")
    private String appSecret;

    /** 微信授权码换 openid 的临时绑定令牌缓存(bindToken → openid),5 分钟过期 */
    private final Map<String, BindEntry> bindTokenCache = new ConcurrentHashMap<>();
    private static final long BIND_TOKEN_TTL_MS = 5L * 60 * 1000;

    public WechatAuthService(EmployeeMapper employeeMapper,
                             PasswordEncoder passwordEncoder,
                             JwtTokenProvider jwtTokenProvider,
                             LoginRateLimiter rateLimiter) {
        this.employeeMapper = employeeMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.rateLimiter = rateLimiter;
        this.restTemplate = new RestTemplate();
    }

    /** 微信是否已配置(AppID 和 AppSecret 均非空) */
    public boolean isConfigured() {
        return appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /**
     * 生成微信网页授权 URL。
     * 使用 snsapi_base 静默授权,用户无感知,仅获取 openid。
     *
     * @param redirectUri 授权后回调的 H5 地址(需与公众号配置的网页授权域名一致)
     * @return 微信授权 URL,前端直接 window.location.href 跳转
     */
    public String getAuthUrl(String redirectUri) {
        if (!isConfigured()) {
            throw new IllegalStateException("微信登录未配置,请设置 WECHAT_APP_ID 和 WECHAT_APP_SECRET");
        }
        String encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        String state = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "https://open.weixin.qq.com/connect/oauth2/authorize"
                + "?appid=" + appId
                + "&redirect_uri=" + encodedRedirect
                + "&response_type=code"
                + "&scope=snsapi_base"
                + "&state=" + state
                + "#wechat_redirect";
    }

    /**
     * 用微信授权 code 换取 openid,尝试登录。
     *
     * @return 登录结果:
     *   - success=true:openid 已绑定,直接登录成功
     *   - success=false, needBind=true:openid 未绑定,返回 bindToken 供前端绑定
     *   - success=false, errorMessage:code 无效或微信 API 异常
     */
    public WechatLoginResult loginByCode(String code) {
        String openid = exchangeCodeForOpenid(code);
        if (openid == null) {
            return WechatLoginResult.fail("微信授权失败,请重试");
        }

        Employee employee = employeeMapper.selectByWxOpenid(openid);
        if (employee == null) {
            // openid 未绑定,生成临时绑定令牌
            String bindToken = UUID.randomUUID().toString().replace("-", "");
            bindTokenCache.put(bindToken, new BindEntry(openid, System.currentTimeMillis()));
            return WechatLoginResult.needBind(bindToken);
        }

        String token = jwtTokenProvider.generateEmployeeToken(employee);
        return WechatLoginResult.success(token, employee);
    }

    /**
     * 绑定微信 openid 到员工账号(通过手机号+密码验证身份)。
     * 绑定成功后自动登录。
     *
     * @param bindToken loginByCode 返回的绑定令牌(5 分钟有效)
     * @param phone 员工手机号
     * @param password 员工密码
     * @return 登录结果
     */
    public EmployeeAuthService.LoginResult bindByPhoneAndPassword(String bindToken, String phone, String password) {
        // 校验 bindToken 有效性
        BindEntry entry = bindTokenCache.get(bindToken);
        if (entry == null || System.currentTimeMillis() - entry.createdAt > BIND_TOKEN_TTL_MS) {
            bindTokenCache.remove(bindToken);
            return EmployeeAuthService.LoginResult.fail("绑定令牌已过期,请重新发起微信登录");
        }
        String openid = entry.openid;

        // 限流:以手机号为 key
        String lockKey = "wxbind:" + phone;
        rateLimiter.checkLocked(lockKey);

        // 验证手机号+密码
        Employee employee = employeeMapper.selectByPhone(phone);
        if (employee == null || employee.getPassword() == null
                || !passwordEncoder.matches(password == null ? "" : password, employee.getPassword())) {
            rateLimiter.recordFail(lockKey);
            return EmployeeAuthService.LoginResult.fail("手机号或密码错误");
        }

        // 检查该 openid 是否已绑定其他员工(避免重复绑定)
        Employee existing = employeeMapper.selectByWxOpenid(openid);
        if (existing != null && !existing.getId().equals(employee.getId())) {
            rateLimiter.recordSuccess(lockKey);
            return EmployeeAuthService.LoginResult.fail("该微信号已绑定其他员工账号");
        }

        // 绑定 openid 到当前员工
        employee.setWxOpenid(openid);
        employeeMapper.updateById(employee);

        // 清理绑定令牌
        bindTokenCache.remove(bindToken);
        rateLimiter.recordSuccess(lockKey);

        log.info("员工 {}({}) 绑定微信 openid 成功", employee.getName(), employee.getPhone());
        String token = jwtTokenProvider.generateEmployeeToken(employee);
        return EmployeeAuthService.LoginResult.success(token, employee);
    }

    /**
     * 用授权 code 换取 openid(调用微信 API)。
     * code 为一次性,5 分钟有效,只能使用一次。
     */
    @SuppressWarnings("unchecked")
    private String exchangeCodeForOpenid(String code) {
        if (!isConfigured()) {
            log.warn("微信登录未配置 AppID/AppSecret,无法换取 openid");
            return null;
        }
        String url = "https://api.weixin.qq.com/sns/oauth2/access_token"
                + "?appid=" + appId
                + "&secret=" + appSecret
                + "&code=" + code
                + "&grant_type=authorization_code";
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = resp.getBody();
            if (body == null) {
                log.error("微信 API 返回空响应");
                return null;
            }
            // 检查错误码
            Object errcode = body.get("errcode");
            if (errcode != null && !"".equals(errcode.toString()) && !"0".equals(errcode.toString())) {
                log.error("微信 API 返回错误: errcode={}, errmsg={}", errcode, body.get("errmsg"));
                return null;
            }
            Object openid = body.get("openid");
            return openid == null ? null : openid.toString();
        } catch (Exception e) {
            log.error("调用微信 API 换取 openid 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 绑定令牌缓存条目 */
    private static class BindEntry {
        final String openid;
        final long createdAt;

        BindEntry(String openid, long createdAt) {
            this.openid = openid;
            this.createdAt = createdAt;
        }
    }

    /** 微信登录结果(含"需要绑定"中间态) */
    public static class WechatLoginResult {
        private final boolean success;
        private final boolean needBind;
        private final String token;
        private final Employee employee;
        private final String bindToken;
        private final String errorMessage;

        private WechatLoginResult(boolean success, boolean needBind, String token,
                                  Employee employee, String bindToken, String errorMessage) {
            this.success = success;
            this.needBind = needBind;
            this.token = token;
            this.employee = employee;
            this.bindToken = bindToken;
            this.errorMessage = errorMessage;
        }

        public static WechatLoginResult success(String token, Employee employee) {
            return new WechatLoginResult(true, false, token, employee, null, null);
        }

        public static WechatLoginResult needBind(String bindToken) {
            return new WechatLoginResult(false, true, null, null, bindToken, null);
        }

        public static WechatLoginResult fail(String errorMessage) {
            return new WechatLoginResult(false, false, null, null, null, errorMessage);
        }

        public boolean isSuccess() { return success; }
        public boolean isNeedBind() { return needBind; }
        public String getToken() { return token; }
        public Employee getEmployee() { return employee; }
        public String getBindToken() { return bindToken; }
        public String getErrorMessage() { return errorMessage; }
    }
}
