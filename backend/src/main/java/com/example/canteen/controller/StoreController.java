package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Store;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.StoreMapper;
import com.example.canteen.security.AuthCookieUtil;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.StoreService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/store")
public class StoreController {
    private final StoreService storeService;
    private final StoreMapper storeMapper;
    private final AdminMapper adminMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieUtil authCookieUtil;

    public StoreController(StoreService storeService, StoreMapper storeMapper,
                           AdminMapper adminMapper, JwtTokenProvider jwtTokenProvider,
                           AuthCookieUtil authCookieUtil) {
        this.storeService = storeService;
        this.storeMapper = storeMapper;
        this.adminMapper = adminMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authCookieUtil = authCookieUtil;
    }

    @GetMapping
    public ApiResponse<List<Store>> getAllStores() {
        // P0-2 securityCode 是敏感字段,仅管理员以上角色(超管/门店管理员)可访问,拒绝员工和终端
        Integer role = SecurityContext.currentRole();
        if (role == null || (role != SecurityContext.ROLE_SUPER_ADMIN && role != SecurityContext.ROLE_STORE_ADMIN)) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        return ApiResponse.success(storeService.getAllStores());
    }

    /**
     * 公开接口(免 token):供 H5/小程序登录页选择食堂使用。
     * 只返回营业中的食堂,且只暴露 id/name/code/address + 品牌字段(logo/image/description),
     * 不返回 securityCode 等敏感字段。
     */
    @GetMapping("/public-list")
    public ApiResponse<List<Map<String, Object>>> publicList() {
        List<Store> all = storeService.getActiveStores();
        List<Map<String, Object>> result = new java.util.ArrayList<>(all.size());
        for (Store s : all) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("code", s.getCode());
            m.put("address", s.getAddress());
            m.put("logoUrl", s.getLogoUrl());
            m.put("imageUrl", s.getImageUrl());
            m.put("description", s.getDescription());
            result.add(m);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Store> getStoreById(@PathVariable Long id) {
        // P0-2 securityCode 是敏感字段,仅管理员以上角色可访问,员工和终端用 publicList/branding
        Integer role = SecurityContext.currentRole();
        if (role == null || (role != SecurityContext.ROLE_SUPER_ADMIN && role != SecurityContext.ROLE_STORE_ADMIN)) {
            throw new com.example.canteen.exception.SecurityException("无权访问");
        }
        return ApiResponse.success(storeService.getStoreById(id));
    }

    /**
     * 公开接口(免 token):返回食堂品牌资源(logo/图片/终端背景/H5 banner/简介)。
     * 供 H5/terminal 免登录获取品牌信息。
     *
     * 缓存策略(零带宽优先):
     * - ETag 基于 store.updatedAt 计算,只在食堂信息变更时变化
     * - 客户端带 If-None-Match 时,匹配则返回 304(无 body,零带宽)
     * - Cache-Control: public, max-age=300 允许浏览器缓存 5 分钟,期满后用 ETag 校验
     * - 浏览器静态资源(图片 URL 自带 ?v=mtime)走 365d immutable 缓存
     *
     * 注意:不能用 ShallowEtagHeaderFilter,因为 ApiResponse.timestamp 字段每次都变,
     * 导致 body hash 永远不同,ETag 失效。这里手动用 updatedAt 作为 ETag 源。
     */
    @GetMapping("/{id}/branding")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBranding(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Store s = storeService.getStoreById(id);
        if (s == null) {
            throw new BusinessException("食堂不存在");
        }

        // ETag 基于 updatedAt 的毫秒值(食堂信息变更时才变),格式: v<epochSec>-<nano>
        // 不再用 32-bit hashCode(碰撞风险),改用毫秒精度,几乎不可能碰撞
        LocalDateTime updatedAt = s.getUpdatedAt();
        String etag;
        if (updatedAt != null) {
            etag = "v" + updatedAt.toEpochSecond(ZoneOffset.UTC) + "-" + updatedAt.getNano();
        } else {
            etag = "v0-0";
        }

        // If-None-Match 匹配 → 304 无 body,零带宽
        // 注意:不同 HTTP 客户端可能发送带引号或不带引号的 ETag,这里统一去除引号后比较
        String ifNoneMatchRaw = ifNoneMatch != null ? ifNoneMatch.replace("\"", "") : null;
        if (etag.equals(ifNoneMatchRaw)) {
            return ResponseEntity.status(304)
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                    .build();
        }

        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("logoUrl", s.getLogoUrl());
        m.put("imageUrl", s.getImageUrl());
        m.put("terminalBackgroundUrl", s.getTerminalBackgroundUrl());
        m.put("h5BannerUrl", s.getH5BannerUrl());
        m.put("description", s.getDescription());
        m.put("updatedAt", s.getUpdatedAt());

        return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(ApiResponse.success(m));
    }

    /**
     * 更新食堂品牌资源(仅超管)。
     * 接收 logoUrl/imageUrl/terminalBackgroundUrl/h5BannerUrl/description 字段。
     */
    @PutMapping("/{id}/branding")
    public ApiResponse<Store> updateBranding(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SecurityContext.checkSuperAdmin("仅超级管理员可修改食堂品牌信息");
        Store store = storeService.getStoreById(id);
        if (store == null) {
            throw new BusinessException("食堂不存在");
        }
        if (body.containsKey("logoUrl")) {
            String url = body.get("logoUrl") == null ? null : String.valueOf(body.get("logoUrl"));
            validateBrandingUrl(url);
            store.setLogoUrl(url);
        }
        if (body.containsKey("imageUrl")) {
            String url = body.get("imageUrl") == null ? null : String.valueOf(body.get("imageUrl"));
            validateBrandingUrl(url);
            store.setImageUrl(url);
        }
        if (body.containsKey("terminalBackgroundUrl")) {
            String url = body.get("terminalBackgroundUrl") == null ? null : String.valueOf(body.get("terminalBackgroundUrl"));
            validateBrandingUrl(url);
            store.setTerminalBackgroundUrl(url);
        }
        if (body.containsKey("h5BannerUrl")) {
            String url = body.get("h5BannerUrl") == null ? null : String.valueOf(body.get("h5BannerUrl"));
            validateBrandingUrl(url);
            store.setH5BannerUrl(url);
        }
        if (body.containsKey("description")) {
            store.setDescription(body.get("description") == null ? null : String.valueOf(body.get("description")));
        }
        return ApiResponse.success(storeService.updateStore(store));
    }

    /** P2-5 校验品牌资源 URL:必须通过上传,只允许 /uploads/ 相对路径,禁止任意 http 外链 */
    private void validateBrandingUrl(String url) {
        if (url == null || url.isBlank()) return; // 允许清空
        if (!url.startsWith("/uploads/")) {
            throw new BusinessException("品牌图片必须通过上传,URL 必须以 /uploads/ 开头");
        }
    }

    /**
     * 返回当前登录用户所属的食堂完整信息(含品牌字段)。
     * 超管的 storeId 可能为 0(未切换)或具体食堂(已切换)。
     */
    @GetMapping("/current")
    public ApiResponse<Store> getCurrentStore() {
        // P0-2 securityCode 是敏感字段,仅管理员以上角色可访问
        SecurityContext.checkStoreAdminOrAbove();
        Long sid = SecurityContext.currentStoreId();
        if (sid == null || sid == 0) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(storeService.getStoreById(sid));
    }

    /**
     * 超管切换"当前管理食堂"。
     * 实现方式:重签 JWT token,把 storeId 设为目标食堂,role 保持 1(超管)。
     * 这样超管既能跨店权限,又能锁定当前管理食堂的数据视图。
     * 已移除全局视图:必须指定具体食堂 ID,不允许切换到 storeId=0。
     */
    @PostMapping("/{id}/switch")
    public ApiResponse<Map<String, Object>> switchStore(@PathVariable Long id,
                                                         HttpServletRequest httpRequest,
                                                         HttpServletResponse httpResponse) {
        SecurityContext.checkSuperAdmin("仅超级管理员可切换管理食堂");
        if (id == null || id <= 0) {
            throw new BusinessException("请选择具体的食堂进行管理");
        }

        Store store = storeService.getStoreById(id);
        if (store == null) {
            throw new BusinessException("食堂不存在");
        }

        // 从 DB 查 admin,临时修改 storeId 后重签 token(不保存到 DB)
        Long adminId = SecurityContext.currentAdminId();
        if (adminId == null) {
            throw new BusinessException("无法获取当前管理员信息");
        }
        Admin admin = adminMapper.selectById(adminId);
        if (admin == null) {
            throw new BusinessException("管理员不存在");
        }
        admin.setStoreId(id);
        String newToken = jwtTokenProvider.generateToken(admin);

        // 写入 Cookie
        authCookieUtil.setAuthCookie(httpResponse, newToken, httpRequest);

        Map<String, Object> result = new HashMap<>();
        result.put("storeId", id);
        result.put("token", newToken);
        result.put("storeName", store.getName());
        return ApiResponse.success(result);
    }

    @OperationLog(value = "创建食堂", detail = "'食堂 ' + #store.name")
    @PostMapping
    public ApiResponse<Store> createStore(@RequestBody Store store) {
        SecurityContext.checkSuperAdmin("仅超级管理员可创建食堂");
        // 创建时自动生成安全码
        store.setSecurityCode(generateSecurityCode());
        return ApiResponse.success(storeService.createStore(store));
    }

    @OperationLog(value = "更新食堂", detail = "'食堂ID ' + #id + ' 名称 ' + #store.name")
    @PutMapping("/{id}")
    public ApiResponse<Store> updateStore(@PathVariable Long id, @RequestBody Store store) {
        SecurityContext.checkSuperAdmin("仅超级管理员可修改食堂");
        store.setId(id);
        // 不允许通过 update 接口修改安全码(走专门的 reset 接口)
        store.setSecurityCode(null);
        return ApiResponse.success(storeService.updateStore(store));
    }

    @OperationLog(value = "删除食堂", detail = "'食堂ID ' + #id")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteStore(@PathVariable Long id) {
        SecurityContext.checkSuperAdmin("仅超级管理员可删除食堂");
        storeService.deleteStore(id);
        return ApiResponse.success(null);
    }

    /**
     * 重置食堂安全码。仅超管可操作。
     * 重置后,使用旧安全码绑定的终端下次启动校验失败,需重新绑定。
     */
    @PostMapping("/{id}/reset-security-code")
    public ApiResponse<Map<String, Object>> resetSecurityCode(@PathVariable Long id) {
        SecurityContext.checkSuperAdmin("仅超级管理员可重置食堂安全码");
        Store store = storeService.getStoreById(id);
        if (store == null) {
            throw new BusinessException("食堂不存在");
        }
        String newCode = generateSecurityCode();
        store.setSecurityCode(newCode);
        // 通过 storeService.updateStore 保存(会同步更新 updatedAt,作为 ETag 源)
        storeService.updateStore(store);

        Map<String, Object> result = new HashMap<>();
        result.put("id", store.getId());
        result.put("name", store.getName());
        result.put("securityCode", newCode);
        return ApiResponse.success(result);
    }

    /** 生成 8 位安全码(大小写字母+数字,排除易混淆字符 0/O/1/I/l) */
    private static String generateSecurityCode() {
        String chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
