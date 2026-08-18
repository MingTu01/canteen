package com.example.canteen.controller;

import com.example.canteen.security.PermissionUtils;
import com.example.canteen.service.WechatMessageService;
import com.example.canteen.service.WechatNotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 微信公众号消息/事件回调接口。
 *
 * 在公众号后台「基本配置→服务器配置」填写:
 * - URL: https://你的域名/api/wechat/callback
 * - Token: 与 .env 中 WECHAT_TOKEN 一致
 * - EncodingAESKey: 明文模式可随机填(不使用),安全模式需填
 * - 消息加解密方式: 选"明文模式"即可
 *
 * 微信会先 GET 该地址做接入签名校验(返回 echostr),通过后启用服务器配置;
 * 之后关注/取关/菜单点击/消息等事件会 POST 推送到该地址。
 *
 * 白名单:WhitelistMatcher 已放行 /api/wechat/callback(回调不带JWT)。
 */
@RestController
@RequestMapping("/api/wechat")
public class WechatMessageController {
    private static final Logger log = LoggerFactory.getLogger(WechatMessageController.class);

    private final WechatMessageService wechatMessageService;
    private final WechatNotifyService wechatNotifyService;

    public WechatMessageController(WechatMessageService wechatMessageService,
                                   WechatNotifyService wechatNotifyService) {
        this.wechatMessageService = wechatMessageService;
        this.wechatNotifyService = wechatNotifyService;
    }

    /**
     * 重新下发默认底部菜单(单个「在线订餐」按钮 → H5 首页)。
     * 启用服务器配置后公众号后台菜单失效,菜单只能通过本接口管理。
     * 调用后用户端约 5 分钟刷新(重新进入公众号会话页生效)。
     */
    @PostMapping("/menu/sync")
    public ResponseEntity<Map<String, Object>> syncMenu() {
        PermissionUtils.requireAdmin();
        String error = wechatNotifyService.syncDefaultMenu();
        if (error == null) {
            return ResponseEntity.ok(Map.of("success", true, "message", "菜单下发成功,约5分钟后用户端刷新"));
        }
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error));
    }

    /**
     * 获取一次性订阅消息授权链接(H5 登录后调用,跳转后用户确认即完成一次订阅)。
     *
     * @param scene 订阅场景:1000=通知/公告,1001=订单(默认1000)
     * @return {url: 授权链接};未配置微信返回 400
     */
    @GetMapping("/subscribe-url")
    public ResponseEntity<Map<String, String>> subscribeUrl(@RequestParam(value = "scene", defaultValue = "1000") int scene) {
        if (scene < 0 || scene > 10000) {
            scene = WechatNotifyService.SCENE_NOTIFY;
        }
        String url = wechatNotifyService.buildSubscribeAuthUrl(scene);
        if (url == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "微信公众号未配置(AppID/模板ID/H5地址)"));
        }
        return ResponseEntity.ok(Map.of("url", url));
    }

    /**
     * 微信接入签名校验(GET)。
     * 公众号后台启用服务器配置时,微信会GET本接口,校验通过需原样返回 echostr。
     *
     * @param signature 微信加密签名
     * @param timestamp 时间戳
     * @param nonce     随机数
     * @param echostr   随机字符串(校验通过需原样返回)
     */
    @GetMapping("/callback")
    public String verify(@RequestParam("signature") String signature,
                         @RequestParam("timestamp") String timestamp,
                         @RequestParam("nonce") String nonce,
                         @RequestParam("echostr") String echostr) {
        if (!wechatMessageService.isConfigured()) {
            log.warn("微信回调Token未配置,拒绝接入校验");
            return "";
        }
        if (wechatMessageService.verifySignature(signature, timestamp, nonce)) {
            log.info("微信服务器配置接入校验通过");
            return echostr;
        }
        log.warn("微信签名校验失败: timestamp={}, nonce={}", timestamp, nonce);
        return "";
    }

    /**
     * 接收微信推送的消息/事件(POST)。
     * 微信推送XML体,本接口解析后被动回复XML。
     * 关注事件(subscribe)→回复图文卡片引导订餐;其他消息→回复引导文本。
     *
     * @param xml 微信推送的XML消息体
     * @return 被动回复XML(空字符串表示不回复)
     */
    @PostMapping(value = "/callback", produces = MediaType.APPLICATION_XML_VALUE + ";charset=UTF-8")
    public String handleMessage(@RequestBody String xml,
                                @RequestParam("signature") String signature,
                                @RequestParam("timestamp") String timestamp,
                                @RequestParam("nonce") String nonce) {
        // 安全:POST回调也校验签名,防止伪造请求
        if (!wechatMessageService.isConfigured()
                || !wechatMessageService.verifySignature(signature, timestamp, nonce)) {
            log.warn("微信POST回调签名校验失败");
            return "";
        }
        return wechatMessageService.handleMessage(xml);
    }
}
