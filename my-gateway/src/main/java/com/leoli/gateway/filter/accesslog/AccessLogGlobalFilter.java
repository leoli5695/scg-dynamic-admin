package com.leoli.gateway.filter.accesslog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.accesslog.config.AccessLogConfig;
import com.leoli.gateway.filter.accesslog.detector.BinaryContentDetector;
import com.leoli.gateway.filter.accesslog.sanitizer.SensitiveDataSanitizer;
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
import org.slf4j.LoggerFactory;
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
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.leoli.gateway.constants.GatewayConfigConstants.ACCESS_LOG_CONFIG;
import static com.leoli.gateway.constants.GatewayConfigConstants.GROUP;
import static com.leoli.gateway.filter.accesslog.constants.AccessLogConstants.*;

/**
 * Access log global filter using Logback RollingFileAppender.
 * <p>
 * Features:
 * - Global configuration (not bound to specific route)
 * - Logback handles file rolling (date + size based)
 * - JSON format for log aggregation
 * - Supports stdout/file/both output modes
 * - Includes auth info when available
 * <p>
 * Components:
 * - AccessLogConfig: Configuration model (external class)
 * - BinaryContentDetector: Binary content detection (injected)
 * - SensitiveDataSanitizer: Sensitive data masking (injected)
 * <p>
 * Config Key: config.gateway.access-log
 *
 * @author leoli
 */
