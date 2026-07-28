package com.example.canteen.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解(对应 ARCH-04)
 * 标注在 Controller 方法上,执行后由 OperationLogAspect 记录到 sys_operation_log。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    /** 操作描述,如 "创建备份"、"修改系统配置" */
    String value();
}
