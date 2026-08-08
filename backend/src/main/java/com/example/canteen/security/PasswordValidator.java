package com.example.canteen.security;

import com.example.canteen.exception.BusinessException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * P2-1 密码复杂度校验工具。
 *
 * 规则:
 *   - 长度 ≥ 8
 *   - 必须同时包含字母和数字
 *   - 允许任意字符(包括所有特殊符号),只要满足上述复杂度即可
 *   - 拒绝常见弱密码
 *
 * 注意:默认初始密码(如 12345678)不经过此校验,因为首次登录会强制修改(mustChangePassword=1)。
 * 仅对用户显式设置的密码调用 validate()。
 */
public final class PasswordValidator {

    private PasswordValidator() {}

    private static final Pattern PATTERN =
            Pattern.compile("^(?=.*[a-zA-Z])(?=.*\\d).{8,}$", Pattern.DOTALL);

    private static final Set<String> COMMON_WEAK = Set.of(
            "12345678", "password", "11111111",
            "abc12345", "qwerty12", "admin123",
            "password1", "12345678a", "aaaa1234"
    );

    /**
     * 校验密码复杂度,不合规则抛出 BusinessException。
     *
     * @param password 待校验密码(明文)
     */
    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException("密码至少 8 位");
        }
        if (!PATTERN.matcher(password).matches()) {
            throw new BusinessException("密码必须同时包含字母和数字");
        }
        if (COMMON_WEAK.contains(password.toLowerCase())) {
            throw new BusinessException("密码过于简单,请使用更复杂的密码");
        }
    }
}
