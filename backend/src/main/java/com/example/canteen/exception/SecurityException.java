package com.example.canteen.exception;

/**
 * 安全异常:对应 401/403
 * 401=未认证(token 缺失/无效/过期);403=已认证但无权限
 */
public class SecurityException extends RuntimeException {
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;

    private final int code;

    public SecurityException(String message) {
        this(403, message);
    }

    public SecurityException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
