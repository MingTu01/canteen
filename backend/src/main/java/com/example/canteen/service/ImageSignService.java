package com.example.canteen.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 图片访问签名服务。
 *
 * 作用:给 /uploads/** 图片 URL 附加 HMAC 签名,防止链接泄露后被外部直接访问。
 *
 * 签名格式:URL 附加 ?sig=xxx&exp=xxx
 *   - sig = HMAC-SHA256(secret, path + "|" + exp) 的 Base64URL 编码(无填充)
 *   - exp  = 过期时间戳(毫秒)
 *
 * 验证时:重新计算 sig 比对,并检查 exp > 当前时间。
 *
 * 签名有效期默认 7 天,前端缓存签名结果后无需频繁请求签名接口。
 */
@Service
public class ImageSignService {

    /** 签名密钥(从配置读取,默认值保证未配置时也能工作) */
    @Value("${image.sign-secret:canteen-img-sign-9f8a2b7e4d1c6a3f5e0b8d2c4a6f1e3d}")
    private String secret;

    /** 签名有效期(毫秒),默认 7 天 */
    @Value("${image.sign-ttl-ms:604800000}")
    private long signTtlMs;

    /**
     * 给图片路径生成签名 URL。
     * 输入:/uploads/xxx.jpg 或 /uploads/xxx.jpg?v=123
     * 输出:/uploads/xxx.jpg?v=123&sig=xxx&exp=xxx
     *
     * @param path 图片相对路径(以 /uploads/ 开头)
     * @return 带签名参数的 URL;非 /uploads/ 路径直接返回原值
     */
    public String sign(String path) {
        if (path == null || path.isBlank()) return path;
        if (!path.startsWith("/uploads/")) return path;

        long exp = System.currentTimeMillis() + signTtlMs;
        // 签名内容:纯路径(去掉 query 参数)+ exp
        String purePath = path;
        int qIdx = purePath.indexOf('?');
        if (qIdx >= 0) purePath = purePath.substring(0, qIdx);

        String sig = computeHmac(purePath + "|" + exp);

        // 附加签名参数(保留原 query 参数如 ?v=mtime)
        String sep = path.contains("?") ? "&" : "?";
        return path + sep + "sig=" + sig + "&exp=" + exp;
    }

    /**
     * 验证签名 URL。
     *
     * @param path 请求 URI(纯路径,如 /uploads/xxx.jpg)
     * @param sig  签名参数值
     * @param exp  过期时间戳(毫秒,字符串)
     * @return true=签名有效且未过期
     */
    public boolean verify(String path, String sig, String exp) {
        if (sig == null || sig.isBlank() || exp == null || exp.isBlank()) return false;
        long expMs;
        try {
            expMs = Long.parseLong(exp);
        } catch (NumberFormatException e) {
            return false;
        }
        // 检查过期
        if (expMs < System.currentTimeMillis()) return false;
        // 重新计算签名比对
        String expected = computeHmac(path + "|" + expMs);
        return expected.equals(sig);
    }

    /** 计算 HMAC-SHA256 并返回 Base64URL(无填充)编码 */
    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("图片签名计算失败", e);
        }
    }
}
