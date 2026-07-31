package com.example.canteen.service;

import com.example.canteen.entity.Admin;
import com.example.canteen.entity.OperationLog;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.OperationLogMapper;
import com.example.canteen.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志服务:写入 sys_operation_log 表(对应 ARCH-04)
 * 当前操作人从 SecurityContext 取出(adminId),adminName 查 Admin 表填充。
 * IP 从当前请求取出。任何写入失败均吞掉,避免日志影响业务主流程。
 */
@Slf4j
@Service
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;
    private final AdminMapper adminMapper;

    public OperationLogService(OperationLogMapper operationLogMapper, AdminMapper adminMapper) {
        this.operationLogMapper = operationLogMapper;
        this.adminMapper = adminMapper;
    }

    public void log(String operation, String target, String detail) {
        log(operation, target, detail, 1, null);
    }

    /**
     * 写入操作日志(指定状态与错误信息)。
     * @param operation 操作描述(如 "发布菜单")
     * @param target    目标方法(ControllerClass.method),仅内部记录
     * @param detail    操作详情(如 "日期 2026-08-01"),人类可读
     * @param status    1=成功,0=失败
     * @param errorMsg  失败时的错误信息(成功时传 null)
     */
    public void log(String operation, String target, String detail, int status, String errorMsg) {
        try {
            OperationLog entity = new OperationLog();
            entity.setOperation(operation);
            Long adminId = SecurityContext.currentAdminId();
            entity.setAdminId(adminId);
            entity.setStoreId(SecurityContext.currentStoreId());
            // 查询操作人姓名,填充 adminName
            if (adminId != null) {
                try {
                    Admin admin = adminMapper.selectById(adminId);
                    if (admin != null) {
                        entity.setAdminName(admin.getName() != null ? admin.getName() : admin.getUsername());
                    }
                } catch (Exception e) {
                    log.debug("查询操作人姓名失败: adminId={}, error={}", adminId, e.getMessage());
                }
            }
            // target 作为 method 字段记录(技术调试用,前端不展示)
            entity.setMethod(target);
            // detail 作为 params 字段记录(人类可读的操作详情)
            entity.setParams(detail);
            entity.setIp(currentIp());
            entity.setStatus(status);
            entity.setErrorMsg(errorMsg);
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("写入操作日志失败: operation={}, error={}", operation, e.getMessage());
        }
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                int comma = ip.indexOf(',');
                return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
            }
            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip;
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
