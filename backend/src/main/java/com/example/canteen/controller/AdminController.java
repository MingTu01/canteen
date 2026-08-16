package com.example.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.annotation.OperationLog;
import com.example.canteen.dto.AdminVO;
import com.example.canteen.dto.ApiResponse;
import com.example.canteen.dto.LoginDTO;
import com.example.canteen.entity.Admin;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.security.AuthCookieUtil;
import com.example.canteen.security.SecurityContext;
import com.example.canteen.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;
    private final AdminMapper adminMapper;
    private final AuthCookieUtil authCookieUtil;

    public AdminController(AdminService adminService, AdminMapper adminMapper,
                           AuthCookieUtil authCookieUtil) {
        this.adminService = adminService;
        this.adminMapper = adminMapper;
        this.authCookieUtil = authCookieUtil;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginDTO loginDTO,
                                                   HttpServletRequest httpRequest,
                                                   HttpServletResponse httpResponse) {
        try {
            Map<String, Object> result = adminService.login(loginDTO);
            // 登录成功后写入 auth_token Cookie(HttpOnly + SameSite=Strict)
            Object tokenObj = result.get("token");
            if (tokenObj instanceof String token) {
                authCookieUtil.setAdminCookie(httpResponse, token, httpRequest);
            }
            return ApiResponse.success(result);
        } catch (SecurityException e) {
            // 登录认证失败(密码错误/账号锁定)返回 401,其他异常交由 GlobalExceptionHandler
            return ApiResponse.error(401, e.getMessage());
        }
    }

    @OperationLog(value = "创建管理员", detail = "'账号 ' + #admin.username + ' 姓名 ' + #admin.name")
    @PostMapping
    public ApiResponse<AdminVO> createAdmin(@RequestBody Admin admin) {
        SecurityContext.checkSuperAdmin("仅超级管理员可创建管理员账号");
        return ApiResponse.success(adminService.createAdmin(admin));
    }

    @OperationLog(value = "更新管理员", detail = "#resolver.adminBrief(#id)")
    @PutMapping("/{id}")
    public ApiResponse<AdminVO> updateAdmin(@PathVariable Long id, @RequestBody Admin admin) {
        // 门店管理员可改本店非超管账号,超管可改任意账号(Service 层做具体校验)
        SecurityContext.checkStoreAdminOrAbove("仅管理员可修改管理员账号");
        return ApiResponse.success(adminService.updateAdmin(id, admin));
    }

    @OperationLog(value = "修改密码", detail = "#resolver.adminBrief(#id)")
    @PutMapping("/{id}/password")
    public ApiResponse<Void> changePassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // 本人可改自己密码,或超管可改任意密码
        Long currentId = SecurityContext.currentAdminId();
        if (currentId == null || (!currentId.equals(id) && !SecurityContext.isSuperAdmin())) {
            throw new SecurityException("无权修改他人密码");
        }
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        adminService.changePassword(id, oldPassword, newPassword);
        return ApiResponse.success(null);
    }

    /**
     * 敏感操作二次验证:校验当前登录管理员的密码。
     * Body: { "action": "操作描述"(可选,记入操作日志), "password": "管理员密码" }
     * 前端在执行删除食堂/恢复备份等破坏性操作前调用;破坏性接口本身也会强制校验,此处用于预校验并记录日志。
     */
    @OperationLog(value = "敏感操作密码验证", detail = "#body == null ? '' : ('操作 ' + #body['action'])")
    @PostMapping("/verify-password")
    public ApiResponse<Void> verifyPassword(@RequestBody Map<String, String> body) {
        if (!SecurityContext.hasAdminLevel()) {
            throw new SecurityException("无权执行该操作");
        }
        adminService.verifyCurrentAdminPassword(body.get("password"));
        return ApiResponse.success(null);
    }

    @OperationLog(value = "删除管理员", detail = "#resolver.adminBrief(#id)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteAdmin(@PathVariable Long id) {
        SecurityContext.checkSuperAdmin("仅超级管理员可删除管理员账号");
        adminService.deleteAdmin(id);
        return ApiResponse.success(null);
    }

    @GetMapping
    public ApiResponse<List<AdminVO>> listAdmins() {
        if (!SecurityContext.isSuperAdmin()) {
            throw new SecurityException("仅超级管理员可查看管理员列表");
        }
        // 不再过滤 status=1:显示含禁用在内的全部账号。
        // 修复 BUG:历史"删除食堂"禁用的店铺管理员因列表过滤而不可见,
        // 导致既无法启用救回、也无法确认账号存在(创建同名又提示已存在)。
        // 前端状态列已区分"启用/禁用"标签,超管可编辑救回(重新启用+绑定食堂)。
        List<Admin> admins = adminMapper.selectList(
                new LambdaQueryWrapper<Admin>().orderByDesc(Admin::getStatus).orderByAsc(Admin::getId));
        List<AdminVO> voList = admins.stream().map(AdminVO::from).collect(Collectors.toList());
        return ApiResponse.success(voList);
    }
}
