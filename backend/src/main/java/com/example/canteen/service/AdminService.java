package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.dto.AdminVO;
import com.example.canteen.dto.LoginDTO;
import com.example.canteen.entity.Admin;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.security.JwtAuthenticationFilter;
import com.example.canteen.security.JwtTokenProvider;
import com.example.canteen.security.LoginRateLimiter;
import com.example.canteen.security.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class AdminService {
    private final AdminMapper adminMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    public AdminService(AdminMapper adminMapper, JwtTokenProvider jwtTokenProvider,
                        PasswordEncoder passwordEncoder, JwtAuthenticationFilter jwtFilter) {
        this.adminMapper = adminMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = jwtFilter.getRateLimiter();
    }

    public Map<String, Object> login(LoginDTO loginDTO) {
        String username = loginDTO.getUsername();
        rateLimiter.checkLocked(username);

        Admin admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getUsername, username)
                .eq(Admin::getStatus, 1));

        if (admin == null) {
            rateLimiter.recordFail(username);
            throw new SecurityException(SecurityException.UNAUTHORIZED, "用户名或密码错误");
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), admin.getPassword())) {
            rateLimiter.recordFail(username);
            throw new SecurityException(SecurityException.UNAUTHORIZED, "用户名或密码错误");
        }

        rateLimiter.recordSuccess(username);
        String token = jwtTokenProvider.generateToken(admin);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("admin", AdminVO.from(admin));
        return result;
    }

    public AdminVO createAdmin(Admin admin) {
        if (!SecurityContext.isSuperAdmin()) {
            throw new SecurityException("仅超级管理员可创建管理员账号");
        }
        Long count = adminMapper.selectCount(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getUsername, admin.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }
        if (admin.getPassword() == null || admin.getPassword().length() < 8) {
            throw new BusinessException("密码至少8位");
        }
        admin.setPassword(passwordEncoder.encode(admin.getPassword()));
        // 初始化密码更新时间
        if (admin.getPasswordUpdatedAt() == null) {
            admin.setPasswordUpdatedAt(LocalDateTime.now());
        }
        if (admin.getStatus() == null) admin.setStatus(1);
        if (admin.getRole() == null) admin.setRole(2);
        adminMapper.insert(admin);
        return AdminVO.from(admin);
    }

    public AdminVO updateAdmin(Long id, Admin admin) {
        Admin existing = adminMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("管理员不存在");
        }
        // 门店管理员只能改自己门店下的非超管账号
        if (!SecurityContext.isSuperAdmin()) {
            if (!existing.getStoreId().equals(SecurityContext.currentStoreId())) {
                throw new SecurityException("无权修改其他门店管理员");
            }
            if (existing.getRole() == 1) {
                throw new SecurityException("无权修改超级管理员");
            }
        }
        existing.setName(admin.getName());
        if (!SecurityContext.isSuperAdmin()) {
            if (admin.getRole() != null && admin.getRole() == 1) {
                throw new BusinessException("无权设置超级管理员角色");
            }
            existing.setStoreId(existing.getStoreId());
            existing.setRole(existing.getRole());
        } else {
            existing.setStoreId(admin.getStoreId());
            existing.setRole(admin.getRole());
        }
        existing.setStatus(admin.getStatus());
        adminMapper.updateById(existing);
        return AdminVO.from(existing);
    }

    public void changePassword(Long id, String oldPassword, String newPassword) {
        // 限流 key:基于管理员 ID,防止原密码暴力枚举
        String rateLimitKey = "pwd:admin:" + id;
        rateLimiter.checkLocked(rateLimitKey);

        Admin admin = adminMapper.selectById(id);
        if (admin == null) {
            throw new BusinessException("管理员不存在");
        }
        // 非超管只能改自己的密码
        Long currentId = SecurityContext.currentAdminId();
        if (!SecurityContext.isSuperAdmin() && !id.equals(currentId)) {
            throw new SecurityException("无权修改他人密码");
        }
        // 改自己的密码需要校验旧密码;超管重置他人密码不需要
        if (id.equals(currentId) && !passwordEncoder.matches(oldPassword, admin.getPassword())) {
            rateLimiter.recordFail(rateLimitKey);
            throw new BusinessException("原密码错误");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessException("新密码至少8位");
        }
        admin.setPassword(passwordEncoder.encode(newPassword));
        // 同步更新密码修改时间,使旧 token 失效
        admin.setPasswordUpdatedAt(LocalDateTime.now());
        adminMapper.updateById(admin);
        rateLimiter.recordSuccess(rateLimitKey);
    }

    public void deleteAdmin(Long id) {
        if (!SecurityContext.isSuperAdmin()) {
            throw new SecurityException("仅超级管理员可删除管理员账号");
        }
        if (id.equals(SecurityContext.currentAdminId())) {
            throw new BusinessException("不能删除自己");
        }
        adminMapper.deleteById(id);
    }
}
