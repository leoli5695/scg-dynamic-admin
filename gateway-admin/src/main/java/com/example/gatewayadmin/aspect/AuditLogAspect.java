package com.example.gatewayadmin.aspect;

import com.example.gatewayadmin.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * Audit log aspect for automatically recording configuration changes.
 *
 * @author leoli
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    @Autowired
    private AuditLogService auditLogService;

    private static final ThreadLocal<Long> startTime = new ThreadLocal<>();

    /**
     * Record start time before method execution.
     */
    @Before("execution(* com.example.gatewayadmin.controller..*.*(..)) && " +
            "(args(..,org.springframework.web.bind.annotation.RequestBody..) || " +
            " args(..,org.springframework.web.bind.annotation.PathVariable..))")
    public void before(JoinPoint joinPoint) {
        startTime.set(System.currentTimeMillis());
    }

    /**
     * Record successful operations.
     */
    @AfterReturning(pointcut = "execution(* com.example.gatewayadmin.controller..*.*(..)) && " +
                               "(within(com.example.gatewayadmin.controller.RouteController) || " +
                               " within(com.example.gatewayadmin.controller.ServiceController) || " +
                               " within(com.example.gatewayadmin.controller.PluginController))",
                    returning = "result")
    public void afterReturning(JoinPoint joinPoint, Object result) {
        recordAuditLog(joinPoint, "SUCCESS");
    }

    /**
     * Record failed operations.
     */
    @AfterThrowing(pointcut = "execution(* com.example.gatewayadmin.controller..*.*(..)) && " +
                              "(within(com.example.gatewayadmin.controller.RouteController) || " +
                              " within(com.example.gatewayadmin.controller.ServiceController) || " +
                              " within(com.example.gatewayadmin.controller.PluginController))",
                   throwing = "ex")
    public void afterThrowing(JoinPoint joinPoint, Exception ex) {
        log.error("Operation failed", ex);
        recordAuditLog(joinPoint, "FAILED");
    }

    /**
     * Record audit log.
     */
    private void recordAuditLog(JoinPoint joinPoint, String status) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            
            // Get current user from security context
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String operator = authentication != null && authentication.isAuthenticated() ? 
                            authentication.getName() : "anonymous";
            
            // Get request info
            ServletRequestAttributes attributes = 
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attributes != null ? attributes.getRequest() : null;
            String ipAddress = request != null ? request.getRemoteAddr() : "unknown";
            
            // Determine operation type and target
            String methodName = method.getName();
            String operationType = getOperationType(methodName, status);
            String targetType = getTargetType(joinPoint);
            String targetId = getTargetId(joinPoint, methodName);
            
            // Record asynchronously
            auditLogService.recordAuditLog(operator, operationType, targetType, targetId, ipAddress);
            
            log.info("Audit: {} {} {} by {} in {}ms", 
                    operationType, targetType, targetId, operator, 
                    System.currentTimeMillis() - startTime.get());
        } catch (Exception ex) {
            log.error("Failed to record audit log", ex);
        } finally {
            startTime.remove();
        }
    }

    /**
     * Get operation type from method name.
     */
    private String getOperationType(String methodName, String status) {
        if ("FAILED".equals(status)) {
            return "FAILED";
        }
        
        if (methodName.startsWith("create") || methodName.startsWith("add")) {
            return "CREATE";
        } else if (methodName.startsWith("update") || methodName.startsWith("modify")) {
            return "UPDATE";
        } else if (methodName.startsWith("delete") || methodName.startsWith("remove")) {
            return "DELETE";
        } else if (methodName.startsWith("get") || methodName.startsWith("list") || 
                   methodName.startsWith("query")) {
            return "READ";
        }
        return "OTHER";
    }

    /**
     * Get target type from controller class name.
     */
    private String getTargetType(JoinPoint joinPoint) {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        if (className.contains("Route")) {
            return "ROUTE";
        } else if (className.contains("Service")) {
            return "SERVICE";
        } else if (className.contains("Plugin")) {
            return "PLUGIN";
        }
        return "UNKNOWN";
    }

    /**
     * Get target ID from method arguments.
     */
    private String getTargetId(JoinPoint joinPoint, String methodName) {
        Object[] args = joinPoint.getArgs();
        
        // Try to find path variable (usually second parameter for update/delete)
        for (Object arg : args) {
            if (arg instanceof String && !arg.toString().isEmpty()) {
                return arg.toString();
            }
        }
        
        return "unknown";
    }
}
