package com.example.canteen.service;

import com.example.canteen.entity.Employee;
import com.example.canteen.entity.MealType;
import com.example.canteen.entity.Notification;
import com.example.canteen.entity.Order;
import com.example.canteen.mapper.EmployeeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信公众号订阅消息通知服务。
 *
 * 注意:微信已于 2021-04-30 下线旧版「模板消息」接口(message/template/send),
 * 现使用「订阅消息」接口(message/subscribe/bizsend)。
 * 订阅消息要求用户在 H5/图文内主动订阅后才能下发,每次订阅可发一条。
 *
 * 用于向已绑定微信的员工推送:
 * 1. 通知/公告/活动发布提醒
 * 2. 订单创建成功提醒(含日期、餐次)
 *
 * 实现:
 * - access_token 全局缓存(有效期 2 小时,提前 5 分钟刷新),多实例下各自缓存可接受
 *   (微信允许一定并发重复获取,影响仅是 token 互踢导致前一个失效,重试即可)
 * - 订阅消息发送失败不影响主业务(下单/发通知),仅记录日志
 * - 前置条件:用户需在 H5 端通过 wx-open-subscribe 开放标签订阅对应模板
 *
 * 配置(环境变量,未配置时方法静默跳过):
 * - WECHAT_APP_ID / WECHAT_APP_SECRET:公众号凭证(复用 WechatAuthService)
 * - WECHAT_TEMPLATE_NOTIFY:通知/公告/活动 订阅消息模板ID
 * - WECHAT_TEMPLATE_ORDER:订单创建 订阅消息模板ID
 *
 * 模板字段约定(在微信开发者平台申请模板时按此字段命名):
 * 通知模板:title / content / time
 * 订单模板:orderDate / mealType / amount / pickupCode / time
 */
@Service
public class WechatNotifyService {
    private static final Logger log = LoggerFactory.getLogger(WechatNotifyService.class);

    private final EmployeeMapper employeeMapper;
    private final RestTemplate restTemplate;

    @Value("${wechat.app-id:}")
    private String appId;

    @Value("${wechat.app-secret:}")
    private String appSecret;

    /** 通知/公告/活动 订阅消息模板ID */
    @Value("${wechat.template.notify:}")
    private String templateNotifyId;

    /** 订单创建 订阅消息模板ID */
    @Value("${wechat.template.order:}")
    private String templateOrderId;

    /** access_token 缓存:token 字符串 */
    private volatile String cachedAccessToken;
    /** access_token 过期时间戳(毫秒) */
    private volatile long tokenExpireAt = 0L;
    /** access_token 锁(防止并发重复获取) */
    private final Object tokenLock = new Object();

    /** 通知模板跳转的 H5 页面路径(如 /home),拼接完整 URL */
    @Value("${wechat.h5-base-url:}")
    private String h5BaseUrl;

    public WechatNotifyService(EmployeeMapper employeeMapper) {
        this.employeeMapper = employeeMapper;
        this.restTemplate = new RestTemplate();
    }

    /** 微信是否已配置(AppID + AppSecret) */
    public boolean isConfigured() {
        return appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /** 通知模板是否已配置 */
    public boolean isNotifyTemplateConfigured() {
        return isConfigured() && templateNotifyId != null && !templateNotifyId.isBlank();
    }

    /** 订单模板是否已配置 */
    public boolean isOrderTemplateConfigured() {
        return isConfigured() && templateOrderId != null && !templateOrderId.isBlank();
    }

    // ============================================================
    // access_token 管理
    // ============================================================

    /**
     * 获取 access_token(带缓存,提前 5 分钟刷新)。
     * 同步块防止并发重复获取(单实例足够;多实例下各自缓存可接受)。
     */
    @SuppressWarnings("unchecked")
    private String getAccessToken() {
        // 快速路径:缓存有效直接返回
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpireAt - 5 * 60 * 1000L) {
            return cachedAccessToken;
        }
        synchronized (tokenLock) {
            // 双检:进入锁后再次确认(可能已被其他线程刷新)
            now = System.currentTimeMillis();
            if (cachedAccessToken != null && now < tokenExpireAt - 5 * 60 * 1000L) {
                return cachedAccessToken;
            }
            if (!isConfigured()) {
                log.debug("微信未配置,无法获取 access_token");
                return null;
            }
            String url = "https://api.weixin.qq.com/cgi-bin/token"
                    + "?grant_type=client_credential"
                    + "&appid=" + appId
                    + "&secret=" + appSecret;
            try {
                ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
                Map<String, Object> body = resp.getBody();
                if (body == null) {
                    log.error("微信 access_token API 返回空响应");
                    return null;
                }
                Object errcode = body.get("errcode");
                if (errcode != null && !"0".equals(errcode.toString()) && !"".equals(errcode.toString())) {
                    log.error("微信 access_token 获取失败: errcode={}, errmsg={}", errcode, body.get("errmsg"));
                    return null;
                }
                String token = body.get("access_token") == null ? null : body.get("access_token").toString();
                Object expiresIn = body.get("expires_in");
                long expiresInLong = expiresIn == null ? 7200L : Long.parseLong(expiresIn.toString());
                if (token == null) {
                    log.error("微信 access_token API 未返回 access_token 字段");
                    return null;
                }
                cachedAccessToken = token;
                tokenExpireAt = now + expiresInLong * 1000L;
                log.info("微信 access_token 刷新成功,有效期 {} 秒", expiresInLong);
                return token;
            } catch (Exception e) {
                log.error("获取微信 access_token 异常: {}", e.getMessage());
                return null;
            }
        }
    }

