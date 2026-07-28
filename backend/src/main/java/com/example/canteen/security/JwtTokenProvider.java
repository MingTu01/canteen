package com.example.canteen.security;

import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Employee;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtTokenProvider {
    /** A6:复用 JwtConfig 中已定义的 jwtSecretKey Bean,避免每次重新生成 SecretKey */
    private final SecretKey secretKey;
    private final JwtParser jwtParser;

    @Value("${jwt.expiration}")
    private Long expiration;

    /** 终端 token 过期时间(默认 365 天),远长于管理员 token(24h),避免 7x24 终端频繁失绑 */
    @Value("${jwt.terminal-expiration:31536000000}")
    private Long terminalExpiration;

    public JwtTokenProvider(SecretKey jwtSecretKey, JwtParser jwtParser) {
        this.secretKey = jwtSecretKey;
        this.jwtParser = jwtParser;
    }

    public String generateToken(Admin admin) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", admin.getId());
        claims.put("username", admin.getUsername());
        claims.put("name", admin.getName());
        claims.put("storeId", admin.getStoreId());
        claims.put("role", admin.getRole());

        return Jwts.builder()
                .claims(claims)
                .subject(admin.getUsername())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public String generateEmployeeToken(Employee employee) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", employee.getId());
        claims.put("cardNo", employee.getCardNo());
        claims.put("name", employee.getName());
        claims.put("storeId", employee.getStoreId());
        claims.put("departmentId", employee.getDepartmentId());
        claims.put("balance", employee.getBalance());
        claims.put("role", 0);

        return Jwts.builder()
                .claims(claims)
                .subject(employee.getCardNo())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public Map<String, Object> validateToken(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }

    /**
     * 生成员工身份二维码签名(HMAC-SHA256),取 hex 前 16 位。
     * 签名内容:cardNo|storeId|employeeId|expire
     * 复用 jwt secretKey 作为 HMAC 密钥。
     */
    public String generateQrcodeSign(String cardNo, Long storeId, Long employeeId, long expire) {
        String data = cardNo + "|" + storeId + "|" + employeeId + "|" + expire;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getEncoded(), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            throw new RuntimeException("生成二维码签名失败", e);
        }
    }

    /**
     * 校验员工身份二维码签名是否一致(不校验过期,由调用方校验 expire)。
     * 使用 MessageDigest.isEqual 做常量时间比较,防止时序攻击。
     */
    public boolean verifyQrcodeSign(String cardNo, Long storeId, Long employeeId, long expire, String sign) {
        if (sign == null || sign.isEmpty()) return false;
        String expected = generateQrcodeSign(cardNo, storeId, employeeId, expire);
        return java.security.MessageDigest.isEqual(expected.getBytes(), sign.getBytes());
    }

    /**
     * 生成终端(X86 设备)专用 token。
     * role=3 表示终端身份,storeId 锁定,不具备管理权限,只能访问门店公开数据(菜单/订单创建/取餐)。
     */
    public String generateTerminalToken(Long storeId, String storeName, String deviceLabel) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("storeId", storeId);
        claims.put("storeName", storeName);
        claims.put("role", 3);
        claims.put("deviceLabel", deviceLabel == null ? "" : deviceLabel);

        return Jwts.builder()
                .claims(claims)
                .subject("terminal-store-" + storeId)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + terminalExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 刷新终端 token:用当前有效的终端 token 换取新 token(滚动续期)。
     * 新 token 的 storeId/storeName/deviceLabel 与原 token 一致,过期时间重新计算。
     * 用于 token 即将过期前主动续期,避免终端频繁失绑。
     */
    public String refreshTerminalToken(Long storeId, String storeName, String deviceLabel) {
        return generateTerminalToken(storeId, storeName, deviceLabel);
    }
}
