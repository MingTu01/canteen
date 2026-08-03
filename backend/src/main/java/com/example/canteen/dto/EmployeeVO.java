package com.example.canteen.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 员工视图对象(不含敏感信息)
 */
@Data
public class EmployeeVO {
    private Long id;
    private Long storeId;
    private String cardNo;
    private String phone;
    private String name;
    private String avatar;
    private Long departmentId;
    /** 部门名称(由 Controller 查表填充,非持久化字段) */
    private String departmentName;
    private BigDecimal balance;
    private Integer status;
    /** 是否需要强制修改密码(首次登录使用默认密码时为 1) */
    private Integer mustChangePassword;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static EmployeeVO from(com.example.canteen.entity.Employee e) {
        if (e == null) return null;
        EmployeeVO vo = new EmployeeVO();
        vo.setId(e.getId());
        vo.setStoreId(e.getStoreId());
        vo.setCardNo(e.getCardNo());
        // P2-6 手机号脱敏:138****0001,防止列表接口泄露完整手机号
        vo.setPhone(maskPhone(e.getPhone()));
        vo.setName(e.getName());
        vo.setAvatar(e.getAvatar());
        vo.setDepartmentId(e.getDepartmentId());
        vo.setBalance(e.getBalance());
        vo.setStatus(e.getStatus());
        vo.setMustChangePassword(e.getMustChangePassword());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    /**
     * P2-6 手机号脱敏:保留前 3 后 4,中间 4 位用 * 替换。
     * 长度不足 11 位时原样返回(兼容短号/测试数据)。
     */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
