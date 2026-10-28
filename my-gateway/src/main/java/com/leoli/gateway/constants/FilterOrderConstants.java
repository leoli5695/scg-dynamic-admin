package com.leoli.gateway.constants;

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
    /**
     * Security (WAF) global filter.
     *
     * <p>FIX: was -500, i.e. BEFORE IP_FILTER (-490). The WAF buffers and regex-scans up to
     * {@code gateway.security.max-body-size-mb} of request body, so an address that was already
     * blacklisted could still make the gateway buffer a full body before being rejected - and the
     * scanning runs on boundedElastic, which the token-quota, semantic-cache and SSL-refresh paths
     * share, so that is a way to starve unrelated features. -480 keeps the WAF ahead of
     * AUTHENTICATION (-250), which is the point of a WAF (unauthenticated traffic must be scanned
     * too), while letting the cheap IP rejection happen first.
     *
     * <p>Deliberately NOT changed: body scanning still covers multipart and every other declared
     * content type. Skipping multipart would let SQLi/XSS ride through in ordinary form fields and
     * would revert the explicit P0 fix recorded in SecurityGlobalFilter.hasBody(). The memory-pressure
     * concern is addressed by the now-configurable size cap instead.
     */
    int SECURITY_GLOBAL = -480;

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
     * Should execute before Trace ID to handle CORS errors cleanly.
     */
    int CORS = -301;

    /**
     * Trace ID filter - distributed tracing.
     * <p>
     * NOTE: TraceIdGlobalFilter 实际使用 Ordered.HIGHEST_PRECEDENCE（必须先于
     * RemoveCachedBodyFilter 等 SCG 内置过滤器执行，保证 traceId 对所有过滤器可见）。
     * 此常量仅为历史位置参考，勿据此推断实际执行顺序。
     */
    int TRACE_ID = -300;

    // ============================================================
    // Request Transformation Filters
    // ============================================================

    /**
     * Request transform filter - Modify request before processing.
     * Execute after authentication so body signatures cover the original wire payload.
     */
    int REQUEST_TRANSFORM = -255;

    /**
     * Request validation filter - Validate request body/schema.
     * Execute after request transformation.
     */
    int REQUEST_VALIDATION = -254;

    /**
     * Authentication filter - JWT/API Key/Basic auth.
     * Execute before request transformation and validation; HMAC signs the original body.
     */
    int AUTHENTICATION = -256;

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
     * Execute before actual service call in pre-phase.
     * In post-phase, execute AFTER RetryGlobalFilter (-2) to correctly record
     * final request status (success/failure after retries).
     */
    int CIRCUIT_BREAKER = -3;

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
    // Rate Limiter Filters (Execute AFTER authentication)
    // ============================================================
    //
    // FIX (ordering): these used to run at Ordered.HIGHEST_PRECEDENCE + 20/21/22
    // (i.e. Integer.MIN_VALUE + n), which is BEFORE SecurityGlobalFilter (-500) and
    // long before AuthenticationGlobalFilter (-250). The multi-dim/hybrid limiters key
    // on identity dimensions (jwt_claims, jwt_subject, api_key_metadata) that are only
    // populated by the auth filters, so running first made tenant/user quotas silently
    // collapse to IP-only (or be skipped). They are now placed after MOCK_RESPONSE
    // (-249) and before TIMEOUT (-200), so identity attributes are available.

    /**
     * AI security guard filter (content moderation: sensitive words / regex / PII).
     * <p>
     * Order -246 is BOTH &lt; NettyWriteResponseFilter (-1) so its response decorator
     * is in the client-write path (outbound moderation), AND after authentication
     * (-250) but before the rate limiters / token quota (-245..-242) so a blocked
     * request never spends quota. Inbound scanning runs in the pre-phase.
     */
    int AI_SECURITY = -246;

    /**
     * Hybrid rate limiter filter - Redis + local rate limiting.
     * Executes after authentication so identity-based keys are populated.
     */
    int HYBRID_RATE_LIMITER = -245;

    /**
     * Multi-dimensional rate limiter filter.
     * Execute right after hybrid rate limiter.
     */
    int MULTI_DIM_RATE_LIMITER = -244;

    /**
     * AI context-window limit filter (Higress ai-context-limit counterpart).
     * <p>
     * After authentication (-250) and BEFORE TokenQuotaPreCheckFilter so an
     * oversized request never spends a pre-deduction. Joins the request body
     * itself (the cachedRequestBody attribute is not populated this early) and
     * replays it through a decorator, publishing the body for downstream filters.
     */
    int AI_CONTEXT_LIMIT = -243;

    /**
     * Token quota pre-check filter.
     * Execute after the AI context-window limit AND the semantic cache, so a cache HIT
     * short-circuits before any pre-deduction happens. Checks/pre-deducts token quota
     * before the request is proxied to the LLM.
     */
    int TOKEN_QUOTA_PRE_CHECK = -241;

    /**
     * AI semantic cache filter.
     * <p>
     * After the rate limiters and the context-window limit but BEFORE the token-quota
     * pre-check (-241), and &lt; NettyWriteResponseFilter (-1). On a cache HIT it
     * short-circuits by writing the stored response (like MockResponseFilter) so routing,
     * the token pre-check and the token collector never run - a hit consumes no LLM tokens
     * AND pre-deducts no quota. (Running it AFTER the pre-check stranded the pre-deducted
     * estimate on every hit: the collector that settles/refunds never ran, so the tenant was
     * billed for tokens it never spent.) On a MISS it decorates the response (order &lt; -1
     * keeps the decorator in the write path) to capture and store the upstream answer.
     */
    int AI_SEMANTIC_CACHE = -242;

    // ============================================================
    // Post-Processing Filters
    // ============================================================

    /**
     * Cache filter - Response caching.
     * <p>
     * FIX (P0 cache never populated): was 50, i.e. AFTER NettyWriteResponseFilter (-1). SCG only
     * routes the client-bound response body through decorators installed by filters ordered
     * BEFORE -1 (same reason ResponseTransformFilter is -45 and TokenUsageCollectorFilter is -44).
     * At order 50 this filter's ServerHttpResponseDecorator.writeWith was never invoked on the real
     * Netty proxy path, so cache.put never ran and every request went back to origin - the caching
     * feature silently did nothing (verified: two identical GETs -> upstreamCalls=2, entries=0).
     * <p>
     * -46 places it just INSIDE ResponseTransformFilter (-45): lower order == installed first ==
     * innermost decorator, so its writeWith observes the FINAL, post-transform body that the client
     * actually receives (correct semantics for a shared response cache), while still being < -1 so
     * it is in the write path at all.
     */
    int CACHE = -46;

    /**
     * Trace capture filter - Capture trace info after request.
     * <p>
     * FIX (P0, response body never captured): was 100, i.e. AFTER NettyWriteResponseFilter (-1) -
     * the exact bug {@link #CACHE} had at 50. This filter wraps the response in a
     * {@code ServerHttpResponseDecorator} to record the response body of error/slow requests, but a
     * filter ordered after -1 decorates an exchange that Netty's write phase never reads back: its
     * pre-phase runs INSIDE NettyWriteResponseFilter's {@code chain.filter(...)}, so Netty has
     * already captured the undecorated response and the decorator's writeWith is never invoked.
     * Every captured trace therefore had {@code responseBody == null}, and the post-phase also ran
     * BEFORE the body was written, so status/duration were sampled early.
     * <p>
     * -47 is the innermost decorator (lowest order == wraps first == innermost), so it sees the
     * final post-transform, post-cache body - exactly what the client received - and its
     * {@code doFinally} now runs last, after the response is on the wire. On a cache HIT
     * (CacheGlobalFilter short-circuits at -46 without calling the chain) this filter has already
     * wrapped the response, so the cached body is captured too.
     */
    int TRACE_CAPTURE = -47;

    /**
     * Token usage collector filter.
     * <p>
     * MUST be &lt; NettyWriteResponseFilter (-1). SCG only routes the client-bound
     * response body through decorators installed by filters ordered BEFORE -1
     * (same reason ResponseTransformFilter sits at -45). At the previous order (101)
     * this filter's writeWith/writeAndFlushWith were never invoked on the real proxy
     * path, so token usage always parsed as 0. Placed just inside ResponseTransform
     * (-45) so it observes the raw upstream body (usage field intact) before any
     * transform, and after TokenQuotaPreCheckFilter (-241) so the tenant attr is set.
     */
    int TOKEN_USAGE_COLLECTOR = -44;

    /**
     * AI statistics filter (Higress ai-statistics counterpart).
     * <p>
     * MUST be &lt; NettyWriteResponseFilter (-1) so its pass-through decorator sees
     * the first response chunk (TTFT) on the write path. Ordered after the token
     * usage collector (-44) so the collector's decorator stays closest to the raw
     * upstream body; this filter only timestamps, never buffers. Reads the settled
     * usage published by the collector via exchange attributes in its post-phase.
     */
    int AI_STATISTICS = -43;

    // ============================================================
    // Load Balancing Filters (High Values)
    // ============================================================

    /**
     * Retry filter - Retry failed requests.
     * Must execute AFTER NettyWriteResponseFilter (-1) in post-phase to intercept response.
     * Using order = -2 ensures post-phase runs after NettyWriteResponseFilter's writeWith.
     */
    int RETRY = -2;

    /**
     * Multi-service load balancer filter.
     * Custom load balancing for multi-service routing.
     * Execute after RouteToRequestUrlFilter (10000).
     * <p>
     * Use case: Route to multiple backend services with weight-based distribution,
     * supporting both service discovery (lb://) and static addresses (static://).
     */
    int MULTI_SERVICE_LOAD_BALANCER = 10001;

    /** Apply API version service targets after SCG URL construction and multi-service selection. */
    int API_VERSION_ROUTING = 10002;

    /**
     * Nacos discovery load balancer filter.
     * Handles lb:// services with namespace/group override.
     * Execute BEFORE SCG's native ReactiveLoadBalancerClientFilter (10150).
     * <p>
     * Use case: Query services in different Nacos namespaces/groups.
     * Triggered when serviceNamespace or serviceGroup is specified in route config.
     */
    int NACOS_DISCOVERY_LOAD_BALANCER = 10100;

    /**
     * Discovery load balancer filter.
     * SCG built-in ReactiveLoadBalancerClientFilter is at 10150.
     * This filter handles service discovery based load balancing.
     * <p>
     * Use case: Standard Spring Cloud load balancing for services
     * registered in Nacos/Consul/Eureka (lb:// scheme).
     * <p>
     * FIX (order collision): this was 10150 - exactly SCG's
     * {@code ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER}, verified against
     * the 4.3.5 jar. Two GlobalFilters with the same order have no defined relative sequence, so it
     * came down to bean registration order. When SCG's filter won, it had already rewritten
     * {@code lb://service} to {@code http://host:port} before this one ran, so the
     * {@code "lb".equals(url.getScheme())} half of {@code DiscoveryLoadBalancerFilter}'s guard was
     * false and the marked-static-instance selection silently never happened. Strictly less than
     * 10150 makes this deterministic, and still after NACOS_DISCOVERY_LOAD_BALANCER (10100).
     */
    int DISCOVERY_LOAD_BALANCER = 10140;

    /**
     * AI prompt template/decorator filter.
     * Runs just before AiProviderRoutingFilter (10005) so the decorated messages
     * (system-prompt injection, {{var}} substitution, optional model override) are
     * what the provider router reads and forwards.
     */
    int AI_PROMPT_TEMPLATE = 10004;

    /**
     * AI provider routing filter (multi-provider LLM proxy).
     * <p>
     * MUST run AFTER SCG's RouteToRequestUrlFilter (10000), which sets
     * GATEWAY_REQUEST_URL_ATTR from the route uri - this filter overwrites that
     * attribute with the resolved provider's absolute https URL (chosen by the
     * request's model), injects the upstream credential and rewrites the Host.
     * Placed after MultiServiceLoadBalancerFilter (10001) and before the lb://
     * discovery filters (10100/10150), which skip non-lb URLs.
     */
    int AI_PROVIDER_ROUTING = 10005;

    // ============================================================
    // Filter Order Summary (Ascending = Execution Order)
    // ============================================================
    //
    // Order    Filter                          Purpose
    // ------   ------------------------------  ----------------------------------
    // -480     SecurityGlobalFilter            XSS/SQL injection protection
    // -490     IPFilterGlobalFilter            IP whitelist/blacklist
    // -400     AccessLogGlobalFilter           Request/response logging
    // -301     CorsGlobalFilter                CORS handling (preflight)
    // -300     TraceIdGlobalFilter             Trace ID generation
    // -256     AuthenticationGlobalFilter      Authentication of original request bytes
    // -255     RequestTransformFilter          Request transformation
    // -254     RequestValidationFilter         Request validation
    // -249     MockResponseFilter              Mock response
    // -246     AiSecurityGuardFilter           AI content moderation (inbound + outbound)
    // -245     HybridRateLimiterFilter         Rate limiting (after auth -> identity keys ready)
    // -244     MultiDimRateLimiterFilter       Multi-dimensional rate limiting
    // -243     AiContextLimitFilter            AI context-window limit (rejects oversized inputs)
    // -242     AiSemanticCacheFilter           AI semantic cache (hit short-circuits before quota)
    // -241     TokenQuotaPreCheckFilter        AI token quota pre-check
    // -200     TimeoutGlobalFilter             Timeout handling
    // -150     ApiVersionGlobalFilter          API version routing
    //  -50     HeaderOpGlobalFilter            Header operations
    //  -47     TraceCaptureGlobalFilter        Trace capture (must be < -1 to capture the response body)
    //  -46     CacheGlobalFilter               Response caching (must be < -1 to be in write path)
    //  -45     ResponseTransformFilter         Response transformation
    //  -44     TokenUsageCollectorFilter       AI token usage capture (must be < -1 to be in write path)
    //  -43     AiStatisticsFilter              AI TTFT/usage statistics (must be < -1 to be in write path)
    //   -3     CircuitBreakerGlobalFilter      Circuit breaker (records final status after retry)
    //   -2     RetryGlobalFilter               Retry logic (intercepts response after NettyWrite)
    //   -1     NettyWriteResponseFilter        Write response (SCG built-in)
    // 10001    MultiServiceLoadBalancerFilter  Multi-service load balancing (static://)
    // 10004    AiPromptTemplateFilter          AI prompt decoration (before provider routing)
    // 10005    AiProviderRoutingFilter         AI provider routing (rewrites the target URL)
    // 10100    NacosDiscoveryLoadBalancerFilter Nacos namespace/group aware lb:// resolution
    // 10140    DiscoveryLoadBalancerFilter     Discovery load balancing (lb://, must be < 10150)
    // 10150    ReactiveLoadBalancerClientFilter SCG built-in lb:// resolution
    //
    // Note: Filters with order < -1 have post-phase AFTER NettyWriteResponseFilter,
    // allowing them to intercept response status/body before it's written to client.
    // ResponseDecoratorOrderTest enforces this for every filter that installs a
    // ServerHttpResponseDecorator - see its javadoc for why the failure is silent.
    //
}