@Slf4j
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    // Dedicated logger for access logs (configured in logback-spring.xml)
    private static final Logger ACCESS_LOG_LOGGER = (Logger) LoggerFactory.getLogger(ACCESS_LOG_LOGGER_NAME);

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private RouteManager routeManager;

    @Autowired
    private AuthBindingManager authBindingManager;

    @Autowired
    private BinaryContentDetector binaryContentDetector;

    @Autowired
    private SensitiveDataSanitizer sensitiveDataSanitizer;

    private final ObjectMapper objectMapper;

    @Autowired
    public AccessLogGlobalFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Current config - uses external AccessLogConfig class
    private volatile AccessLogConfig config = new AccessLogConfig();

    // Config listener
    private ConfigCenterService.ConfigListener configListener;

    @PostConstruct
    public void init() {
        loadConfig();
        configListener = (dataId, group, content) -> {
            log.info("Access log config changed");
            loadConfig();
            updateLogbackAppenders();
        };
        configCenterService.addListener(ACCESS_LOG_CONFIG, GROUP, configListener);
        log.info("AccessLogGlobalFilter initialized, enabled: {}", config.isEnabled());
        updateLogbackAppenders();
    }

    @PreDestroy
    public void destroy() {
        if (configListener != null) {
            configCenterService.removeListener(ACCESS_LOG_CONFIG, GROUP, configListener);
        }
    }

    private void loadConfig() {
        try {
            String content = configCenterService.getConfig(ACCESS_LOG_CONFIG, GROUP);
            if (content != null && !content.isEmpty()) {
                config = objectMapper.readValue(content, AccessLogConfig.class);
                log.info("Loaded access log config: enabled={}, mode={}, format={}",
                        config.isEnabled(), config.getDeployMode(), config.getLogFormat());
            }
        } catch (Exception e) {
            log.warn("Failed to load access log config, using defaults: {}", e.getMessage());
        }
    }

    /**
     * Dynamically configure Logback appenders based on current config.
     */
    @SuppressWarnings("unchecked")
    private void updateLogbackAppenders() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger accessLogger = loggerContext.getLogger(ACCESS_LOG_LOGGER_NAME);
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);

        // Clear existing appenders
        accessLogger.detachAndStopAllAppenders();

        if (!config.isEnabled()) {
            accessLogger.setLevel(Level.OFF);
            log.info("Access log disabled, logger set to OFF");
            return;
        }

        String logDirectory = config.getLogDirectory();
        boolean toConsole = config.isLogToConsole();
        boolean toFile = logDirectory != null && !logDirectory.isEmpty();

        if (toFile) {
            Appender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender =
                    (Appender<ch.qos.logback.classic.spi.ILoggingEvent>) rootLogger.getAppender("ACCESS_FILE_ASYNC");
            if (fileAppender != null) {
                accessLogger.addAppender(fileAppender);
                log.info("Access log file output enabled, directory: {}", logDirectory);
                loggerContext.putProperty("ACCESS_LOG_DIRECTORY", logDirectory);
            } else {
                log.warn("ACCESS_FILE_ASYNC appender not found in logback-spring.xml");
            }
        }

        if (toConsole) {
            Appender<ch.qos.logback.classic.spi.ILoggingEvent> consoleAppender =
                    (Appender<ch.qos.logback.classic.spi.ILoggingEvent>) rootLogger.getAppender("ACCESS_CONSOLE");
            if (consoleAppender != null) {
                accessLogger.addAppender(consoleAppender);
                log.info("Access log console output enabled (stdout)");
            } else {
                log.warn("ACCESS_CONSOLE appender not found in logback-spring.xml");
            }
        }

        accessLogger.setLevel(Level.INFO);
        accessLogger.setAdditive(false);

        log.info("Access log appenders configured: file={}, console={}", toFile, toConsole);
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

        // Determine if we need to cache request/response body using BinaryContentDetector
        boolean needRequestBody = config.isLogRequestBody() &&
                binaryContentDetector.shouldCacheRequestBody(request, config);
        boolean needResponseBody = config.isLogResponseBody();
        boolean isFileUpload = binaryContentDetector.isFileUpload(request, config);

        // Mark file upload status for logging
        if (isFileUpload) {
            exchange.getAttributes().put(IS_FILE_UPLOAD_ATTR, true);
        }

        // Skip response body caching for WebSocket/SSE using BinaryContentDetector
        if (needResponseBody && !binaryContentDetector.shouldCacheResponseBody(exchange, exchange.getResponse(), config)) {
            needResponseBody = false;
        }

        final boolean shouldLogResponseBody = needResponseBody;

        ServerWebExchange wrappedExchange = shouldLogResponseBody ?
                wrapExchangeForResponseBody(exchange, routeId) : exchange;

        if (needRequestBody) {
            return cacheRequestBodyAndContinue(wrappedExchange, chain, traceId, requestId, routeId, serviceId,
                    authInfo, method, path, query, clientIp, userAgent, contentType, startTime, shouldLogResponseBody);
        }

        return chain.filter(wrappedExchange).then(Mono.fromRunnable(() -> {
            String responseBody = shouldLogResponseBody ?
                    exchange.getAttribute(RESPONSE_BODY_ATTR) : null;
            logRequest(exchange, traceId, requestId, routeId, serviceId, authInfo, method, path,
                    query, clientIp, userAgent, contentType, null, responseBody, startTime);
        }));
    }

    private ServerWebExchange wrapExchangeForResponseBody(ServerWebExchange exchange, String routeId) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // Check if file download using BinaryContentDetector
                if (binaryContentDetector.isFileDownload(getDelegate(), config)) {
                    log.debug("Skipping response body logging for file download, routeId: {}", routeId);
                    exchange.getAttributes().put(IS_FILE_DOWNLOAD_ATTR, true);
                    return super.writeWith(body);
                }

                return Flux.from(body)
                        .collectList()
                        .flatMap(buffers -> {
                            if (buffers.isEmpty()) {
                                return super.writeWith(Flux.empty());
                            }

                            int totalSize = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
                            byte[] allBytes = new byte[totalSize];
                            int offset = 0;
                            for (DataBuffer buffer : buffers) {
                                int size = buffer.readableByteCount();
                                buffer.read(allBytes, offset, size);
                                offset += size;
                                DataBufferUtils.release(buffer);
                            }

                            // Use SensitiveDataSanitizer for masking and truncation
                            String bodyContent = sensitiveDataSanitizer.sanitizeBody(
                                    new String(allBytes, StandardCharsets.UTF_8),
                                    getHeaders().getContentType() != null ?
                                            getHeaders().getContentType().toString() : null,
                                    config);
                            exchange.getAttributes().put(RESPONSE_BODY_ATTR, bodyContent);

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

                    // Use SensitiveDataSanitizer for masking and truncation
                    final String requestBody = sensitiveDataSanitizer.sanitizeBody(
                            new String(bytes, StandardCharsets.UTF_8),
                            contentType,
                            config);

                    ServerHttpRequest newRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                        }
                    };

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

            String finalTraceId = traceId;
            if (finalTraceId == null) {
                finalTraceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
            }

            AuthInfo finalAuthInfo = authInfo;
            if (finalAuthInfo.authType == null && finalAuthInfo.authUser == null) {
                finalAuthInfo = getAuthInfo(exchange, routeId);
            }

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("@timestamp", Instant.ofEpochMilli(startTime).toString());
            logEntry.put("traceId", finalTraceId);
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

            if (finalAuthInfo.authType != null) {
                logEntry.put("authType", finalAuthInfo.authType);
            }
            if (finalAuthInfo.authPolicy != null) {
                logEntry.put("authPolicy", finalAuthInfo.authPolicy);
            }
            if (finalAuthInfo.authUser != null) {
                logEntry.put("authUser", finalAuthInfo.authUser);
            }

            // Use SensitiveDataSanitizer for header filtering
            if (config.isLogRequestHeaders()) {
                logEntry.put("requestHeaders", convertHeadersToMap(
                        sensitiveDataSanitizer.filterHeaders(exchange.getRequest().getHeaders(), config)));
            }

            if (requestBody != null && !requestBody.isEmpty() && config.isLogRequestBody()) {
                logEntry.put("requestBody", requestBody);
            }

            Boolean isFileUpload = exchange.getAttribute(IS_FILE_UPLOAD_ATTR);
            if (isFileUpload != null && isFileUpload && config.isLogRequestBody()) {
                logEntry.put("requestBody", "[BINARY_FILE_UPLOAD]");
            }

            if (config.isLogResponseHeaders()) {
                logEntry.put("responseHeaders", convertHeadersToMap(
                        sensitiveDataSanitizer.filterHeaders(exchange.getResponse().getHeaders(), config)));
            }

            if (responseBody != null && !responseBody.isEmpty() && config.isLogResponseBody()) {
                logEntry.put("responseBody", responseBody);
            }

            Boolean isFileDownload = exchange.getAttribute(IS_FILE_DOWNLOAD_ATTR);
            if (isFileDownload != null && isFileDownload && config.isLogResponseBody()) {
                logEntry.put("responseBody", "[BINARY_FILE_DOWNLOAD]");
            }

            String jsonLine = objectMapper.writeValueAsString(logEntry);
            ACCESS_LOG_LOGGER.info(jsonLine);

        } catch (Exception e) {
            log.error("Failed to write access log: {}", e.getMessage());
        }
    }

    /**
     * Convert HttpHeaders to Map for JSON serialization.
     */
    private Map<String, String> convertHeadersToMap(HttpHeaders headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((key, values) -> result.put(key, String.join(", ", values)));
        return result;
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
        return FilterOrderConstants.ACCESS_LOG;
    }

    /**
     * Auth info holder class.
     */
    @Data
    private static class AuthInfo {
        String authType;
        String authPolicy;
        String authUser;
    }
}