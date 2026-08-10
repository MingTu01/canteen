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
    /** 门店名称(超管全局视图时填充,非持久化字段) */
    private String storeName;
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
        // PII 脱敏:仅管理端角色(超管/店管/财务/厨师长/店长)可见完整手机号,
        // 员工本人(H5)、终端、未认证上下文均返回脱敏值,避免 PII 落入 localStorage/终端缓存
        if (com.example.canteen.security.SecurityContext.hasAdminLevel()) {
            vo.setPhone(e.getPhone());
        } else {
            vo.setPhone(maskPhone(e.getPhone()));
        }
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

    /** 手机号脱敏:11 位手机号保留前 3 后 4(138****1234);其他格式保留前 2 后 2,中间打码 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return phone;
        if (phone.length() == 11) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        if (phone.length() > 4) {
            return phone.substring(0, 2) + "****" + phone.substring(phone.length() - 2);
        }
        return "****";
    }
}
