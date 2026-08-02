package com.example.canteen.security;

import com.example.canteen.entity.Admin;
import com.example.canteen.entity.Employee;
import com.example.canteen.mapper.AdminMapper;
import com.example.canteen.mapper.EmployeeMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 密码新鲜度校验:若 token 签发时间 < 用户密码修改时间,则视为旧 token,拒绝。
 *
 * 仅对 role=0(员工)和管理员(1/2/4/5/6)做此校验;role=3(终端)跳过。
 * 含 P1-5 软删除/禁用账号后 token 失效校验。
 * 给 5 秒宽限期,避免时钟漂移导致刚改完密码就被拒。
 */
@Component
public class PasswordFreshnessValidator {
    private final EmployeeMapper employeeMapper;
    private final AdminMapper adminMapper;

    public PasswordFreshnessValidator(EmployeeMapper employeeMapper, AdminMapper adminMapper) {
        this.employeeMapper = employeeMapper;
        this.adminMapper = adminMapper;
    }

    /**
     * 校验密码新鲜度。
     *
     * @param userId   用户 ID(claims.id)
     * @param role     角色(0=员工,1/2/4/5/6=管理员,3=终端)
     * @param iatEpoch token 签发时间(秒级 epoch)
     * @return 失败原因(null 表示通过)
     */
    public String checkPasswordFreshness(Long userId, Integer role, Long iatEpoch) {
        if (userId == null || iatEpoch == null || role == null) {
            return null;
        }

        LocalDateTime passwordUpdatedAt = null;

        if (role == 0) {
            // 员工
            Employee emp = employeeMapper.selectById(userId);
            if (emp == null) {
                // fail-closed:员工记录不存在(硬删除)则 token 失效
                return "账号不存在或已删除";
            }
            // P1-5 软删除/禁用员工后 token 失效
            if (emp.getIsDeleted() != null && emp.getIsDeleted() == 1) {
                return "账号已失效";
            }
            if (emp.getStatus() != null && emp.getStatus() != 1) {
                return "账号已失效";
            }
            passwordUpdatedAt = emp.getPasswordUpdatedAt();
        } else if (role == 1 || role == 2
                || role == SecurityContext.ROLE_FINANCE
                || role == SecurityContext.ROLE_CHEF
                || role == SecurityContext.ROLE_STORE_MANAGER) {
            // 管理员(1/2/4/5/6)
            Admin admin = adminMapper.selectById(userId);
            if (admin == null) {
                // fail-closed:管理员记录不存在(硬删除)则 token 失效
                return "账号不存在或已删除";
            }
            // P1-5 禁用管理员后 token 失效
            if (admin.getStatus() != null && admin.getStatus() != 1) {
                return "账号已失效";
            }
            passwordUpdatedAt = admin.getPasswordUpdatedAt();
        }
        // role=3(终端)或其他:跳过

        if (passwordUpdatedAt == null) {
            return null;
        }

        long pwdEpoch = passwordUpdatedAt.atZone(ZoneId.systemDefault()).toInstant().getEpochSecond();
        // 给 5 秒宽限期,避免时钟漂移导致刚改完密码就被拒
        if (iatEpoch + 5 < pwdEpoch) {
            return "密码已修改,请重新登录";
        }
        return null;
    }
}
