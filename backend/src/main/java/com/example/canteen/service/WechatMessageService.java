package com.example.canteen.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 微信公众号消息/事件回调处理服务。
 *
 * 用于接收微信服务器推送的消息与事件(需在公众号后台「基本配置→服务器配置」
 * 填写回调URL/Token/EncodingAESKey,并启用服务器配置)。
 *
 * 当前支持:
 * - GET 接入签名校验(公众号后台启用服务器配置时微信会GET回调校验)
 * - POST 接收事件:关注(subscribe)→被动回复图文卡片,引导员工进入H5订餐
 *
 * 明文模式(不加密):XML明文传输,subscribe事件与回复均不含敏感信息,风险可控。
 * 如需安全模式(加密),需引入 wxbizmsgcrypt SDK,此处暂不实现。
 *
 * 配置:
 * - wechat.token:服务器配置里的Token(用于签名校验,必填)
 * - wechat.h5-base-url:H5访问域名(拼接图文卡片跳转URL)
 * - wechat.h5-banner-url:图文卡片封面图URL(可选,未配置则回退纯文本回复)
 */
@Service
public class WechatMessageService {
    private static final Logger log = LoggerFactory.getLogger(WechatMessageService.class);

    @Value("${wechat.token:}")
    private String token;

    @Value("${wechat.h5-base-url:}")
    private String h5BaseUrl;

    @Value("${wechat.h5-banner-url:}")
    private String h5BannerUrl;

    /** 食堂名称(用于回复文案,未配置时用通用文案) */
    @Value("${wechat.canteen-name:企业食堂}")
    private String canteenName;

    /** Token 是否已配置(未配置时回调接口直接拒绝,避免无效请求) */
    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    // ============================================================
    // 签名校验(微信接入验证 + 每次POST回调也会带签名)
    // ============================================================

    /**
     * 校验微信签名:将 token/timestamp/nonce 字典序排序后拼接做 SHA1,与 signature 比较。
     * 算法来自微信官方文档。
     */
    public boolean verifySignature(String signature, String timestamp, String nonce) {
        if (!isConfigured() || signature == null || timestamp == null || nonce == null) {
            return false;
        }
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            sb.append(s);
        }
        String sha1 = sha1(sb.toString());
        return signature.equals(sha1);
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ============================================================
    // 消息/事件处理
    // ============================================================

    /**
     * 处理微信推送的XML消息,返回被动回复XML(或空字符串表示不回复)。
     * 微信要求5秒内响应,本方法同步处理(仅解析+构造XML,无外部IO,很快)。
     */
    public String handleMessage(String xml) {
        try {
            Document doc = parseXml(xml);
            String msgType = getText(doc, "MsgType");
            String toUserName = getText(doc, "ToUserName");   // 公众号
            String fromUserName = getText(doc, "FromUserName"); // 发送方(openid)

            if ("event".equals(msgType)) {
                String event = getText(doc, "Event");
                if ("subscribe".equals(event)) {
                    // 关注事件:回复图文卡片引导订餐
                    log.info("员工关注公众号: openid={}", fromUserName);
                    return buildSubscribeReply(fromUserName, toUserName);
                }
                // 取消关注(unsubscribe)等事件:不回复
                return "";
            }
            // 其他消息类型(文本/图片等):回复引导文本
            return buildTextReply(fromUserName, toUserName,
                    "欢迎来到" + canteenName + "，点击底部菜单【去订餐】即可在线点餐。");
        } catch (Exception e) {
            log.warn("处理微信消息异常: {}", e.getMessage());
            return "";
        }
    }

    // ============================================================
    // 回复XML构造
    // ============================================================

    /**
     * 关注后回复:优先图文卡片(需配置封面图),否则回退文本。
     * 图文卡片点击跳转H5首页,已绑定微信则免密进入,未绑定则到登录页绑定。
     */
    private String buildSubscribeReply(String openid, String ghId) {
        long createTime = System.currentTimeMillis() / 1000;
        String h5Url = buildH5Url("/");

        // 未配置封面图或H5域名:回退文本回复
        if (h5Url == null || h5BannerUrl == null || h5BannerUrl.isBlank()) {
            return buildTextReply(openid, ghId,
                    "欢迎关注" + canteenName + "！\n点击下方卡片或底部菜单【去订餐】开始在线点餐。\n"
                            + "首次使用需用手机号登录并绑定微信，之后即可一键免密订餐。");
        }

        // 图文消息(单条)
        return "<xml>"
                + "<ToUserName><![CDATA[" + openid + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + ghId + "]]></FromUserName>"
                + "<CreateTime>" + createTime + "</CreateTime>"
                + "<MsgType><![CDATA[news]]></MsgType>"
                + "<ArticleCount>1</ArticleCount>"
                + "<Articles>"
                + "<item>"
                + "<Title><![CDATA[欢迎关注" + canteenName + "]]></Title>"
                + "<Description><![CDATA[点击进入在线订餐，首次使用需用手机号登录绑定微信，之后一键免密订餐。]]></Description>"
                + "<PicUrl><![CDATA[" + h5BannerUrl + "]]></PicUrl>"
                + "<Url><![CDATA[" + h5Url + "]]></Url>"
                + "</item>"
                + "</Articles>"
                + "</xml>";
    }

    /** 构造文本回复XML */
    private String buildTextReply(String openid, String ghId, String content) {
        long createTime = System.currentTimeMillis() / 1000;
        return "<xml>"
                + "<ToUserName><![CDATA[" + openid + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + ghId + "]]></FromUserName>"
                + "<CreateTime>" + createTime + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + content + "]]></Content>"
                + "</xml>";
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 拼接H5完整URL(末尾不带斜杠的base + path) */
    private String buildH5Url(String path) {
        if (h5BaseUrl == null || h5BaseUrl.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        String base = h5BaseUrl.endsWith("/") ? h5BaseUrl.substring(0, h5BaseUrl.length() - 1) : h5BaseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    /**
     * DOM解析XML。
     * 每次调用新建 DocumentBuilderFactory(工厂非线程安全,不做共享成员),
     * 并启用完整 XXE 防护(禁 DTD/外部实体/参数实体/外部 DTD)。
     */
    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        // XXE 防护:禁 DTD/外部实体/参数实体/外部 DTD
        // trySetFeature:解析器不支持该特性时忽略(兼容不同 JAXP 实现)
        trySetFeature(dbFactory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(dbFactory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(dbFactory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(dbFactory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            dbFactory.setXIncludeAware(false);
        } catch (Exception ignored) {
            // 解析器不支持时忽略
        }
        try {
            dbFactory.setExpandEntityReferences(false);
        } catch (Exception ignored) {
            // 解析器不支持时忽略
        }
        DocumentBuilder builder = dbFactory.newDocumentBuilder();
        try (StringReader reader = new StringReader(xml)) {
            return builder.parse(new InputSource(reader));
        }
    }

    /** 设置 JAXP 特性,解析器不支持时静默忽略 */
    private static void trySetFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // 解析器不支持该特性时忽略(不影响其它防护)
        }
    }

    /** 提取XML中指定标签的文本内容(兼容CDATA) */
    private String getText(Document doc, String tagName) {
        org.w3c.dom.NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent();
    }
}
