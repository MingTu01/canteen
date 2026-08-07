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

    /** 员工 token 过期时间(默认 30 天),H5 长期登录不失效 */
    @Value("${jwt.employee-expiration:2592000000}")
    private Long employeeExpiration;

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
                .expiration(new Date(System.currentTimeMillis() + employeeExpiration))
                .signWith(secretKey)
                .compact();
    }

    public Map<String, Object> validateToken(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }

    /**
     * 生成员工身份二维码签名(HMAC-SHA256),取 hex 前 32 位(128 bit)。
     * 签名内容:cardNo|storeId|employeeId|expire
     * 复用 jwt secretKey 作为 HMAC 密钥。
     * 128 bit 截断显著高于 64 bit,可抵抗离线碰撞/猜测攻击(二维码仅身份用途,足够)。
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
            return sb.substring(0, 32);
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

    /**
     * 滑动续期:用当前有效 token 的 claims 换取新 token。
     * 新 token 的过期时间根据 role 重新计算(admin 24h / employee 30d / terminal 365d)。
     * 用于 JwtAuthenticationFilter 中自动续期,避免活跃用户被登出。
     */
    public String renewToken(Map<String, Object> oldClaims) {
        Integer role = oldClaims.get("role") instanceof Number n ? n.intValue() : null;
        long ttl = switch (role == null ? -1 : role) {
            case 0 -> employeeExpiration;   // 员工
            case 3 -> terminalExpiration;   // 终端
            default -> expiration;           // 管理员(1=超管, 2=门店管理员)
        };
        // 复用原 claims,但更新 jti/iat/exp
        Map<String, Object> claims = new HashMap<>(oldClaims);
        claims.remove("exp");
        claims.remove("iat");
        claims.remove("jti");
        String subject = oldClaims.get("sub") == null ? "renewed" : oldClaims.get("sub").toString();
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttl))
                .signWith(secretKey)
                .compact();
    }

    /** 获取指定 role 的 token TTL(毫秒),供 Filter 判断是否需要续期 */
    public long getTtlByRole(Integer role) {
        return switch (role == null ? -1 : role) {
            case 0 -> employeeExpiration;
            case 3 -> terminalExpiration;
            default -> expiration;
        };
    }
}
