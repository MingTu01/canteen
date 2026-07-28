package com.example.canteen.aspect;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 操作日志切面(对应 ARCH-04)
 * 对标注了 {@link OperationLog} 的方法,在执行后(无论成功失败)记录日志。
 * 失败时 status=0 并记录 errorMsg;成功时 status=1。
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    private final OperationLogService operationLogService;

    public OperationLogAspect(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Around("@annotation(com.example.canteen.annotation.OperationLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        OperationLog annotation = method.getAnnotation(OperationLog.class);
        String operation = annotation == null ? method.getName() : annotation.value();
        String target = joinPoint.getTarget().getClass().getSimpleName() + "." + method.getName();
        String detail = buildArgs(joinPoint);

        Throwable thrown = null;
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            thrown = t;
            result = null;
        }

        try {
            if (thrown != null) {
                operationLogService.log(operation, target, detail + " | error=" + thrown.getMessage());
            } else {
                operationLogService.log(operation, target, detail);
            }
        } catch (Exception e) {
            log.warn("操作日志切面写入失败: {}", e.getMessage());
        }

        if (thrown != null) {
            throw thrown;
        }
        return result;
    }

    private String buildArgs(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            String methodName = method.getName().toLowerCase();
            // P2-8 敏感方法整体脱敏,避免密码/充值等明文写入日志
            if (methodName.contains("password") || methodName.contains("login") || methodName.contains("recharge")) {
                return "[REDACTED]";
            }
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) return "{}";
            String[] paramNames = signature.getParameterNames();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                // 对参数名含 password/pwd 的参数脱敏
                String paramName = (paramNames != null && i < paramNames.length) ? paramNames[i].toLowerCase() : "";
                if (paramName.contains("password") || paramName.contains("pwd")) {
                    sb.append("[REDACTED]");
                } else {
                    sb.append(args[i] == null ? "null" : args[i].toString());
                }
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
