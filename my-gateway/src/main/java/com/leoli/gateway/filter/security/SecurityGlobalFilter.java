package com.leoli.gateway.filter.security;

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
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

    // SQL Injection patterns
    private static final List<Pattern> SQL_INJECTION_PATTERNS = Arrays.asList(
            // SQL keywords with context
            Pattern.compile("(?i)(\\b(select|insert|update|delete|drop|create|alter|truncate|exec|execute)\\b.*\\b(from|into|table|database|set|where)\\b)"),
            // Union-based
            Pattern.compile("(?i)(union\\s+(all\\s+)?select)"),
            // Comment injection
            Pattern.compile("(--|/\\*|\\*/|#)"),
            // Boolean-based (with or without quotes): OR '1'='1', OR 1=1, OR 'a'='a'
            Pattern.compile("(?i)(\\b(and|or)\\b\\s+(['\"]?\\w+['\"]?\\s*=\\s*['\"]?\\w+['\"]?))"),
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

        log.info("SecurityGlobalFilter - routeId: {}, hasConfig: {}", routeId, config != null);

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

        // Check exclude paths
        List<String> excludePaths = getStringListValue(config, "excludePaths");
        if (excludePaths != null && !excludePaths.isEmpty()) {
            for (String excludePath : excludePaths) {
                if (path.matches(excludePath.replace("*", ".*"))) {
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

        log.info("Security check enabled - SQL: {}, XSS: {}, mode: {}, checkBody: {}",
                enableSqlInjection, enableXss, mode, checkBody);

        List<String> threats = new ArrayList<>();

        // Check query parameters
        if (checkParameters) {
            MultiValueMap<String, String> queryParams = request.getQueryParams();
            for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                String paramName = entry.getKey();
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
     * Check if request has a body.
     */
    private boolean hasBody(ServerHttpRequest request) {
        String method = request.getMethod().name();
        if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)) {
            return false;
        }
        MediaType contentType = request.getHeaders().getContentType();
        return contentType != null &&
                (contentType.includes(MediaType.APPLICATION_JSON) ||
                        contentType.includes(MediaType.APPLICATION_FORM_URLENCODED));
    }

    /**
     * Check request body for threats.
     */
    private Mono<Void> checkRequestBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                        Map<String, Object> config, List<String> existingThreats,
                                        String mode, boolean enableSqlInjection, boolean enableXss) {
        ServerHttpRequest request = exchange.getRequest();

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String body = new String(bytes, StandardCharsets.UTF_8);
                    List<String> threats = new ArrayList<>(existingThreats);
                    checkValue(body, enableSqlInjection, enableXss, threats);

                    if (!threats.isEmpty()) {
                        return handleThreat(exchange, threats, mode, chain);
                    }

                    // Rebuild request with body
                    ServerHttpRequest newRequest = new org.springframework.http.server.reactive.ServerHttpRequestDecorator(request) {
                        @Override
                        public reactor.core.publisher.Flux<DataBuffer> getBody() {
                            return reactor.core.publisher.Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                        }
                    };

                    return chain.filter(exchange.mutate().request(newRequest).build());
                });
    }

    /**
     * Check a value for threats.
     */
    private void checkValue(String value, boolean checkSql, boolean checkXss, List<String> threats) {
        if (value == null || value.isEmpty()) {
            return;
        }

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

        String body = String.format(
                "{\"error\":\"Security threat detected\",\"threats\":[\"%s\"]}",
                String.join("\", \"", threats)
        );

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