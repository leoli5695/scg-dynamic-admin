package com.leoli.license.client.interceptor;

import com.leoli.license.client.model.LicenseInfo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RequireFeature Annotation - Mark methods requiring specific license feature
 * <p>
 * Apply this annotation to Controller methods to enforce license-based access control.
 * The LicenseAspect will intercept and validate the feature before method execution.
 * <p>
 * Usage example:
 * <pre>
 * &#64;RequireFeature(Feature.AI_COPilot)
 * public ApiResponse&lt;String&gt; analyzeMetrics() {
 *     // Only accessible with ENTERPRISE license
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireFeature {

    /**
     * Required feature for this method
     */
    LicenseInfo.Feature value();

    /**
     * Optional custom message for unauthorized access
     */
    String message() default "";
}