    // ============================================================
    // 订阅消息发送
    // ============================================================

    /**
     * 发送订阅消息到指定 openid。
     * 单条发送,失败仅记录日志,不抛异常(避免影响主业务)。
     *
     * 注意:订阅消息要求用户已通过 H5 端 wx-open-subscribe 开放标签订阅过该模板,
     * 否则微信会返回 errcode=43101(用户未订阅)。
     *
     * @param openid 接收人 openid
     * @param templateId 订阅消息模板ID
     * @param data 模板数据(键为模板变量名,值为 {value: "xxx"})
     * @param url 点击跳转的 URL(可为 null)
     */
    @SuppressWarnings("unchecked")
    private void sendSubscribeMessage(String openid, String templateId, Map<String, Object> data, String url) {
        String accessToken = getAccessToken();
        if (accessToken == null) {
            return;
        }
        // 订阅消息发送接口(替代已下线的 message/template/send)
        String apiUrl = "https://api.weixin.qq.com/cgi-bin/message/subscribe/bizsend?access_token=" + accessToken;
        Map<String, Object> body = new HashMap<>();
        body.put("touser", openid);
        body.put("template_id", templateId);
        if (url != null && !url.isBlank()) {
            body.put("url", url);
        }
        body.put("data", data);

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiUrl, body, Map.class);
            Map<String, Object> result = resp.getBody();
            if (result == null) {
                log.warn("微信订阅消息发送返回空响应,openid={}", openid);
                return;
            }
            Object errcode = result.get("errcode");
            if (errcode != null && !"0".equals(errcode.toString())) {
                // 常见错误:43101(用户未订阅)、40037(模板ID无效)、40003(openid无效)
                log.warn("微信订阅消息发送失败: openid={}, errcode={}, errmsg={}",
                        openid, errcode, result.get("errmsg"));
            }
        } catch (Exception e) {
            log.warn("微信订阅消息发送异常: openid={}, error={}", openid, e.getMessage());
        }
    }

    /**
     * 构造模板数据项(value + color)。
     * color 留空使用模板默认颜色。
     */
    private Map<String, Object> dataItem(String value) {
        Map<String, Object> item = new HashMap<>();
        item.put("value", value);
        return item;
    }

    /** 拼接 H5 跳转 URL(如 /home → http://example.com/home) */
    private String buildH5Url(String path) {
        if (h5BaseUrl == null || h5BaseUrl.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        String base = h5BaseUrl.endsWith("/") ? h5BaseUrl.substring(0, h5BaseUrl.length() - 1) : h5BaseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    // ============================================================
    // 业务入口:通知发布
    // ============================================================

    /**
     * 通知/公告/活动发布时,向门店所有已绑定微信的员工推送订阅消息。
     * 异步执行,仅记录日志,不抛异常,不影响通知发布主流程。
     * 注意:员工需在 H5 端预先订阅通知模板,否则发送会被微信拒绝(43101)。
     *
     * @param notification 发布的通知对象
     */
    @Async
    public void notifyNotificationPublished(Notification notification) {
        if (!isNotifyTemplateConfigured()) {
            return;
        }
        if (notification == null || notification.getStoreId() == null) {
            return;
        }
        // 仅 status=1(上架)且已到上架时间的通知才推送
        if (notification.getStatus() == null || notification.getStatus() != 1) {
            return;
        }

        List<Employee> employees = employeeMapper.selectByStoreId(notification.getStoreId());
        if (employees == null || employees.isEmpty()) {
            return;
        }
        // 过滤已绑定 openid 的员工
        List<Employee> boundEmployees = employees.stream()
                .filter(e -> e.getWxOpenid() != null && !e.getWxOpenid().isBlank())
                .toList();
        if (boundEmployees.isEmpty()) {
            return;
        }

        // 通知类型文案
        String typeLabel = notificationTypeLabel(notification.getType());
        String title = notification.getTitle() == null ? "" : notification.getTitle();
        String content = notification.getContent() == null ? "" : notification.getContent();
        // 内容截断(模板消息单字段建议 ≤ 20 字,过长截断)
        if (content.length() > 50) {
            content = content.substring(0, 50) + "...";
        }
        String timeStr = java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Map<String, Object> data = new HashMap<>();
        data.put("title", dataItem("【" + typeLabel + "】" + title));
        data.put("content", dataItem(content));
        data.put("time", dataItem(timeStr));

        // 点击跳转到 H5 首页
        String url = buildH5Url("/");

        log.info("推送微信订阅消息(通知): storeId={}, type={}, 标题={}, 推送人数={}",
                notification.getStoreId(), typeLabel, title, boundEmployees.size());

        for (Employee emp : boundEmployees) {
            try {
                sendSubscribeMessage(emp.getWxOpenid(), templateNotifyId, data, url);
            } catch (Exception e) {
                log.warn("推送微信订阅消息(通知)异常: employeeId={}, error={}", emp.getId(), e.getMessage());
            }
        }
    }

    /** 通知类型中文标签 */
    private String notificationTypeLabel(Integer type) {
        if (type == null) return "通知";
        switch (type) {
            case 1: return "通知";
            case 3: return "公告";
            case 4: return "活动";
            default: return "通知";
        }
    }

    // ============================================================
    // 业务入口:订单创建
    // ============================================================

    /**
     * 订单创建成功后,向员工推送微信订阅消息(含订单日期、餐次、金额、取餐码)。
     * 异步执行,仅记录日志,不抛异常,不影响下单主流程。
     * 注意:员工需在 H5 端预先订阅订单模板,否则发送会被微信拒绝(43101)。
     *
     * @param order 创建的订单
     * @param employee 下单员工(需含 wxOpenid)
     */
    @Async
    public void notifyOrderCreated(Order order, Employee employee) {
        if (!isOrderTemplateConfigured()) {
            return;
        }
        if (order == null || employee == null) {
            return;
        }
        if (employee.getWxOpenid() == null || employee.getWxOpenid().isBlank()) {
            // 员工未绑定微信,跳过
            return;
        }

        // 订单日期格式化
        LocalDate date = order.getDate();
        String dateStr = date == null ? "" : date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        // 餐次文案
        MealType mealType = MealType.fromCode(order.getMealType());
        String mealTypeStr = mealType == null ? "未知餐次" : mealType.getChineseName();
        // 金额
        String amountStr = order.getTotalAmount() == null ? "0.00" : order.getTotalAmount().toPlainString();
        // 时间
        String timeStr = java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        // 取餐码
        String pickupCode = order.getPickupCode() == null ? "" : order.getPickupCode();

        Map<String, Object> data = new HashMap<>();
        data.put("orderDate", dataItem(dateStr));
        data.put("mealType", dataItem(mealTypeStr));
        data.put("amount", dataItem("¥" + amountStr));
        data.put("pickupCode", dataItem(pickupCode));
        data.put("time", dataItem(timeStr));

        // 点击跳转到订单详情页
        String url = buildH5Url("/orders/" + order.getId());

        log.info("推送微信订阅消息(订单): employeeId={}, orderId={}, 日期={}, 餐次={}",
                employee.getId(), order.getId(), dateStr, mealTypeStr);

        try {
            sendSubscribeMessage(employee.getWxOpenid(), templateOrderId, data, url);
        } catch (Exception e) {
            log.warn("推送微信订阅消息(订单)异常: employeeId={}, error={}", employee.getId(), e.getMessage());
        }
    }
}
