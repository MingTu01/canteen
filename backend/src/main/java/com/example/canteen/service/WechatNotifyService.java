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
 * 微信公众号一次性订阅消息通知服务。
 *
 * 2026-08 适配微信开发者平台新版:多模板体系已下线,每个公众号仅保留一个
 * 固定的「一次性订阅消息」模板(开发者平台 → 服务号 → 一次性订阅消息处查看模板ID)。
 * 发送接口 message/template/subscribe(接口英文名 templateSubscribe)
 * 必填 scene(字符串) + title(≤15字),data 仅支持单个 content 字段(≤200字)。
 *
 * 授权机制:员工需通过订阅授权链接(mp/subscribemsg)逐次授权,
 * 每次授权仅可下发一条消息,同一 scene 多次授权不累积。
 * 降低操作门槛:H5 首次登录强制改密成功后弹窗引导开通(仅同意/拒绝一次点击),
 * 「我的 → 微信提醒订阅」提供常驻入口;推送时无授权额度则回退客服消息
 * (关注/互动后 48 小时窗口内可送达,无需授权,带图公告走客服图文卡片可显示配图)。
 *
 * 用于向已绑定微信的员工推送:
 * 1. 通知/公告/活动发布提醒(scene=1000)
 * 2. 订单创建成功提醒(含日期、餐次,scene=1001)
 *
 * 实现:
 * - access_token 全局缓存(有效期 2 小时,提前 5 分钟刷新),多实例下各自缓存可接受
 *   (微信允许一定并发重复获取,影响仅是 token 互踢导致前一个失效,重试即可)
 * - 订阅消息发送失败不影响主业务(下单/发通知),仅记录日志
 *
 * 配置(环境变量,未配置时方法静默跳过):
 * - WECHAT_APP_ID / WECHAT_APP_SECRET:公众号凭证(复用 WechatAuthService)
 * - WECHAT_TEMPLATE_NOTIFY / WECHAT_TEMPLATE_ORDER:新版仅一个固定模板,两项填同一模板ID
 */
@Service
public class WechatNotifyService {
    private static final Logger log = LoggerFactory.getLogger(WechatNotifyService.class);

    /** 订阅场景值:通知/公告/活动(0-10000,H5 授权与下发需一致) */
    public static final int SCENE_NOTIFY = 1000;
    /** 订阅场景值:订单创建 */
    public static final int SCENE_ORDER = 1001;

    private final EmployeeMapper employeeMapper;
    private final RestTemplate restTemplate;
    /** 图片签名:公告配图(/uploads/**)需带签名后微信才能拉取 */
    private final ImageSignService imageSignService;

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

    /** 关注引导客服卡片封面图(复用欢迎图文封面,可选) */
    @Value("${wechat.h5-banner-url:}")
    private String h5BannerUrl;

    public WechatNotifyService(EmployeeMapper employeeMapper, ImageSignService imageSignService) {
        this.employeeMapper = employeeMapper;
        this.imageSignService = imageSignService;
        // 微信 API 超时:连接 5s/读取 10s,避免线程无限期阻塞
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
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
     * 发送一次性订阅消息到指定 openid。
     * 单条发送,失败仅记录日志,不抛异常(避免影响主业务)。
     *
     * 2026-08 适配微信开发者平台新版:平台已下线多模板体系,每个公众号仅保留
     * 一个固定的一次性订阅消息模板,接口必填 scene + title,data 只支持单个
     * content 字段(≤200 字)。两个模板配置项(WECHAT_TEMPLATE_NOTIFY/ORDER)
     * 需填同一个模板 ID。
     *
     * 注意:一次性订阅消息要求用户先在 H5 端完成订阅授权(每次授权仅可下发一条,
     * 同一 scene 多次授权不累积),否则微信返回 errcode=43101(用户未订阅)。
     *
     * @param openid 接收人 openid
     * @param templateId 一次性订阅消息模板ID
     * @param scene 订阅场景值(0-10000,通知=1000/订单=1001)
     * @param title 消息标题(≤15 字,超长截断)
     * @param contentText 消息正文(≤200 字,超长截断)
     * @param url 点击跳转的 URL(可为 null)
     */
    @SuppressWarnings("unchecked")
    private boolean sendSubscribeMessage(String openid, String templateId, int scene,
                                      String title, String contentText, String url) {
        String accessToken = getAccessToken();
        if (accessToken == null) {
            return false;
        }
        // 新版一次性订阅消息发送接口(接口英文名 templateSubscribe)
        String apiUrl = "https://api.weixin.qq.com/cgi-bin/message/template/subscribe?access_token=" + accessToken;
        Map<String, Object> body = new HashMap<>();
        body.put("touser", openid);
        body.put("template_id", templateId);
        body.put("scene", String.valueOf(scene));
        body.put("title", truncate(title, 15));
        if (url != null && !url.isBlank()) {
            body.put("url", url);
        }
        Map<String, Object> content = new HashMap<>();
        content.put("value", truncate(contentText, 200));
        Map<String, Object> data = new HashMap<>();
        data.put("content", content);
        body.put("data", data);

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiUrl, body, Map.class);
            Map<String, Object> result = resp.getBody();
            if (result == null) {
                log.warn("微信订阅消息发送返回空响应,openid={}", openid);
                return false;
            }
            Object errcode = result.get("errcode");
            if (errcode != null && !"0".equals(errcode.toString())) {
                // 常见错误:43101(用户未订阅/授权额度已用完)、40037(模板ID无效)、40003(openid无效)
                log.warn("微信订阅消息发送失败: openid={}, errcode={}, errmsg={}",
                        openid, errcode, result.get("errmsg"));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("微信订阅消息发送异常: openid={}, error={}", openid, e.getMessage());
            return false;
        }
    }

