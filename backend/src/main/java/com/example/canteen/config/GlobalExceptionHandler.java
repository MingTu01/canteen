package com.example.canteen.config;

import com.example.canteen.dto.ApiResponse;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.exception.SecurityException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * - 业务异常(BusinessException) -> 4xx,使用异常自带的 code
 * - 安全异常(SecurityException) -> 401/403,使用异常自带的 code
 * - 参数校验失败(MethodArgumentNotValidException / ConstraintViolationException) -> 400
 * - 兜底 RuntimeException -> 500,统一返回"服务器内部错误",不泄露堆栈
 * 每个 handler 均通过 log.error 记录完整异常,便于排查
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 安全异常:401 未认证 / 403 已认证但无权限
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityException(SecurityException ex) {
        log.error("安全异常: code={}, message={}", ex.getCode(), ex.getMessage(), ex);
        HttpStatus status = ex.getCode() == SecurityException.UNAUTHORIZED
                ? HttpStatus.UNAUTHORIZED
                : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    /**
     * 业务异常:返回异常自带 code(默认 400)
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.error("业务异常: code={}, message={}", ex.getCode(), ex.getMessage(), ex);
        int code = ex.getCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(ApiResponse.error(code, ex.getMessage()));
    }

    /**
     * @Valid Body 参数校验失败
     * 提取所有 FieldError,拼接为 "field: message; field: message" 形式
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<String> messages = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field;
            String msg = error.getDefaultMessage();
            if (error instanceof FieldError fe) {
                field = fe.getField();
            } else {
                field = error.getObjectName();
            }
            messages.add(field + ": " + (msg == null ? "invalid" : msg));
        }
        String joined = messages.stream().collect(Collectors.joining("; "));
        log.error("参数校验失败: {}", joined, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, "参数验证失败: " + joined));
    }

    /**
     * 查询参数 / 路径参数 @Validated 校验失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> {
                    ConstraintViolation<?> cv = v;
                    String path = cv.getPropertyPath() == null ? "" : cv.getPropertyPath().toString();
                    return path + ": " + cv.getMessage();
                })
                .collect(Collectors.joining("; "));
        log.error("参数校验失败: {}", message, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(400, "参数验证失败: " + message));
    }

    /**
     * 静态资源/接口不存在(NoResourceFoundException)
     * Spring Boot 6.x 新增异常:访问不存在的静态资源(如 /、/favicon.ico)时抛出。
     * 旧版 Spring Boot 返回 404,6.x 改为抛异常,若不单独处理会被下面的
     * RuntimeException 兜底捕获并返回 500,误导前端以为是服务器错误。
     * 此处显式返回 404,与旧行为保持一致。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        // 静态资源不存在是正常情况(如 favicon.ico),不记录 ERROR 日志,仅 DEBUG
        log.debug("资源不存在: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "资源不存在"));
    }

    /**
     * 兜底:其他运行时异常一律返回 500,不向前端暴露堆栈
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex) {
        log.error("服务器内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务器内部错误"));
    }

    /**
     * 兜底:其他受检异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("服务器内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务器内部错误"));
    }
}
