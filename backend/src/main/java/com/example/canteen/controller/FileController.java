package com.example.canteen.controller;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.ImageSignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传控制器。
 *
 * 设计要点:
 * - 仅存储图片(校验 Content-Type 前缀 image/)
 * - 文件名 UUID 重命名,防止冲突与路径遍历
 * - 存储到 uploads/ 目录(由 WebConfig.addResourceHandlers 显式映射为 /uploads/**)
 * - 返回相对 URL(如 /uploads/xxx.jpg),前端拼接 base 后直接用
 * - 前端已做 canvas 压缩到 300k 左右,后端不再二次压缩(保持简单)
 * - 返回的 URL 带版本号参数(文件 mtime),便于前端 <img> 缓存失效
 */
@RestController
@RequestMapping("/api/file")
public class FileController {

    /** 上传文件根目录(相对工作目录,WebConfig.addResourceHandlers 映射到此目录) */
    private static final Path UPLOAD_DIR = Paths.get("uploads");

    /** 允许的图片 MIME 类型前缀 */
    private static final String ALLOWED_PREFIX = "image/";

    /** 最大文件大小 5MB(前端已压缩到 300k,此为防御性上限) */
    private static final long MAX_SIZE = 5 * 1024 * 1024;

    @Autowired
    private ImageSignService imageSignService;

    @PostMapping("/upload-image")
    public ApiResponse<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        // 管理级别角色(超管/店管/财务/厨师长/店长)均可上传,与员工头像上传(uploadAvatar)口径一致;
        // 拒绝员工(role=0)与终端(role=3)。厨师长传菜品图、店长传通知图都依赖此接口。
        if (!SecurityContext.hasAdminLevel()) {
            throw new com.example.canteen.exception.SecurityException("仅管理员可上传文件");
        }

        if (file == null || file.isEmpty()) {
            return ApiResponse.error(400, "文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            return ApiResponse.error(400, "文件过大,最大 5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith(ALLOWED_PREFIX)) {
            return ApiResponse.error(400, "仅允许上传图片文件");
        }

        try {
            // 确保目录存在
            if (!Files.exists(UPLOAD_DIR)) {
                Files.createDirectories(UPLOAD_DIR);
            }

            // 生成唯一文件名:保留原扩展名
            String originalName = file.getOriginalFilename();
            String ext = "";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf('.'));
                // 扩展名白名单校验(不允许 svg,防存储型 XSS)
                String lowerExt = ext.toLowerCase();
                if (!lowerExt.matches("\\.(jpg|jpeg|png|gif|webp)")) {
                    ext = ".jpg";
                }
            } else {
                ext = ".jpg";
            }
            String fileName = UUID.randomUUID().toString().replace("-", "") + ext;
            Path target = UPLOAD_DIR.resolve(fileName).normalize().toAbsolutePath();

            // 二次校验:确保最终路径在 UPLOAD_DIR 内(防路径遍历)
            if (!target.startsWith(UPLOAD_DIR.toAbsolutePath())) {
                return ApiResponse.error(400, "非法文件名");
            }

            // magic byte 校验:防止扩展名伪装(Content-Type 和扩展名都可信度有限)
            validateMagicBytes(file, ext.toLowerCase());

            file.transferTo(target.toFile());

            // 返回相对 URL(前端通过 vite proxy 或 nginx 反代访问 /uploads/**)
            // 加 mtime 版本号,便于前端 <img> 缓存失效
            long mtime = Files.getLastModifiedTime(target).toMillis();
            String url = "/uploads/" + fileName + "?v=" + mtime;

            Map<String, Object> result = new HashMap<>();
            result.put("url", url);
            result.put("fileName", fileName);
            result.put("size", file.getSize());
            return ApiResponse.success(result);
        } catch (IOException e) {
            return ApiResponse.error(500, "文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 批量签名图片 URL。
     *
     * 前端在拿到包含 /uploads/ 路径的数据后(如菜品列表、员工列表),
     * 调用此接口批量获取带签名参数的 URL,用于 <img src> 展示。
     * 签名有效期 7 天,前端应缓存签名结果避免频繁请求。
     *
     * 请求体:{ "paths": ["/uploads/xxx.jpg?v=123", "/uploads/yyy.png"] }
     * 响应:  { "urls":  ["/uploads/xxx.jpg?v=123&sig=...&exp=...", ...] }
     */
    @PostMapping("/sign")
    public ApiResponse<Map<String, Object>> signUrls(@RequestBody Map<String, List<String>> body) {
        List<String> paths = body.get("paths");
        if (paths == null || paths.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("urls", List.of());
            return ApiResponse.success(empty);
        }
        List<String> urls = paths.stream()
                .map(imageSignService::sign)
                .toList();
        Map<String, Object> result = new HashMap<>();
        result.put("urls", urls);
        return ApiResponse.success(result);
    }

    /**
     * 校验文件前几字节是否匹配声明的图片类型(magic bytes)。
     * 不匹配则抛 BusinessException,防止扩展名/Content-Type 伪装上传恶意脚本。
     */
    private void validateMagicBytes(MultipartFile file, String lowerExt) throws IOException {
        byte[] header = new byte[12];
        int read;
        try (InputStream is = file.getInputStream()) {
            read = is.read(header);
        }
        if (read <= 0) {
            throw new BusinessException("文件内容与扩展名不符");
        }

        boolean ok = false;
        switch (lowerExt) {
            case ".jpg":
            case ".jpeg":
                // JPEG: FF D8 FF
                ok = read >= 3
                        && (header[0] & 0xFF) == 0xFF
                        && (header[1] & 0xFF) == 0xD8
                        && (header[2] & 0xFF) == 0xFF;
                break;
            case ".png":
                // PNG: 89 50 4E 47 0D 0A 1A 0A
                ok = read >= 8
                        && (header[0] & 0xFF) == 0x89
                        && (header[1] & 0xFF) == 0x50
                        && (header[2] & 0xFF) == 0x4E
                        && (header[3] & 0xFF) == 0x47
                        && (header[4] & 0xFF) == 0x0D
                        && (header[5] & 0xFF) == 0x0A
                        && (header[6] & 0xFF) == 0x1A
                        && (header[7] & 0xFF) == 0x0A;
                break;
            case ".gif":
                // GIF: 47 49 46 38 (GIF8)
                ok = read >= 4
                        && (header[0] & 0xFF) == 0x47
                        && (header[1] & 0xFF) == 0x49
                        && (header[2] & 0xFF) == 0x46
                        && (header[3] & 0xFF) == 0x38;
                break;
            case ".webp":
                // WebP: RIFF....WEBP
                ok = read >= 12
                        && (header[0] & 0xFF) == 0x52
                        && (header[1] & 0xFF) == 0x49
                        && (header[2] & 0xFF) == 0x46
                        && (header[3] & 0xFF) == 0x46
                        && (header[8] & 0xFF) == 0x57
                        && (header[9] & 0xFF) == 0x45
                        && (header[10] & 0xFF) == 0x42
                        && (header[11] & 0xFF) == 0x50;
                break;
            default:
                // 未知扩展名(理论上前面已兜底为 .jpg,这里兜底拒绝)
                ok = false;
        }
        if (!ok) {
            throw new BusinessException("文件内容与扩展名不符");
        }
    }
}