    /** 字符串安全截断(避免超长被微信拒绝) */
    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s == null ? "" : s;
        }
        return s.substring(0, maxLen);
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

    /**
     * 构造一次性订阅消息授权链接(H5 内跳转,用户确认后获得一次下发权限)。
     *
     * 链接格式:https://mp.weixin.qq.com/mp/subscribemsg?action=get_confirm&appid=...
     * 用户同意授权后微信回跳:redirect_url?openid=..&action=confirm&scene=..
     *
     * 前提:H5 域名须配置为公众号「业务域名」(mp后台 → 设置与开发 → 公众号设置 → 功能设置)。
     *
     * @param scene 订阅场景值(SCENE_NOTIFY=1000 通知 / SCENE_ORDER=1001 订单)
     * @return 授权链接;未配置(appId/模板/H5地址缺失)返回 null
     */
    public String buildSubscribeAuthUrl(int scene) {
        if (!isConfigured() || templateNotifyId == null || templateNotifyId.isBlank()) {
            return null;
        }
        String redirect = buildH5Url("/profile?subscribed=1");
        if (redirect == null) {
            return null;
        }
        try {
            return "https://mp.weixin.qq.com/mp/subscribemsg"
                    + "?action=get_confirm"
                    + "&appid=" + appId
                    + "&scene=" + scene
                    + "&template_id=" + templateNotifyId
                    + "&redirect_url=" + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8)
                    + "#wechat_redirect";
        } catch (Exception e) {
            log.warn("构造订阅授权链接失败: {}", e.getMessage());
            return null;
        }
    }

    // ============================================================
    // 客服消息(关注/互动后48小时窗口内可送达,无需订阅授权)
    // ============================================================

    /**
     * 发送客服文本消息(message/custom/send)。
     * 微信规则:仅当用户 48 小时内与公众号互动过(关注/发消息/点菜单)才可送达,
     * 否则返回 45015。适合作为订阅消息无授权额度时的回退通道。
     */
    @SuppressWarnings("unchecked")
    private boolean sendCustomTextMessage(String openid, String text) {
        String accessToken = getAccessToken();
        if (accessToken == null) {
            return false;
        }
        String apiUrl = "https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=" + accessToken;
        Map<String, Object> textObj = new HashMap<>();
        textObj.put("content", truncate(text, 2048));
        Map<String, Object> body = new HashMap<>();
        body.put("touser", openid);
        body.put("msgtype", "text");
        body.put("text", textObj);
        return postCustomMessage(apiUrl, body, openid);
    }

    /**
     * 发送客服图文卡片(封面+标题+跳转链接,展示效果优于纯文本)。
     * 同样受 48 小时互动窗口限制。
     */
    @SuppressWarnings("unchecked")
    private boolean sendCustomNewsMessage(String openid, String title, String description,
                                          String url, String picUrl) {
        String accessToken = getAccessToken();
        if (accessToken == null) {
            return false;
        }
        String apiUrl = "https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=" + accessToken;
        Map<String, Object> article = new HashMap<>();
        article.put("title", truncate(title, 64));
        article.put("description", truncate(description, 120));
        article.put("url", url);
        if (picUrl != null && !picUrl.isBlank()) {
            article.put("picurl", picUrl);
        }
        Map<String, Object> news = new HashMap<>();
        news.put("articles", List.of(article));
        Map<String, Object> body = new HashMap<>();
        body.put("touser", openid);
        body.put("msgtype", "news");
        body.put("news", news);
        return postCustomMessage(apiUrl, body, openid);
    }

    /** 客服消息统一提交与结果判定(48小时窗口外 errcode=45015 属正常未送达) */
    @SuppressWarnings("unchecked")
    private boolean postCustomMessage(String apiUrl, Map<String, Object> body, String openid) {
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiUrl, body, Map.class);
            Map<String, Object> result = resp.getBody();
            if (result == null) {
                return false;
            }
            Object errcode = result.get("errcode");
            if (errcode != null && "0".equals(errcode.toString())) {
                return true;
            }
            // 45015=超出48小时互动窗口(多数老关注用户),45002=内容超限等;降级为 info 不刷警告日志
            log.info("客服消息未送达: openid={}, errcode={}, errmsg={}",
                    openid, errcode, result.get("errmsg"));
        } catch (Exception e) {
            log.warn("客服消息发送异常: openid={}, error={}", openid, e.getMessage());
        }
        return false;
    }

    /**
     * 构造公告配图的公网绝对 URL(供微信客服图文卡片封面拉取)。
     * - http(s) 外链:直接返回
     * - /uploads/ 本地存储:附加签名(sig+exp)后拼接 H5 公网域名
     *   (部署上 /uploads 与 H5 同域反代到后端,签名 7 天有效,微信拉取时可通过校验)
     * - dataURL 等其他格式:返回 null(微信不支持)
     */
    private String buildPicUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String trimmed = imageUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/uploads/")) {
            if (h5BaseUrl == null || h5BaseUrl.isBlank()) {
                return null;
            }
            String base = h5BaseUrl.endsWith("/")
                    ? h5BaseUrl.substring(0, h5BaseUrl.length() - 1)
                    : h5BaseUrl;
            return base + imageSignService.sign(trimmed);
        }
        return null;
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
        if (content.length() > 100) {
            content = content.substring(0, 100) + "...";
        }
        String timeStr = java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        // 新版固定模板:标题(≤15字) + 单条正文(≤200字)
        String msgTitle = "【" + typeLabel + "】" + title;
        String msgContent = title + "\n" + content + "\n" + timeStr;

        // 点击跳转到 H5 首页
        String url = buildH5Url("/");

        // 公告配图(可显示在客服图文卡片封面;订阅消息模板仅支持文字,无法带图)
        String picUrl = buildPicUrl(notification.getImageUrl());

        log.info("推送微信消息(通知): storeId={}, type={}, 标题={}, 推送人数={}, 带图={}",
                notification.getStoreId(), typeLabel, title, boundEmployees.size(), picUrl != null);

        for (Employee emp : boundEmployees) {
            try {
                if (picUrl != null) {
                    // 带图公告:优先客服图文卡片(封面图+标题+摘要+链接,展示最佳);
                    // 48小时互动窗口外(45015)回退订阅消息(纯文字,微信模板不支持图片)
                    boolean delivered = sendCustomNewsMessage(emp.getWxOpenid(),
                            msgTitle, content, url, picUrl);
                    if (!delivered) {
                        sendSubscribeMessage(emp.getWxOpenid(), templateNotifyId,
                                SCENE_NOTIFY, msgTitle, msgContent, url);
                    }
                } else {
                    boolean sent = sendSubscribeMessage(emp.getWxOpenid(), templateNotifyId,
                            SCENE_NOTIFY, msgTitle, msgContent, url);
                    if (!sent) {
                        // 无可用订阅授权(43101):回退客服文本消息(48小时内互动过的员工可收,如刚关注的)
                        sendCustomTextMessage(emp.getWxOpenid(),
                                msgTitle + "\n" + content + "\n点击查看:" + (url == null ? "" : url));
                    }
                }
            } catch (Exception e) {
                log.warn("推送微信消息(通知)异常: employeeId={}, error={}", emp.getId(), e.getMessage());
            }
        }
    }

    /** 通知类型中文标签 */
    private String notificationTypeLabel(Integer type) {
        if (type == null) return "通知";
        switch (type) {
            case 1: return "通知";
            case 2: return "公告";
            case 3: return "活动";
            default: return "通知";
        }
    }

    // ============================================================
    // 业务入口:订单创建
    // ============================================================

    /**
     * 订单创建成功后,向员工推送微信订阅消息(含订单日期、餐次、金额)。
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

        // 新版固定模板:标题(≤15字) + 单条正文(≤200字)
        String msgTitle = "订餐成功";
        String msgContent = "日期: " + dateStr + "\n餐次: " + mealTypeStr
                + "\n金额: ¥" + amountStr + "\n时间: " + timeStr;

        // 点击跳转到订单详情页
        String url = buildH5Url("/orders/" + order.getId());

        log.info("推送微信订阅消息(订单): employeeId={}, orderId={}, 日期={}, 餐次={}",
                employee.getId(), order.getId(), dateStr, mealTypeStr);

        try {
            boolean sent = sendSubscribeMessage(employee.getWxOpenid(), templateOrderId,
                    SCENE_ORDER, msgTitle, msgContent, url);
            if (!sent) {
                // 无可用订阅授权(43101):回退客服文本消息(48小时内互动过的员工可收,如刚关注的)
                sendCustomTextMessage(employee.getWxOpenid(),
                        "【订餐成功】" + dateStr + " " + mealTypeStr + " ¥" + amountStr
                                + "\n点击查看订单:" + (url == null ? "" : url));
            }
        } catch (Exception e) {
            log.warn("推送微信订阅消息(订单)异常: employeeId={}, error={}", employee.getId(), e.getMessage());
        }
    }
}
