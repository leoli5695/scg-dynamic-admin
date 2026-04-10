package com.leoli.gateway.constants;

import org.springframework.core.Ordered;

/**
 * Filter order constants for Spring Cloud Gateway.
 * <p>
 * Order values define the execution sequence of filters:
 * - Lower values execute first (HIGHEST_PRECEDENCE = Integer.MIN_VALUE)
 * - Higher values execute later
 * <p>
 * Execution flow:
 * 1. Security/Protection filters (negative high values)
 * 2. Pre-processing filters (negative medium values)
 * 3. Business logic filters (negative low values)
 * 4. SCG built-in filters (around 0)
 * 5. Post-processing filters (positive values)
 * 6. Load balancing filters (high positive values)
 *
 * @author leoli
 */
public interface FilterOrderConstants {

    // ============================================================
    // Security & Protection Filters (Execute First)
    // ============================================================

    /**
     * Security filter - XSS/SQL injection protection.
     * Execute first to block malicious requests early.
     */
    int SECURITY_GLOBAL = -500;

    /**
     * IP filter - IP whitelist/blacklist.
     * Execute immediately after security filter to block malicious IPs.
     * High priority to avoid wasting resources on blocked IPs.
     */
    int IP_FILTER = -490;

    /**
     * Access log filter - Request/response logging.
     * Capture entire request lifecycle.
     */
    int ACCESS_LOG = -400;

    /**
     * CORS filter - Cross-origin handling.
     * Execute early to handle preflight requests.
     */
    int CORS = -300;

    /**
     * Trace ID filter - Distributed tracing.
     * Generate trace ID early for full request tracing.
     */
    int TRACE_ID = -300;

    // ============================================================
    // Request Transformation Filters
    // ============================================================

    /**
     * Request transform filter - Modify request before processing.
     * Execute after security but before authentication.
     */
    int REQUEST_TRANSFORM = -255;

    /**
     * Request validation filter - Validate request body/schema.
     * Execute after request transformation.
     */
    int REQUEST_VALIDATION = -254;

    /**
     * Authentication filter - JWT/API Key/Basic auth.
     * Execute after request transformation and validation.
     */
    int AUTHENTICATION = -250;

    /**
     * Mock response filter - Return mock data for testing.
     * Execute right after authentication.
     */
    int MOCK_RESPONSE = -249;

    // ============================================================
    // Circuit Breaker & Timeout Filters
    // ============================================================

    /**
     * Timeout filter - Request timeout handling.
     */
    int TIMEOUT = -200;

    /**
     * API version filter - Version-based routing.
     * Execute before load balancing.
     */
    int API_VERSION = -150;

    /**
     * Circuit breaker filter - Resilience4j integration.
     * Execute before actual service call.
     */
    int CIRCUIT_BREAKER = -100;

    // ============================================================
    // Header & Response Processing Filters
    // ============================================================

    /**
     * Header operation filter - Add/remove headers.
     */
    int HEADER_OP = -50;

    /**
     * Response transform filter - Modify response body.
     * Execute before response is committed.
     */
    int RESPONSE_TRANSFORM = -45;

    // ============================================================
    // Rate Limiter Filters (Using Ordered.HIGHEST_PRECEDENCE)
    // ============================================================

    /**
     * Hybrid rate limiter filter - Redis + local rate limiting.
     * Uses HIGHEST_PRECEDENCE + 20 for early execution.
     */
    int HYBRID_RATE_LIMITER = Ordered.HIGHEST_PRECEDENCE + 20;

    /**
     * Multi-dimensional rate limiter filter.
     * Execute right after hybrid rate limiter.
     */
    int MULTI_DIM_RATE_LIMITER = Ordered.HIGHEST_PRECEDENCE + 21;

    // ============================================================
    // Post-Processing Filters
    // ============================================================

    /**
     * Cache filter - Response caching.
     * Execute after response is ready.
     */
    int CACHE = 50;

    /**
     * Trace capture filter - Capture trace info after request.
     */
    int TRACE_CAPTURE = 100;

    // ============================================================
    // Load Balancing Filters (High Values)
    // ============================================================

    /**
     * Retry filter - Retry failed requests.
     * Execute before route resolution (RouteToRequestUrlFilter = 10000).
     */
    int RETRY = 9999;

    /**
     * Multi-service load balancer filter.
     * Custom load balancing for multi-service routing.
     * Execute after RouteToRequestUrlFilter (10000).
     * <p>
     * Use case: Route to multiple backend services with weight-based distribution,
     * supporting both service discovery (lb://) and static addresses (static://).
     */
    int MULTI_SERVICE_LOAD_BALANCER = 10001;

    /**
     * Discovery load balancer filter.
     * SCG built-in ReactiveLoadBalancerClientFilter is at 10150.
     * This filter handles service discovery based load balancing.
     * <p>
     * Use case: Standard Spring Cloud load balancing for services
     * registered in Nacos/Consul/Eureka (lb:// scheme).
     */
    int DISCOVERY_LOAD_BALANCER = 10150;

    // ============================================================
    // Filter Order Summary (Ascending = Execution Order)
    // ============================================================
    //
    // Order    Filter                          Purpose
    // ------   ------------------------------  ----------------------------------
    // -500     SecurityGlobalFilter            XSS/SQL injection protection
    // -490     IPFilterGlobalFilter            IP whitelist/blacklist
    // -400     AccessLogGlobalFilter           Request/response logging
    // -300     CorsGlobalFilter                CORS handling
    // -300     TraceIdGlobalFilter             Trace ID generation
    // -255     RequestTransformFilter          Request transformation
    // -254     RequestValidationFilter         Request validation
    // -250     AuthenticationGlobalFilter      Authentication
    // -249     MockResponseFilter              Mock response
    // -200     TimeoutGlobalFilter             Timeout handling
    // -150     ApiVersionGlobalFilter          API version routing
    // -100     CircuitBreakerGlobalFilter      Circuit breaker
    //  -50     HeaderOpGlobalFilter            Header operations
    //  -45     ResponseTransformFilter         Response transformation
    //  50      CacheGlobalFilter               Response caching
    //  100     TraceCaptureGlobalFilter        Trace capture
    // 9999     RetryGlobalFilter               Retry logic
    // 10001    MultiServiceLoadBalancerFilter  Multi-service load balancing (static://)
    // 10150    DiscoveryLoadBalancerFilter     Discovery load balancing (lb://)
    //
}