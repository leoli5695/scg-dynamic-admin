package com.leoli.license.client.interceptor;

import com.leoli.license.client.config.LicenseProperties;
import com.leoli.license.client.exception.FeatureNotLicensedException;
import com.leoli.license.client.model.LicenseInfo;
import com.leoli.license.client.validator.FeatureGatekeeper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * License Aspect - Controller-level Feature Interception
 * <p>
 * Intercepts gateway-admin Controller methods to check feature licensing.
 * Uses AOP to transparently block access to premium features.
 * <p>
 * Usage patterns:
 * 1. Explicit annotation: @RequireFeature(Feature.AI_COPilot)
 * 2. Implicit mapping: Based on controller class name
 * <p>
 * Returns 403 response with upgrade hint when feature is not licensed.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LicenseAspect {

    private final FeatureGatekeeper featureGatekeeper;
    private final LicenseProperties properties;

    /**
     * Controller-to-Feature mapping for implicit interception
     */
    private static final Map<String, LicenseInfo.Feature> CONTROLLER_FEATURE_MAP = Map.ofEntries(
        // Enterprise features
        Map.entry("AiCopilotController", LicenseInfo.Feature.AI_COPilot),
        Map.entry("StressTestController", LicenseInfo.Feature.STRESS_TEST),
        Map.entry("RequestReplayController", LicenseInfo.Feature.REQUEST_REPLAY),
        Map.entry("TrafficTopologyController", LicenseInfo.Feature.TOPOLOGY_VIEW),
        Map.entry("KubernetesController", LicenseInfo.Feature.KUBERNETES_MANAGER),
        Map.entry("SslCertificateController", LicenseInfo.Feature.SSL_MANAGEMENT),
        Map.entry("AuditLogController", LicenseInfo.Feature.AUDIT_LOG),
        Map.entry("RequestTraceController", LicenseInfo.Feature.FULL_CHAIN_TRACE),
        Map.entry("AccessLogConfigController", LicenseInfo.Feature.AUDIT_LOG),
        Map.entry("DiagnosticController", LicenseInfo.Feature.FULL_CHAIN_TRACE),
        Map.entry("FullChainTraceController", LicenseInfo.Feature.FULL_CHAIN_TRACE),
        Map.entry("FilterChainController", LicenseInfo.Feature.FULL_CHAIN_TRACE),
        Map.entry("MiddlewareController", LicenseInfo.Feature.FULL_CHAIN_TRACE),
        
        // Advanced features
        Map.entry("AuthPolicyController", LicenseInfo.Feature.JWT_AUTH),
        Map.entry("StrategyController", LicenseInfo.Feature.CIRCUIT_BREAKER),
        Map.entry("AlertController", LicenseInfo.Feature.PROMETHEUS_MONITORING),
        Map.entry("MonitorController", LicenseInfo.Feature.PROMETHEUS_MONITORING),
        Map.entry("AnalyticsController", LicenseInfo.Feature.PROMETHEUS_MONITORING)
    );

    /**
     * Intercept methods with @RequireFeature annotation
     */
    @Around("@annotation(com.leoli.license.client.interceptor.RequireFeature)")
    public Object checkRequireFeatureAnnotation(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        RequireFeature annotation = method.getAnnotation(RequireFeature.class);
        
        if (annotation != null) {
            LicenseInfo.Feature feature = annotation.value();
            try {
                featureGatekeeper.requireFeature(feature);
            } catch (FeatureNotLicensedException e) {
                if (properties.isLogUnlicensedAccess()) {
                    log.warn("Feature blocked: {} on method {} - required: {}, current: {}",
                        feature.name(), method.getName(), 
                        e.getRequiredEdition(), e.getCurrentEdition());
                }
                return createForbiddenResponse(e);
            }
        }
        
        return pjp.proceed();
    }

    /**
     * Intercept all gateway-admin controller methods (implicit feature mapping)
     * This provides automatic protection without explicit annotations.
     */
    @Around("execution(* com.leoli.gateway.admin.controller..*.*(..))")
    public Object checkImplicitFeatureMapping(ProceedingJoinPoint pjp) throws Throwable {
        String controllerName = pjp.getTarget().getClass().getSimpleName();
        
        // Check if controller requires a specific feature
        LicenseInfo.Feature requiredFeature = CONTROLLER_FEATURE_MAP.get(controllerName);
        
        if (requiredFeature != null) {
            if (!featureGatekeeper.hasFeature(requiredFeature)) {
                String currentEdition = featureGatekeeper.getCurrentEdition();
                
                if (properties.isLogUnlicensedAccess()) {
                    log.warn("Controller blocked: {} - required: {}, current: {}",
                        controllerName, requiredFeature.getRequiredEdition(), currentEdition);
                }
                
                FeatureNotLicensedException e = new FeatureNotLicensedException(
                    requiredFeature, currentEdition);
                return createForbiddenResponse(e);
            }
        }
        
        return pjp.proceed();
    }

    /**
     * Intercept specific enterprise-only endpoints
     */
    @Around("execution(* com.leoli.gateway.admin.controller.AiCopilotController.*(..))")
    public Object checkAiCopilotAccess(ProceedingJoinPoint pjp) throws Throwable {
        return checkEnterpriseFeature(pjp, LicenseInfo.Feature.AI_COPilot);
    }

    @Around("execution(* com.leoli.gateway.admin.controller.StressTestController.*(..))")
    public Object checkStressTestAccess(ProceedingJoinPoint pjp) throws Throwable {
        return checkEnterpriseFeature(pjp, LicenseInfo.Feature.STRESS_TEST);
    }

    @Around("execution(* com.leoli.gateway.admin.controller.RequestReplayController.*(..))")
    public Object checkRequestReplayAccess(ProceedingJoinPoint pjp) throws Throwable {
        return checkEnterpriseFeature(pjp, LicenseInfo.Feature.REQUEST_REPLAY);
    }

    /**
     * Helper method to check enterprise feature
     */
    private Object checkEnterpriseFeature(ProceedingJoinPoint pjp, LicenseInfo.Feature feature) 
            throws Throwable {
        if (!featureGatekeeper.hasFeature(feature)) {
            String currentEdition = featureGatekeeper.getCurrentEdition();
            
            if (properties.isLogUnlicensedAccess()) {
                log.warn("Enterprise feature blocked: {} - current edition: {}",
                    feature.name(), currentEdition);
            }
            
            FeatureNotLicensedException e = new FeatureNotLicensedException(
                feature, currentEdition);
            return createForbiddenResponse(e);
        }
        
        return pjp.proceed();
    }

    /**
     * Create forbidden response object
     * This returns a response compatible with gateway-admin's ApiResponse format
     */
    private Object createForbiddenResponse(FeatureNotLicensedException e) {
        // Return a map that will be serialized as JSON response
        // Compatible with ApiResponse format
        return Map.of(
            "code", e.getErrorCode(),
            "message", e.getMessage(),
            "data", Map.of(
                "feature", e.getFeature() != null ? e.getFeature().name() : "UNKNOWN",
                "requiredEdition", e.getRequiredEdition(),
                "currentEdition", e.getCurrentEdition(),
                "upgradeUrl", "/api/licenses/upgrade"
            ),
            "timestamp", System.currentTimeMillis()
        );
    }
}