package com.example.canteen.aspect;

import com.example.canteen.annotation.OperationLog;
import com.example.canteen.service.OperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 操作日志切面(对应 ARCH-04)
 * 对标注了 {@link OperationLog} 的方法,在执行后(无论成功失败)记录日志。
 * 失败时 status=0 并记录 errorMsg;成功时 status=1。
 *
 * detail 字段:优先使用注解 detail() 的 SpEL 模板解析;无模板时留空。
 * 前端展示 operation + detail 组合的人类可读描述。
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    private final OperationLogService operationLogService;
    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

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
        // detail: 优先解析 SpEL 模板;无模板时不再记录原始参数(避免技术细节)
        String detail = resolveDetail(joinPoint, method, annotation);

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
                operationLogService.log(operation, target, detail, 0, thrown.getMessage());
            } else {
                operationLogService.log(operation, target, detail, 1, null);
            }
        } catch (Exception e) {
            log.warn("操作日志切面写入失败: {}", e.getMessage());
        }

        if (thrown != null) {
            throw thrown;
        }
        return result;
    }

    /**
     * 解析注解 detail() 的 SpEL 模板,生成人类可读的操作详情。
     * 无模板时返回空字符串(前端只显示 operation)。
     */
    private String resolveDetail(ProceedingJoinPoint joinPoint, Method method, OperationLog annotation) {
        if (annotation == null) return "";
        String template = annotation.detail();
        if (template == null || template.isBlank()) return "";
        try {
            MethodBasedEvaluationContext ctx = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, joinPoint.getArgs(), paramNameDiscoverer);
            Expression exp = spelParser.parseExpression(template);
            Object value = exp.getValue(ctx);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            log.debug("SpEL 解析失败,回退为模板原文: {}", e.getMessage());
            return template;
        }
    }
}
