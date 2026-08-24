package com.leoli.gateway.filter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.RouteUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Security global filter for SQL injection and XSS protection.
 * Detects and optionally blocks malicious requests.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityGlobalFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;

    /**
     * Maximum body size for security scanning (default: 10MB).
     * Bodies larger than this are rejected to prevent OOM attacks.
     * Configure via gateway.security.max-body-size-mb in application.yml
     */
    private static final int MAX_BODY_SIZE_BYTES = 10 * 1024 * 1024;  // 10MB

    /** Ant-style matcher for excludePaths (* = single segment, ** = multiple). */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    // High-confidence SQL injection patterns - a single hit blocks in BLOCK mode
    private static final List<Pattern> SQL_INJECTION_PATTERNS = Arrays.asList(
            // SQL keywords with context
            Pattern.compile("(?i)(\\b(select|insert|update|delete|drop|create|alter|truncate|exec|execute)\\b.*\\b(from|into|table|database|set|where)\\b)"),
            // Union-based
            Pattern.compile("(?i)(union\\s+(all\\s+)?select)"),
            // Boolean-based: only quote/number tautology forms. FIX (false positives):
            // the old \\b(and|or)\\b\\s+\\w+=\\w+ form fired on natural language such as
            // "color or size=large".
            Pattern.compile("(?i)\\b(and|or)\\b\\s+(\\d+\\s*=\\s*\\d+|'[^']*'\\s*=|\"[^\"]*\"\\s*=)"),
            // Tautology: '1'='1', 1=1, 'a'='a'
            Pattern.compile("(?i)(['\"]\\s*=\\s*['\"])"),
            // Single quote followed by SQL keyword
            Pattern.compile("(?i)('\\s*(or|and)\\s+)"),
            // Time-based
            Pattern.compile("(?i)(sleep\\s*\\(|benchmark\\s*\\(|waitfor\\s+delay|pg_sleep\\s*\\()"),
            // Error-based
            Pattern.compile("(?i)(convert\\s*\\(|cast\\s*\\(|extractvalue\\s*\\(|updatexml\\s*\\()"),
            // Stacked queries
            Pattern.compile(";\\s*(?i)(select|insert|update|delete|drop)"),
            // SQL functions commonly used in injection
            Pattern.compile("(?i)(concat\\s*\\(|char\\s*\\(|substr\\s*\\(|substring\\s*\\(|ascii\\s*\\(|length\\s*\\()"),
            // Information schema access
            Pattern.compile("(?i)(information_schema\\.)"),
            // Quote escape patterns
            Pattern.compile("('\\s*(;|%3B|--|%2D%2D))")
    );

    // Low-confidence SQL injection patterns - only block when combined with a
    // high-confidence hit. FIX (false positives): the old bare (--|/\*|\*/|#)
    // pattern fired on any text containing "#" or "--" (URL fragments, markdown,
    // dates like 2024--2025, passwords). Comment markers now require trailing
    // whitespace or end-of-value, so embedded "--"/"#section" no longer matches.
    private static final List<Pattern> SQL_LOW_CONFIDENCE_PATTERNS = Arrays.asList(
            Pattern.compile("(?:--|#)(?:\\s|$)"),
            Pattern.compile("/\\*|\\*/")
    );

    // XSS patterns
    private static final List<Pattern> XSS_PATTERNS = Arrays.asList(
            // Script tags
            Pattern.compile("<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            // Event handlers
            Pattern.compile("on(load|error|click|mouse|focus|blur|key|submit|change)\\s*=", Pattern.CASE_INSENSITIVE),
            // JavaScript protocol
            Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE),
            // Data URI with script
            Pattern.compile("data\\s*:\\s*text/html", Pattern.CASE_INSENSITIVE),
            // VBScript
            Pattern.compile("vbscript\\s*:", Pattern.CASE_INSENSITIVE),
            // Expression
            Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE),
            // Document object
            Pattern.compile("(document\\.(cookie|location|write)|window\\.(location|open))", Pattern.CASE_INSENSITIVE),
            // Alert/confirm/prompt
            Pattern.compile("(alert|confirm|prompt)\\s*\\(", Pattern.CASE_INSENSITIVE),
            // HTML injection
            Pattern.compile("<\\s*(iframe|object|embed|form|input|button|meta|link|style|base)[^>]*>", Pattern.CASE_INSENSITIVE),
            // SVG with script
            Pattern.compile("<\\s*svg[^>]*onload[^>]*>", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get security config
        Map<String, Object> config = strategyManager.getSecurityConfig(routeId);

        // Log config status (DEBUG level to avoid high-frequency logging)
        if (log.isDebugEnabled()) {
            log.debug("SecurityGlobalFilter - routeId: {}, hasConfig: {}", routeId, config != null);
        }

        if (config == null) {
            log.debug("No security config for route: {}, skipping", routeId);
            return chain.filter(exchange);
        }

        if (!getBoolValue(config, "enabled", true)) {
            log.debug("Security disabled for route: {}", routeId);
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Check exclude paths - FIX: AntPathMatcher instead of per-request regex
        // compilation. The old excludePath.replace("*", ".*") recompiled a regex on
        // every request, did not escape regex metacharacters (. + etc.) and let *
        // cross segment boundaries.
        List<String> excludePaths = getStringListValue(config, "excludePaths");
        if (excludePaths != null && !excludePaths.isEmpty()) {
            for (String excludePath : excludePaths) {
                if (PATH_MATCHER.match(excludePath, path)) {
                    log.debug("Path {} excluded from security check", path);
                    return chain.filter(exchange);
                }
            }
        }

        boolean checkParameters = getBoolValue(config, "checkParameters", true);
        boolean checkHeaders = getBoolValue(config, "checkHeaders", false);
        boolean checkBody = getBoolValue(config, "checkBody", true);
        boolean enableSqlInjection = getBoolValue(config, "enableSqlInjectionProtection", true);
        boolean enableXss = getBoolValue(config, "enableXssProtection", true);
        String mode = getStringValue(config, "mode", "BLOCK");
        // FIX (false positives): parameter names listed here are skipped entirely
        // (e.g. fields that legitimately carry markdown/code/base64 content).
        List<String> whitelistParams = getStringListValue(config, "whitelistParams");

        // Log security settings (DEBUG level to avoid high-frequency logging)
        if (log.isDebugEnabled()) {
            log.debug("Security check enabled - SQL: {}, XSS: {}, mode: {}, checkBody: {}",
                    enableSqlInjection, enableXss, mode, checkBody);
        }

        List<String> threats = new ArrayList<>();

        // Check query parameters
        if (checkParameters) {
            MultiValueMap<String, String> queryParams = request.getQueryParams();
            for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                String paramName = entry.getKey();
                if (whitelistParams != null && whitelistParams.contains(paramName)) {
                    continue;
                }
                for (String paramValue : entry.getValue()) {
                    checkValue(paramName + "=" + paramValue, enableSqlInjection, enableXss, threats);
                }
            }
        }

        // Check headers
        if (checkHeaders) {
            HttpHeaders headers = request.getHeaders();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String headerName = entry.getKey();
                for (String headerValue : entry.getValue()) {
                    checkValue(headerName + ": " + headerValue, enableSqlInjection, enableXss, threats);
                }
            }
        }

        // Check request body for JSON content
        if (checkBody && hasBody(request)) {
            return checkRequestBody(exchange, chain, config, threats, mode, enableSqlInjection, enableXss);
        }

        // Handle detected threats
        if (!threats.isEmpty()) {
            return handleThreat(exchange, threats, mode, chain);
        }

        return chain.filter(exchange);
    }

    /**
     * Check if the request may carry a body that must be scanned.
     * <p>
     * FIX (Content-Type bypass): previously only JSON and form-urlencoded bodies
     * were scanned, so an attacker could smuggle payloads with
     * Content-Type: text/xml, text/plain or multipart/form-data (the console even
     * supports XML protocol conversion). Now ANY request that shows evidence of a
     * body (Content-Type present, Content-Length > 0, or chunked transfer) is
     * scanned regardless of media type. The 10MB cap in checkRequestBody still
     * applies, and WebSocket upgrade streams are never consumed.
     */
    private boolean hasBody(ServerHttpRequest request) {
        // Only skip HEAD method (GET and DELETE can have body for security check)
        String method = request.getMethod().name();
        if ("HEAD".equals(method)) {
            return false;
        }
        HttpHeaders headers = request.getHeaders();
        // Never consume WebSocket upgrade streams
        if (headers.containsKey(HttpHeaders.UPGRADE)) {
            return false;
        }
        // Any declared content type means a body must be scanned (XML, multipart,
        // text/plain, custom vendor types included)
        if (headers.getContentType() != null) {
            return true;
        }
        // Body without a declared type: explicit length or chunked transfer
        if (headers.getContentLength() > 0) {
            return true;
        }
        String transferEncoding = headers.getFirst(HttpHeaders.TRANSFER_ENCODING);
        return transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
    }

    /**
     * Check request body for threats.
     * 
     * SECURITY: Enforces maximum body size to prevent OOM attacks.
     * Bodies larger than MAX_BODY_SIZE_BYTES are rejected.
     */
    private Mono<Void> checkRequestBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                        Map<String, Object> config, List<String> existingThreats,
                                        String mode, boolean enableSqlInjection, boolean enableXss) {
        ServerHttpRequest request = exchange.getRequest();

        // Check Content-Length header first (quick rejection without reading body)
        String contentLength = request.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH);
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length > MAX_BODY_SIZE_BYTES) {
                    log.error("Request body too large for route {}: Content-Length={} bytes (max {} bytes)",
                            RouteUtils.getRouteId(exchange), length, MAX_BODY_SIZE_BYTES);
                    return writeTooLargeResponse(exchange.getResponse(), 
                            "Request body too large. Maximum allowed: " + (MAX_BODY_SIZE_BYTES / 1024 / 1024) + "MB");
                }
            } catch (NumberFormatException e) {
                log.debug("Invalid Content-Length header: {}", contentLength);
            }
        }

        // SECURITY: 在流级别限制累计字节数，避免客户端伪造 Content-Length 后
        // DataBufferUtils.join 把整个 body 载入内存造成 OOM。
        java.util.concurrent.atomic.AtomicLong totalBytes = new java.util.concurrent.atomic.AtomicLong(0);
        return DataBufferUtils.join(
                request.getBody().handle((dataBuffer, sink) -> {
                    long newTotal = totalBytes.addAndGet(dataBuffer.readableByteCount());
                    if (newTotal > MAX_BODY_SIZE_BYTES) {
                        DataBufferUtils.release(dataBuffer);
                        sink.error(new org.springframework.core.io.buffer.DataBufferLimitException(
                                "Body exceeded max size " + MAX_BODY_SIZE_BYTES + " bytes"));
                        return;
                    }
                    sink.next(dataBuffer);
                })
        )
                .onErrorResume(org.springframework.core.io.buffer.DataBufferLimitException.class, e -> {
                    log.error("Request body exceeded max size for route {}: {}",
                            RouteUtils.getRouteId(exchange), e.getMessage());
                    return writeTooLargeResponse(exchange.getResponse(),
                            "Request body too large. Maximum allowed: " + (MAX_BODY_SIZE_BYTES / 1024 / 1024) + "MB")
                            .then(Mono.empty());
                })
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    int readableBytes = dataBuffer.readableByteCount();
                    byte[] bytes = new byte[readableBytes];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    // SECURITY: the full body is always scanned. A previous
                    // optimization truncated the scan to the leading 512KB for large
                    // bodies, which let attackers bypass WAF detection deterministically
                    // by padding a body past the scan limit (payload placed after the
                    // boundary was never inspected). The 10MB cap in MAX_BODY_SIZE_BYTES
                    // already bounds the worst-case decode/regex cost, so correctness
                    // wins over the marginal hot-path saving.
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    List<String> threats = new ArrayList<>(existingThreats);
                    checkValue(body, enableSqlInjection, enableXss, threats);

                    // Rebuild request with cached body BEFORE threat handling, so that
                    // DETECT mode (which forwards the request) does not lose the consumed body.
                    ServerHttpRequest newRequest = new org.springframework.http.server.reactive.ServerHttpRequestDecorator(request) {
                        @Override
                        public reactor.core.publisher.Flux<DataBuffer> getBody() {
                            return reactor.core.publisher.Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                        }
                    };
                    ServerWebExchange rebuiltExchange = exchange.mutate().request(newRequest).build();

                    if (!threats.isEmpty()) {
                        return handleThreat(rebuiltExchange, threats, mode, chain);
                    }

                    return chain.filter(rebuiltExchange);
                });
    }

    /**
     * Write error response for oversized body.
     */
    private Mono<Void> writeTooLargeResponse(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);  // 413
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        
        String body = String.format("{\"error\":\"PAYLOAD_TOO_LARGE\",\"message\":\"%s\"}", message);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Check a value for threats.
     * <p>
     * Low-confidence SQL patterns (bare comment markers) only count as a threat
     * when a high-confidence pattern also matched, to keep BLOCK mode from
     * rejecting legitimate content.
     */
    private void checkValue(String value, boolean checkSql, boolean checkXss, List<String> threats) {
        if (value == null || value.isEmpty()) {
            return;
        }

        int hitsBefore = threats.size();
        if (checkSql) {
            for (Pattern pattern : SQL_INJECTION_PATTERNS) {
                if (pattern.matcher(value).find()) {
                    threats.add("SQL_INJECTION: " + pattern.pattern());
                    log.warn("SQL injection detected: pattern={}", pattern.pattern());
                }
            }
        }

        if (checkXss) {
            for (Pattern pattern : XSS_PATTERNS) {
                if (pattern.matcher(value).find()) {
                    threats.add("XSS: " + pattern.pattern());
                    log.warn("XSS detected: pattern={}", pattern.pattern());
                }
            }
        }

        if (checkSql && threats.size() > hitsBefore) {
            for (Pattern pattern : SQL_LOW_CONFIDENCE_PATTERNS) {
                if (pattern.matcher(value).find()) {
                    threats.add("SQL_INJECTION: " + pattern.pattern());
                    log.warn("SQL injection detected (low-confidence, correlated): pattern={}", pattern.pattern());
                }
            }
        }
    }

    /**
     * Handle detected threat.
     */
    private Mono<Void> handleThreat(ServerWebExchange exchange, List<String> threats, String mode, GatewayFilterChain chain) {
        if ("DETECT".equals(mode)) {
            // Log only, don't block
            log.warn("Security threat detected (DETECT mode): {}", threats);
            return chain.filter(exchange);
        }

        // BLOCK mode
        log.error("Security threat blocked: {}", threats);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body;
        try {
            // FIX (response injection): threats contain regex pattern text with
            // quotes/backslashes - serialize with Jackson instead of hand-built
            // String.format JSON, which produced invalid JSON and allowed response
            // body injection.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", "Security threat detected");
            payload.put("threats", threats);
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize security threat response", e);
            body = "{\"error\":\"Security threat detected\"}";
        }

        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    // Helper methods
    private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringListValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        return null;
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.SECURITY_GLOBAL;
    }
}