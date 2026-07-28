package com.example.canteen.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员视图对象(不含 password)
 */
@Data
public class AdminVO {
    private Long id;
    private String username;
    private String name;
    private Long storeId;
    private Integer role;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AdminVO from(com.example.canteen.entity.Admin admin) {
        if (admin == null) return null;
        AdminVO vo = new AdminVO();
        vo.setId(admin.getId());
        vo.setUsername(admin.getUsername());
        vo.setName(admin.getName());
        vo.setStoreId(admin.getStoreId());
        vo.setRole(admin.getRole());
        vo.setStatus(admin.getStatus());
        vo.setCreatedAt(admin.getCreatedAt());
        vo.setUpdatedAt(admin.getUpdatedAt());
        return vo;
    }
}
