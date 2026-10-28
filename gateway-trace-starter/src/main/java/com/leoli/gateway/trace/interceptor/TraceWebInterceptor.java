package com.leoli.gateway.trace.interceptor;

import com.leoli.gateway.trace.TraceContextHolder;
import com.leoli.gateway.trace.model.DistributedTrace;
import com.leoli.gateway.trace.model.SpanType;
import com.leoli.gateway.trace.properties.GatewayTraceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trace Web interceptor
 * <p>
 * Responsible for extracting TraceId passed by gateway from request Header,
 * and initializing Trace context.
 * <p>
 * Enhanced with:
 * - Span hierarchy support (parentSpanId from upstream)
 * - Caller service tracking (for topology graph generation)
 * - Baggage propagation (cross-service business context)
 * <p>
 * TraceId propagation chain:
 * Gateway generates → X-Trace-Id Header → downstream services → Starter extracts
 * <p>
 * SECURITY FIX (H3): X-Forwarded-For IP spoofing prevention.
 * Only trust X-Forwarded-For header from configured trusted proxies.
 * This prevents malicious clients from forging IP addresses to bypass
 * IP-based access controls or pollute audit trails.
 *
 * @author leoli
 */
@Slf4j
public class TraceWebInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    
    /**
     * Sampling flag Header name (from upstream; sampling decision is made once
     * at the entry point and inherited by all downstream services so a trace
     * is never partially reported)
     */
    private static final String SAMPLED_HEADER = "X-Trace-Sampled";

    /**
     * Span ID Header name (from upstream, becomes this service's parentSpanId)
     */
    private static final String SPAN_ID_HEADER = "X-Span-Id";
    
    /**
     * Caller service Header name (for topology graph generation)
     */
    private static final String CALLER_SERVICE_HEADER = "X-Caller-Service";
    
    /**
     * Baggage Header name (JSON encoded cross-service context)
     */
    private static final String BAGGAGE_HEADER = "X-Trace-Baggage";

    /**
     * W3C Trace Context header: 00-{trace-id 32hex}-{parent-id 16hex}-{flags 2hex}.
     * Interop with OpenTelemetry/Jaeger/SkyWalking instrumented services.
     * Priority: traceparent > X-Trace-Id / X-Span-Id / X-Trace-Sampled.
     */
    private static final String TRACEPARENT_HEADER = "traceparent";

    /**
     * Request attribute carrying the entry span id, so TraceReportInterceptor can
     * close it when the request completes (an un-exited entry span would report
     * duration=0/success=false forever).
     */
    public static final String ENTRY_SPAN_ATTR = "gateway.trace.entrySpanId";

    private final String serviceName;
    private final GatewayTraceProperties properties;

    public TraceWebInterceptor(GatewayTraceProperties properties, String serviceName) {
        this.properties = properties;
        this.serviceName = serviceName;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Check if enabled
        if (!properties.isEnabled()) {
            return true;
        }

        // W3C traceparent takes priority (interop with OTel-instrumented callers)
        String[] traceparent = parseTraceparent(request.getHeader(TRACEPARENT_HEADER));

        // Get TraceId: traceparent > X-Trace-Id > generate
        String traceId = traceparent != null ? traceparent[1] : request.getHeader(TRACE_ID_HEADER);

        if (traceId == null || !isValidTraceId(traceId)) {
            // Gateway didn't pass TraceId (or it's malformed/untrusted), generate one
            traceId = generateTraceId();
            log.debug("Generated new traceId: {}", traceId);
        } else {
            log.debug("Received traceId from upstream: {}", traceId);
        }

        // Set TraceId to ThreadLocal
        TraceContextHolder.setTraceId(traceId);

        // Set sampling flag: traceparent sampled bit (01) forces sampling; otherwise
        // inherit upstream X-Trace-Sampled; otherwise sample locally. Independent
        // per-service sampling would fragment traces.
        boolean sampled;
        if (traceparent != null) {
            sampled = (Integer.parseInt(traceparent[3], 16) & 0x01) == 1;
        } else {
            String sampledHeader = request.getHeader(SAMPLED_HEADER);
            if ("true".equalsIgnoreCase(sampledHeader)) {
                sampled = true;
            } else if ("false".equalsIgnoreCase(sampledHeader)) {
                sampled = false;
            } else {
                sampled = properties.shouldSample();
            }
        }
        TraceContextHolder.setSampled(sampled);

        // Initialize Trace object
        String path = request.getRequestURI();
        String method = request.getMethod();
        TraceContextHolder.initTrace(serviceName, path, method);

        // === Enhanced: Span hierarchy support ===
        
        // Get parentSpanId from upstream: traceparent parent-id > X-Span-Id
        String parentSpanId = traceparent != null ? traceparent[2] : request.getHeader(SPAN_ID_HEADER);
        
        // Get caller service name (for topology graph generation)
        String callerService = request.getHeader(CALLER_SERVICE_HEADER);
        
        // Store caller info in trace for topology analysis
        DistributedTrace trace = TraceContextHolder.getTrace();
        if (trace != null) {
            if (callerService != null && !callerService.isEmpty()) {
                trace.setUpstreamService(callerService);
                log.debug("Received caller service: {}", callerService);
            }
        }
        
        // Create entry span for this service
        if (TraceContextHolder.isSampled()) {
            String entrySpanId = TraceContextHolder.enterSpan(serviceName + ".entry", SpanType.SERVICE);
            if (entrySpanId != null) {
                request.setAttribute(ENTRY_SPAN_ATTR, entrySpanId);
            }
            // Override parentSpanId from upstream
            if (parentSpanId != null && !parentSpanId.isEmpty()) {
                com.leoli.gateway.trace.model.ServiceSpan entrySpan = TraceContextHolder.getSpan(entrySpanId);
                if (entrySpan != null) {
                    entrySpan.setParentSpanId(parentSpanId);
                }
                log.debug("Set parentSpanId={} for entry span", parentSpanId);
            }
        }

        // === Enhanced: Baggage propagation ===
        
        // Extract and restore baggage from upstream
        String baggageHeader = request.getHeader(BAGGAGE_HEADER);
        if (baggageHeader != null && !baggageHeader.isEmpty()) {
            Map<String, String> baggage = decodeBaggage(baggageHeader);
            if (baggage != null && !baggage.isEmpty()) {
                TraceContextHolder.setAllBaggage(baggage);
                log.debug("Restored baggage with {} keys from upstream", baggage.size());
            }
        }

        // Set client IP
        String clientIp = getClientIp(request);
        if (TraceContextHolder.getTrace() != null) {
            TraceContextHolder.getTrace().setClientIp(clientIp);
        }

        // Add TraceId to response Header (for debugging)
        response.setHeader(TRACE_ID_HEADER, traceId);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // TraceReportInterceptor (order=100) 负责上报后清理。
        // 这里做防御性兜底：如果 TraceReportInterceptor 未执行（异常/未注册），
        // 确保 ThreadLocal 不泄漏。
        if (TraceContextHolder.getTraceId() != null) {
            TraceContextHolder.clear();
        }
    }

    /**
     * Generate TraceId
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Parse a W3C traceparent header: {version}-{trace-id}-{parent-id}-{flags}.
     *
     * @return String[4] of lowercase parts when valid, null otherwise
     */
    private String[] parseTraceparent(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        String[] parts = header.trim().toLowerCase().split("-");
        if (parts.length != 4) {
            return null;
        }
        // version: 2 hex, "ff" is invalid per spec
        if (!isHex(parts[0], 2) || "ff".equals(parts[0])) {
            return null;
        }
        // trace-id: 32 hex, all-zero invalid; parent-id: 16 hex, all-zero invalid
        if (!isHex(parts[1], 32) || parts[1].chars().allMatch(c -> c == '0')) {
            return null;
        }
        if (!isHex(parts[2], 16) || parts[2].chars().allMatch(c -> c == '0')) {
            return null;
        }
        if (!isHex(parts[3], 2)) {
            return null;
        }
        return parts;
    }

    private boolean isHex(String value, int expectedLength) {
        if (value == null || value.length() != expectedLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validate an inbound TraceId before trusting it.
     * Rejects oversized/malformed values (log injection, storage pollution,
     * correlation breakage) — falls back to a locally generated id.
     */
    private boolean isValidTraceId(String traceId) {
        if (traceId.length() < 8 || traceId.length() > 64) {
            return false;
        }
        for (int i = 0; i < traceId.length(); i++) {
            char c = traceId.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decode baggage from URL-safe JSON string
     */
    private Map<String, String> decodeBaggage(String encodedBaggage) {
        if (encodedBaggage == null || encodedBaggage.isEmpty()) {
            return null;
        }
        
        try {
            // Remove JSON braces
            String content = encodedBaggage.trim();
            if (content.startsWith("{") && content.endsWith("}")) {
                content = content.substring(1, content.length() - 1);
            }
            
            if (content.isEmpty()) {
                return null;
            }
            
            Map<String, String> baggage = new HashMap<>();
            
            // Parse key-value pairs
            String[] pairs = content.split(",");
            for (String pair : pairs) {
                // Format: "key":"value"
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    // Remove quotes and URL decode
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");
                    
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8);
                    
                    baggage.put(key, value);
                }
            }
            
            return baggage;
        } catch (Exception e) {
            log.warn("Failed to decode baggage: {}", encodedBaggage, e);
            return null;
        }
    }

    /**
     * 获取客户端IP
     * <p>
     * SECURITY FIX (H3): X-Forwarded-For IP spoofing prevention.
     * <p>
     * Before: Unconditionally trusted X-Forwarded-For header, allowing clients to forge any IP.
     * After: Only trust X-Forwarded-For header from configured trusted proxy IPs.
     * <p>
     * Trusted proxy configuration:
     * - gateway.trace.trusted-proxy-ips: List of proxy IPs (supports CIDR)
     * - gateway.trace.trust-private-networks: Trust private network IPs (default: true)
     * <p>
     * Algorithm:
     * 1. Get direct remote address (TCP connection source)
     * 2. Check if remote address is a trusted proxy
     * 3. If trusted, extract client IP from X-Forwarded-For (first IP in chain)
     * 4. Validate extracted IP format to prevent injection
     * 5. If not trusted or invalid, use remote address
     */
    private String getClientIp(HttpServletRequest request) {
        // Step 1: Get direct remote address (the actual TCP connection source)
        String remoteAddr = request.getRemoteAddr();

        // Step 2: Check if request comes from a trusted proxy
        if (!isTrustedProxy(remoteAddr)) {
            // Request is NOT from trusted proxy - ignore X-Forwarded-For to prevent spoofing
            log.debug("Request from non-trusted source {}, using remote address (ignoring X-Forwarded-For)", remoteAddr);
            return remoteAddr;
        }

        // Step 3: Request is from trusted proxy, check X-Forwarded-For
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(forwardedFor)) {
            // Extract first IP from chain (original client IP)
            String clientIp = forwardedFor.split(",")[0].trim();

            // Step 4: Validate extracted IP format to prevent injection attacks
            if (isValidIpFormat(clientIp)) {
                log.debug("Request from trusted proxy {}, extracted client IP: {}", remoteAddr, clientIp);
                return clientIp;
            } else {
                log.warn("Invalid IP format in X-Forwarded-For from trusted proxy {}: {}", remoteAddr, clientIp);
            }
        }

        // Fallback: Check X-Real-IP header (used by some proxies)
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty() && !"unknown".equalsIgnoreCase(realIp)) {
            if (isValidIpFormat(realIp)) {
                log.debug("Using X-Real-IP from trusted proxy {}: {}", remoteAddr, realIp);
                return realIp;
            }
        }

        // No valid forwarded header from trusted proxy - use remote address
        log.debug("Request from trusted proxy {} but no valid X-Forwarded-For/X-Real-IP, using remote address", remoteAddr);
        return remoteAddr;
    }

    /**
     * Check if the remote address is a trusted proxy.
     * <p>
     * Trusted sources:
     * 1. IPs in configured trustedProxyIps list (supports CIDR notation)
     * 2. Private network IPs (if trustPrivateNetworks is enabled)
     */
    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return false;
        }

        // Check configured proxy IPs
        List<String> trustedProxies = properties.getTrustedProxyIps();
        if (trustedProxies != null && !trustedProxies.isEmpty()) {
            for (String proxy : trustedProxies) {
                if (isIpInRange(remoteAddr, proxy)) {
                    return true;
                }
            }
        }

        // Check if private networks are trusted
        if (properties.isTrustPrivateNetworks()) {
            return isPrivateNetworkIp(remoteAddr);
        }

        return false;
    }

    /**
     * Check if IP is in a CIDR range or exact match.
     * Simplified version for trace interceptor (basic CIDR support).
     */
    private boolean isIpInRange(String ip, String range) {
        if (range == null || range.isEmpty()) {
            return false;
        }

        // Exact match
        if (!range.contains("/")) {
            return ip.equals(range);
        }

        // CIDR notation - simplified check for IPv4
        try {
            String[] parts = range.split("/");
            String network = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            // Handle IPv4 CIDR
            if (!ip.contains(":") && !network.contains(":")) {
                long ipLong = ipv4ToLong(ip);
                long networkLong = ipv4ToLong(network);
                long mask = prefixLength == 0 ? 0 : (-1L << (32 - prefixLength));

                return (ipLong & mask) == (networkLong & mask);
            }

            // IPv6 CIDR - simplified (full byte comparison)
            // For production, use a proper IP library like IPAddress or Guava
            return false;
        } catch (Exception e) {
            log.warn("Invalid CIDR range format: {}", range);
            return false;
        }
    }

    /**
     * Convert IPv4 address to long for CIDR comparison.
     */
    private long ipv4ToLong(String ip) {
        try {
            // Handle IPv6-mapped IPv4
            if (ip.startsWith("::ffff:")) {
                ip = ip.substring(7);
            }

            String[] octets = ip.split("\\.");
            if (octets.length != 4) {
                return 0;
            }

            long result = 0;
            for (int i = 0; i < 4; i++) {
                result = (result << 8) + Integer.parseInt(octets[i]);
            }
            return result;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Check if IP belongs to private networks (RFC 1918).
     * IPv4: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8
     * IPv6: fc00::/7, fe80::/10, ::1/128
     */
    private boolean isPrivateNetworkIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // IPv6 private networks
        if (ip.contains(":")) {
            // IPv6 loopback
            if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
                return true;
            }
            // IPv6 Unique Local Addresses (fc00::/7) - simplified check
            if (ip.toLowerCase().startsWith("fc") || ip.toLowerCase().startsWith("fd")) {
                return true;
            }
            // IPv6 Link-Local (fe80::/10)
            if (ip.toLowerCase().startsWith("fe8") || ip.toLowerCase().startsWith("fe9")
                    || ip.toLowerCase().startsWith("fea") || ip.toLowerCase().startsWith("feb")) {
                return true;
            }
            return false;
        }

        // IPv4 private networks - check using CIDR
        return isIpInRange(ip, "10.0.0.0/8")
                || isIpInRange(ip, "172.16.0.0/12")
                || isIpInRange(ip, "192.168.0.0/16")
                || isIpInRange(ip, "127.0.0.0/8");
    }

    /**
     * Validate IP format to prevent injection attacks.
     * Accepts IPv4 (xxx.xxx.xxx.xxx) and IPv6 formats.
     * Rejects invalid formats that could be used for injection.
     */
    private boolean isValidIpFormat(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // Basic validation: no special characters that could be used for injection
        // Allow only digits, dots, colons, and hex letters (a-f)
        String cleaned = ip.toLowerCase().replaceAll("[0-9a-f.:]", "");
        if (!cleaned.isEmpty()) {
            log.warn("Invalid characters in IP: {}", ip);
            return false;
        }

        // IPv4 validation
        if (!ip.contains(":")) {
            String[] octets = ip.split("\\.");
            if (octets.length != 4) {
                return false;
            }
            for (String octet : octets) {
                try {
                    int value = Integer.parseInt(octet);
                    if (value < 0 || value > 255) {
                        return false;
                    }
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }

        // IPv6 validation - basic check (at least has colons)
        return ip.contains(":");
    }
}
