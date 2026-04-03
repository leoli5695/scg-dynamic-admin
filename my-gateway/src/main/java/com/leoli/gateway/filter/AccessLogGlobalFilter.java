package com.leoli.gateway.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.model.MultiServiceConfig;
import com.leoli.gateway.util.RouteUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Access log global filter with file output support.
 * <p>
 * Features:
 * - Global configuration (not bound to specific route)
 * - Output to file directory (configurable path)
 * - JSON format for log aggregation
 * - Supports LOCAL/DOCKER/K8S deployment modes
 * - Includes auth info when available
 * <p>
 * Config Key: config.gateway.access-log
 *
 * @author leoli
 */
@Slf4j
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    private static final String CONFIG_KEY = "config.gateway.access-log";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String START_TIME_ATTR = "accessLogStartTime";
    private static final String REQUEST_ID_ATTR = "requestId";
    private static final String RESPONSE_BODY_ATTR = "accessLogResponseBody";

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private RouteManager routeManager;

    @Autowired
    private AuthBindingManager authBindingManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final DateTimeFormatter fileDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Current config
    private volatile AccessLogConfig config = new AccessLogConfig();

    // File writer state
    private volatile Path currentLogPath;
    private volatile String currentDate;
    private final ReentrantLock fileLock = new ReentrantLock();

    // Config listener
    private ConfigCenterService.ConfigListener configListener;

    @PostConstruct
    public void init() {
        loadConfig();
        configListener = (dataId, group, content) -> {
            log.info("🔥 Access log config changed");
            loadConfig();
        };
        configCenterService.addListener(CONFIG_KEY, GROUP, configListener);
        log.info("✅ AccessLogGlobalFilter initialized, enabled: {}", config.isEnabled());
    }

    @PreDestroy
    public void destroy() {
        if (configListener != null) {
            configCenterService.removeListener(CONFIG_KEY, GROUP, configListener);
        }
    }

    private void loadConfig() {
        try {
            String content = configCenterService.getConfig(CONFIG_KEY, GROUP);
            if (content != null && !content.isEmpty()) {
                config = objectMapper.readValue(content, AccessLogConfig.class);
                log.info("✅ Loaded access log config: enabled={}, mode={}, format={}",
                        config.enabled, config.deployMode, config.logFormat);

                // Ensure log directory exists
                ensureLogDirectory();
            }
        } catch (Exception e) {
            log.warn("Failed to load access log config, using defaults: {}", e.getMessage());
        }
    }

    private void ensureLogDirectory() {
        String logDir = config.getLogDirectory();
        if (logDir != null && !logDir.isEmpty()) {
            try {
                Path path = Paths.get(logDir);
                if (!Files.exists(path)) {
                    Files.createDirectories(path);
                    log.info("Created log directory: {}", logDir);
                }
            } catch (Exception e) {
                log.error("Failed to create log directory: {}", logDir, e);
            }
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!config.isEnabled()) {
            return chain.filter(exchange);
        }

        // Check sampling rate
        if (config.getSamplingRate() < 100 &&
                ThreadLocalRandom.current().nextInt(100) >= config.getSamplingRate()) {
            return chain.filter(exchange);
        }

        // Record start time
        long startTime = System.currentTimeMillis();
        exchange.getAttributes().put(START_TIME_ATTR, startTime);
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);

        ServerHttpRequest request = exchange.getRequest();
        String routeId = RouteUtils.getRouteId(exchange);

        // Capture request data
        final String method = request.getMethod().name();
        final String path = request.getURI().getPath();
        final String query = request.getURI().getQuery();
        final String clientIp = getClientIp(request);
        final String userAgent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
        final String contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        final String traceId = request.getHeaders().getFirst("X-Trace-Id");
        final String serviceId = getServiceId(routeId);
        final AuthInfo authInfo = getAuthInfo(exchange, routeId);

        // Determine if we need to cache request/response body
        boolean needRequestBody = config.isLogRequestBody() && shouldCacheBody(request);
        boolean needResponseBody = config.isLogResponseBody();

        // Wrap exchange for response body caching if needed
        ServerWebExchange wrappedExchange = needResponseBody ?
                wrapExchangeForResponseBody(exchange) : exchange;

        // Check if need to cache request body
        if (needRequestBody) {
            return cacheRequestBodyAndContinue(wrappedExchange, chain, traceId, requestId, routeId, serviceId,
                    authInfo, method, path, query, clientIp, userAgent, contentType, startTime, needResponseBody);
        }

        // Continue without caching request body
        return chain.filter(wrappedExchange).then(Mono.fromRunnable(() -> {
            String responseBody = needResponseBody ?
                    exchange.getAttribute(RESPONSE_BODY_ATTR) : null;
            logRequest(exchange, traceId, requestId, routeId, serviceId, authInfo, method, path,
                    query, clientIp, userAgent, contentType, null, responseBody, startTime);
        }));
    }

    private ServerWebExchange wrapExchangeForResponseBody(ServerWebExchange exchange) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // Convert any Publisher to Flux for uniform handling
                return Flux.from(body)
                        .collectList()
                        .flatMap(buffers -> {
                            if (buffers.isEmpty()) {
                                return super.writeWith(Flux.empty());
                            }
                            
                            // Combine all buffers into one
                            int totalSize = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
                            byte[] allBytes = new byte[totalSize];
                            int offset = 0;
                            for (DataBuffer buffer : buffers) {
                                int size = buffer.readableByteCount();
                                buffer.read(allBytes, offset, size);
                                offset += size;
                                DataBufferUtils.release(buffer);
                            }
                            
                            // Store response body for logging
                            String bodyContent = truncate(new String(allBytes, StandardCharsets.UTF_8), config.getMaxBodyLength());
                            exchange.getAttributes().put(RESPONSE_BODY_ATTR, maskSensitiveFields(bodyContent));
                            
                            // Write the buffer and return Mono<Void>
                            return super.writeWith(Flux.just(bufferFactory.wrap(allBytes)));
                        });
            }
        };

        return exchange.mutate().response(decoratedResponse).build();
    }

    private Mono<Void> cacheRequestBodyAndContinue(ServerWebExchange exchange, GatewayFilterChain chain,
                                                   String traceId, String requestId, String routeId,
                                                   String serviceId, AuthInfo authInfo, String method,
                                                   String path, String query, String clientIp,
                                                   String userAgent, String contentType, long startTime,
                                                   boolean needResponseBody) {
        ServerHttpRequest request = exchange.getRequest();

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(new DefaultDataBufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String bodyStr = truncate(new String(bytes, StandardCharsets.UTF_8), config.getMaxBodyLength());
                    final String requestBody = maskSensitiveFields(bodyStr);

                    ServerHttpRequest newRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                        }
                    };

                    // Preserve the response decorator by keeping the same response
                    ServerWebExchange mutatedExchange = exchange.mutate()
                            .request(newRequest)
                            .response(exchange.getResponse())
                            .build();

                    return chain.filter(mutatedExchange)
                            .then(Mono.fromRunnable(() -> {
                                String responseBody = needResponseBody ?
                                        exchange.getAttribute(RESPONSE_BODY_ATTR) : null;
                                logRequest(exchange, traceId, requestId, routeId, serviceId, authInfo,
                                        method, path, query, clientIp, userAgent, contentType,
                                        requestBody, responseBody, startTime);
                            }));
                });
    }

    private void logRequest(ServerWebExchange exchange, String traceId, String requestId, String routeId,
                            String serviceId, AuthInfo authInfo, String method, String path, String query,
                            String clientIp, String userAgent, String contentType, String requestBody,
                            String responseBody, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            int statusCode = exchange.getResponse().getStatusCode() != null ?
                    exchange.getResponse().getStatusCode().value() : 0;

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("@timestamp", Instant.ofEpochMilli(startTime).toString());
            logEntry.put("traceId", traceId);
            logEntry.put("requestId", requestId);
            logEntry.put("routeId", routeId);
            logEntry.put("serviceId", serviceId);
            logEntry.put("method", method);
            logEntry.put("path", path);
            logEntry.put("query", query);
            logEntry.put("clientIp", clientIp);
            logEntry.put("userAgent", userAgent);
            logEntry.put("statusCode", statusCode);
            logEntry.put("durationMs", duration);

            // Auth info
            if (authInfo.authType != null) {
                logEntry.put("authType", authInfo.authType);
            }
            if (authInfo.authPolicy != null) {
                logEntry.put("authPolicy", authInfo.authPolicy);
            }
            if (authInfo.authUser != null) {
                logEntry.put("authUser", authInfo.authUser);
            }

            // Request headers
            if (config.isLogRequestHeaders()) {
                logEntry.put("requestHeaders", filterHeaders(exchange.getRequest().getHeaders()));
            }

            // Request body
            if (requestBody != null && !requestBody.isEmpty() && config.isLogRequestBody()) {
                logEntry.put("requestBody", requestBody);
            }

            // Response headers
            if (config.isLogResponseHeaders()) {
                logEntry.put("responseHeaders", filterHeaders(exchange.getResponse().getHeaders()));
            }

            // Response body
            if (responseBody != null && !responseBody.isEmpty() && config.isLogResponseBody()) {
                logEntry.put("responseBody", responseBody);
            }

            // Write to file and/or console
            String jsonLine = objectMapper.writeValueAsString(logEntry);
            writeToFile(jsonLine);

            if (config.isLogToConsole()) {
                log.info("ACCESS_LOG: {}", jsonLine);
            }

        } catch (Exception e) {
            log.error("Failed to write access log: {}", e.getMessage());
        }
    }

    private void writeToFile(String line) {
        if (!config.isEnabled() || config.getLogDirectory() == null || config.getLogDirectory().isEmpty()) {
            return;
        }

        try {
            String today = LocalDateTime.now().format(fileDateFormatter);

            fileLock.lock();
            try {
                // Check if need to rollover to new file
                if (!today.equals(currentDate) || currentLogPath == null) {
                    currentDate = today;
                    String fileName = config.getFileNamePattern()
                            .replace("{yyyy-MM-dd}", today)
                            .replace("{date}", today);
                    currentLogPath = Paths.get(config.getLogDirectory(), fileName);
                }

                // Write line to file
                Files.writeString(currentLogPath, line + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } finally {
                fileLock.unlock();
            }
        } catch (Exception e) {
            log.error("Failed to write log to file: {}", e.getMessage());
        }
    }

    private String getServiceId(String routeId) {
        if (routeId == null) return null;
        RouteDefinition route = routeManager.getRoute(routeId);
        if (route == null) return null;

        if (route.getMetadata() != null) {
            Object multiConfig = route.getMetadata().get(MultiServiceConfig.METADATA_KEY);
            if (multiConfig instanceof MultiServiceConfig) {
                MultiServiceConfig msc = (MultiServiceConfig) multiConfig;
                if (msc.getServiceId() != null) return msc.getServiceId();
                if (msc.getServices() != null && !msc.getServices().isEmpty()) {
                    return msc.getServices().get(0).getServiceId();
                }
            }
        }

        if (route.getUri() != null) {
            String uri = route.getUri().toString();
            if (uri.contains("://")) {
                return uri.substring(uri.indexOf("://") + 3);
            }
        }
        return null;
    }

    private AuthInfo getAuthInfo(ServerWebExchange exchange, String routeId) {
        AuthInfo info = new AuthInfo();

        Set<String> policyIds = authBindingManager.getPoliciesForRoute(routeId);
        if (policyIds != null && !policyIds.isEmpty()) {
            for (String policyId : policyIds) {
                AuthConfig cfg = authBindingManager.getAuthConfig(policyId);
                if (cfg != null && cfg.isEnabled()) {
                    info.authPolicy = policyId;
                    info.authType = cfg.getAuthType();
                    break;
                }
            }
        }

        Map<String, Object> attrs = exchange.getAttributes();
        if (attrs.containsKey("auth_user")) {
            info.authUser = (String) attrs.get("auth_user");
            if (info.authType == null) info.authType = "BASIC";
        }
        if (attrs.containsKey("jwt_subject")) {
            info.authUser = (String) attrs.get("jwt_subject");
            if (info.authType == null) info.authType = "JWT";
        }
        if (attrs.containsKey("api_key")) {
            info.authUser = (String) attrs.get("api_key");
            if (info.authType == null) info.authType = "API_KEY";
        }
        if (attrs.containsKey("auth_access_key")) {
            info.authUser = (String) attrs.get("auth_access_key");
            if (info.authType == null) info.authType = "HMAC";
        }
        if (attrs.containsKey("oauth2_username")) {
            info.authUser = (String) attrs.get("oauth2_username");
            if (info.authType == null) info.authType = "OAUTH2";
        }

        return info;
    }

    private Map<String, String> filterHeaders(HttpHeaders headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((key, values) -> {
            if (!isSensitiveHeader(key)) {
                result.put(key, String.join(", ", values));
            } else {
                result.put(key, "******");
            }
        });
        return result;
    }

    private boolean isSensitiveHeader(String header) {
        String lower = header.toLowerCase();
        return lower.contains("authorization") ||
                lower.contains("cookie") ||
                lower.contains("api-key") ||
                lower.contains("x-api-key") ||
                lower.contains("token");
    }

    private String maskSensitiveFields(String body) {
        if (body == null || body.isEmpty() || config.getSensitiveFields() == null) {
            return body;
        }
        String result = body;
        for (String field : config.getSensitiveFields()) {
            result = result.replaceAll("(?i)\"" + field + "\"\\s*:\\s*\"[^\"]*\"",
                    "\"" + field + "\":\"******\"");
        }
        return result;
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated)";
    }

    private boolean shouldCacheBody(ServerHttpRequest request) {
        String method = request.getMethod().name();
        if (HttpMethod.GET.name().equals(method) || HttpMethod.HEAD.name().equals(method)) {
            return false;
        }
        MediaType contentType = request.getHeaders().getContentType();
        if (contentType == null) return false;
        return contentType.includes(MediaType.APPLICATION_JSON) ||
                contentType.includes(MediaType.APPLICATION_FORM_URLENCODED) ||
                contentType.includes(MediaType.TEXT_PLAIN);
    }

    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            int index = ip.indexOf(",");
            return index != -1 ? ip.substring(0, index).trim() : ip.trim();
        }
        ip = request.getHeaders().getFirst("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddress() != null ?
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    @Override
    public int getOrder() {
        return -400;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccessLogConfig {
        private boolean enabled = false;
        private String deployMode = "LOCAL";
        private String logDirectory = "./logs/access";
        private String fileNamePattern = "access-{yyyy-MM-dd}.log";
        private String logFormat = "JSON";
        private String logLevel = "NORMAL";  // MINIMAL, NORMAL, VERBOSE
        private boolean logRequestHeaders = true;
        private boolean logResponseHeaders = true;
        private boolean logRequestBody = false;
        private boolean logResponseBody = false;
        private int maxBodyLength = 2048;
        private int samplingRate = 100;
        private List<String> sensitiveFields = Arrays.asList("password", "token", "secret", "apiKey");
        private boolean logToConsole = true;
        private boolean includeAuthInfo = true;
        private int maxFileSizeMb = 100;
        private int maxBackupFiles = 30;

        public String getLogDirectory() {
            switch (deployMode) {
                case "DOCKER":
                    return "/app/logs/access";
                case "K8S":
                    return "/var/log/gateway/access";
                case "CUSTOM":
                    return logDirectory;
                default:
                    return "./logs/access";
            }
        }
    }

    @Data
    private static class AuthInfo {
        String authType;
        String authPolicy;
        String authUser;
    }

}