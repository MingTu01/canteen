package com.example.canteen.controller;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.AdminService;
import com.example.canteen.service.BackupService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 备份恢复控制器。
 *
 * 权限模型:
 * - 全库备份/恢复/下载:仅超级管理员。
 * - 门店备份/恢复/下载:门店管理员仅限本门店;超级管理员可操作任意门店。
 *
 * 备份格式:JSON + GZIP(.json.gz),兼容 H2 与 MySQL,不依赖外部命令。
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backupService;
    private final AdminService adminService;

    public BackupController(BackupService backupService, AdminService adminService) {
        this.backupService = backupService;
        this.adminService = adminService;
    }

    /** 列出当前身份可见的备份。 */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> listBackups() {
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException(SecurityException.FORBIDDEN, "无权访问备份功能");
        }
        return ApiResponse.success(backupService.listBackups());
    }

    /**
     * 创建备份。
     * Body(可选): { "type": "full" | "store", "storeId": 1 }
     * 超管不传 type 默认 full;门店管理员默认 store(本门店)。
     */
    @OperationLog(value = "创建备份", detail = "'类型 ' + (#body == null ? 'full' : #body['type'])")
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createBackup(@RequestBody(required = false) Map<String, Object> body) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException(SecurityException.FORBIDDEN, "无权访问备份功能");
        }
        String type = body == null ? null : (String) body.get("type");
        Long storeId = body == null ? null : toLong(body.get("storeId"));
        if (type == null) {
            // 默认:超管全库,门店管理员本门店
            type = SecurityContext.isSuperAdmin() ? "full" : "store";
        }
        return ApiResponse.success(backupService.createBackup(type, storeId));
    }

    /**
     * 恢复备份(敏感操作,强制密码二次验证)。
     * Body: { "password": "当前登录管理员密码" }
     */
    @OperationLog(value = "恢复备份", detail = "'备份文件 ' + #backupName")
    @PostMapping("/restore/{backupName}")
    public ApiResponse<Map<String, Object>> restoreBackup(@PathVariable String backupName,
                                                          @RequestBody(required = false) Map<String, String> body) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException(SecurityException.FORBIDDEN, "无权访问备份功能");
        }
        // 敏感操作二次验证:恢复会覆盖业务数据,防会话被劫持后直接调用
        adminService.verifyCurrentAdminPassword(body == null ? null : body.get("password"));
        return ApiResponse.success(backupService.restoreBackup(backupName));
    }

    /** 删除备份。 */
    @OperationLog(value = "删除备份", detail = "'备份文件 ' + #backupName")
    @DeleteMapping("/{backupName}")
    public ApiResponse<Void> deleteBackup(@PathVariable String backupName) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException(SecurityException.FORBIDDEN, "无权访问备份功能");
        }
        backupService.deleteBackup(backupName);
        return ApiResponse.success(null);
    }

    /** 下载备份(真实文件流)。 */
    @GetMapping("/download/{backupName}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String backupName) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException(SecurityException.FORBIDDEN, "无权访问备份功能");
        }
        File file = backupService.getBackupFile(backupName);
        FileSystemResource resource = new FileSystemResource(file);
        String encodedName = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedName + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    /**
     * 导入(上传)备份文件。
     * 参数:file=备份文件;restore=true|false(是否立即恢复,默认 false);
     * password=管理员密码(restore=true 时必填,敏感操作二次验证)。
     */
    @OperationLog(value = "导入备份", detail = "'文件名 ' + #file.originalFilename + ' 立即恢复 ' + #restore")
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importBackup(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "restore", defaultValue = "false") boolean restore,
            @RequestParam(value = "password", required = false) String password) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException(SecurityException.FORBIDDEN, "无权访问备份功能");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择备份文件");
        }
        // 导入后立即恢复属于破坏性操作,同样需要密码二次验证
        if (restore) {
            adminService.verifyCurrentAdminPassword(password);
        }
        try {
            Map<String, Object> result = backupService.importBackup(
                    file.getInputStream(), file.getOriginalFilename(), restore);
            return ApiResponse.success(result);
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败: " + e.getMessage());
        }
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
