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
import com.leoli.gateway.util.ClientIpResolver;
import com.leoli.gateway.util.ConfigContentTracker;
import com.leoli.gateway.util.RouteUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.annotation.DependsOn;
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
import org.springframework.scheduling.annotation.Scheduled;
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
@DependsOn("nacosConfigServiceWrapper")
@RequiredArgsConstructor
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    // Dedicated logger for access logs (configured in logback-spring.xml)
    private static final Logger ACCESS_LOG_LOGGER = (Logger) LoggerFactory.getLogger(ACCESS_LOG_LOGGER_NAME);

    // FIX: upper bound for response body aggregation in access log capture (default 1MB).
    // Responses larger than this are passed through without body capture to avoid
    // heap spikes and broken streaming behavior.
    private static final long MAX_RESPONSE_BODY_LOG_SIZE = 1024L * 1024;

    // FIX (OOM): upper bound for request body aggregation. Previously DataBufferUtils.join
    // was called WITHOUT any limit, so a large POST to a body-logging route could be used
    // to exhaust heap memory from outside.
    private static final long MAX_REQUEST_BODY_LOG_SIZE = 1024L * 1024;

    private final ConfigCenterService configCenterService;
    private final RouteManager routeManager;
    private final AuthBindingManager authBindingManager;
    private final BinaryContentDetector binaryContentDetector;
    private final SensitiveDataSanitizer sensitiveDataSanitizer;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    // Current config - uses external AccessLogConfig class
    private volatile AccessLogConfig config = new AccessLogConfig();

    // Content the config was last applied with, so the periodic reconcile can tell
    // "unchanged" from "edited" instead of rebuilding the Logback appenders every cycle.
    private final ConfigContentTracker appliedContent = new ConfigContentTracker();

    // Config listener
    private ConfigCenterService.ConfigListener configListener;

    @PostConstruct
    public void init() {
        loadConfig();
        configListener = (dataId, group, content) -> {
            log.info("Access log config changed");
            applyConfig(content);
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
        log.info("Loading access log config from Nacos: dataId={}, group={}", ACCESS_LOG_CONFIG, GROUP);
        applyConfig(configCenterService.getConfig(ACCESS_LOG_CONFIG, GROUP));
    }

    /**
     * Parse and install an access-log config payload.
     * <p>
     * A blank payload keeps the current config rather than reverting to defaults:
     * {@code getConfig} returns null both when the key was deleted and when the config
     * center is unreachable, and silently changing what a running gateway logs on a
     * transient blip is worse than holding the last known good value.
     */
    private void applyConfig(String content) {
        if (content == null || content.isBlank()) {
            log.warn("Access log config not found in Nacos (dataId={}, group={}), keeping current: enabled={}",
                    ACCESS_LOG_CONFIG, GROUP, config.isEnabled());
            return;
        }
        appliedContent.recordIfChanged(ACCESS_LOG_CONFIG, content);
        try {
            config = objectMapper.readValue(content, AccessLogConfig.class);
            log.info("Loaded access log config successfully: enabled={}, mode={}, format={}, logToConsole={}, logDirectory={}",
                    config.isEnabled(), config.getDeployMode(), config.getLogFormat(),
                    config.isLogToConsole(), config.getLogDirectory());
        } catch (Exception e) {
            log.warn("Failed to load access log config, keeping current: enabled={}, error={}",
                    config.isEnabled(), e.getMessage());
        }
    }

    /**
     * Periodic reconcile against the config center.
     * <p>
     * Stands in for change pushes, which this deployment never receives (nacos-client
     * 3.0.3 against nacos-server 2.5.4 - measured live: the server's remote-push.log stays
     * empty while the publish succeeds). Without it, flipping {@code enabled} or changing
     * the log directory in the console had no effect until the gateway restarted, because
     * the config lives in a field that only the listener used to write.
     */
    @Scheduled(fixedRateString = "${gateway.config-sync.interval-ms:60000}")
    public void periodicSyncAccessLogConfig() {
        try {
            String content = configCenterService.getConfig(ACCESS_LOG_CONFIG, GROUP);
            if (content == null || content.isBlank()) {
                return;
            }
            if (appliedContent.recordIfChanged(ACCESS_LOG_CONFIG, content)) {
                log.info("🔍 Periodic sync found an access log config change");
                applyConfig(content);
                updateLogbackAppenders();
            }
        } catch (Exception e) {
            log.warn("Periodic access log config sync failed - changes stay unapplied until it succeeds", e);
        }
    }

    /**
     * Dynamically create and configure Logback appenders based on current config.
     * Appenders are created programmatically to avoid Logback XML parsing issues.
     */
    private void updateLogbackAppenders() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger accessLogger = loggerContext.getLogger(ACCESS_LOG_LOGGER_NAME);

        // Clear existing appenders first
        accessLogger.detachAndStopAllAppenders();

        if (!config.isEnabled()) {
            accessLogger.setLevel(Level.OFF);
            log.info("Access log disabled, logger set to OFF");
            return;
        }

        String logDirectory = config.getLogDirectory();
        boolean toConsole = config.isLogToConsole();
        boolean toFile = logDirectory != null && !logDirectory.isEmpty();

        // Create console appender (pure JSON output for stdout)
        if (toConsole) {
            ch.qos.logback.core.ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> consoleAppender =
                    new ch.qos.logback.core.ConsoleAppender<>();
            consoleAppender.setName("ACCESS_CONSOLE");
            consoleAppender.setContext(loggerContext);

            // Create pattern layout for pure JSON output
            ch.qos.logback.classic.PatternLayout layout = new ch.qos.logback.classic.PatternLayout();
            layout.setPattern("%msg%n");
            layout.setContext(loggerContext);
            layout.start();

            ch.qos.logback.core.encoder.LayoutWrappingEncoder<ch.qos.logback.classic.spi.ILoggingEvent> encoder =
                    new ch.qos.logback.core.encoder.LayoutWrappingEncoder<>();
            encoder.setLayout(layout);
            encoder.setContext(loggerContext);
            encoder.start();
            consoleAppender.setEncoder(encoder);
            consoleAppender.start();
            accessLogger.addAppender(consoleAppender);
            log.info("Access log console output enabled (stdout)");
        }

        // Create file appender with async wrapper
        if (toFile) {
            // Create rolling file appender
            ch.qos.logback.core.rolling.RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fileAppender =
                    new ch.qos.logback.core.rolling.RollingFileAppender<>();
            fileAppender.setName("ACCESS_FILE");
            fileAppender.setContext(loggerContext);
            fileAppender.setFile(logDirectory + "/access.log");

            // Configure rolling policy
            ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy<ch.qos.logback.classic.spi.ILoggingEvent> rollingPolicy =
                    new ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy<>();
            rollingPolicy.setContext(loggerContext);
            rollingPolicy.setParent(fileAppender);
            rollingPolicy.setFileNamePattern(logDirectory + "/access-%d{yyyy-MM-dd}.%i.log");
            rollingPolicy.setMaxFileSize(ch.qos.logback.core.util.FileSize.valueOf(config.getMaxFileSizeMb() + "MB"));
            rollingPolicy.setMaxHistory(config.getMaxBackupFiles());
            rollingPolicy.setTotalSizeCap(ch.qos.logback.core.util.FileSize.valueOf("10GB"));
            rollingPolicy.setCleanHistoryOnStart(true);
            rollingPolicy.start();

            fileAppender.setRollingPolicy(rollingPolicy);

            // Create pattern layout for pure JSON output
            ch.qos.logback.classic.PatternLayout fileLayout = new ch.qos.logback.classic.PatternLayout();
            fileLayout.setPattern("%msg%n");
            fileLayout.setContext(loggerContext);
            fileLayout.start();

            ch.qos.logback.core.encoder.LayoutWrappingEncoder<ch.qos.logback.classic.spi.ILoggingEvent> fileEncoder =
                    new ch.qos.logback.core.encoder.LayoutWrappingEncoder<>();
            fileEncoder.setLayout(fileLayout);
            fileEncoder.setContext(loggerContext);
            fileEncoder.start();
            fileAppender.setEncoder(fileEncoder);
            fileAppender.start();

            // Create async appender wrapper
            ch.qos.logback.classic.AsyncAppender asyncAppender = new ch.qos.logback.classic.AsyncAppender();
            asyncAppender.setName("ACCESS_FILE_ASYNC");
            asyncAppender.setContext(loggerContext);
            asyncAppender.setQueueSize(1024);
            asyncAppender.setDiscardingThreshold(0);
            asyncAppender.setNeverBlock(true);
            asyncAppender.setIncludeCallerData(false);
            asyncAppender.addAppender(fileAppender);
            asyncAppender.start();
            accessLogger.addAppender(asyncAppender);

            log.info("Access log file output enabled, directory: {}", logDirectory);
        }

        accessLogger.setLevel(Level.INFO);
        accessLogger.setAdditive(false);

        log.info("Access log enabled: file={}, console={}", toFile, toConsole);
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
        final String clientIp = getClientIp(exchange);
        final String userAgent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
        final String contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        final String traceId = request.getHeaders().getFirst("X-Trace-Id");
        final String serviceId = getServiceId(routeId);
        final AuthInfo authInfo = getAuthInfo(exchange, routeId);

        // Determine if we need to cache request/response body using BinaryContentDetector
        boolean needRequestBody = config.isLogRequestBody() &&
                binaryContentDetector.shouldCacheRequestBody(request, config);
        // FIX (OOM): when Content-Length already exceeds the capture limit, skip body caching
        // entirely - the body stream stays untouched and is forwarded as-is.
        long requestContentLength = request.getHeaders().getContentLength();
        if (needRequestBody && requestContentLength > MAX_REQUEST_BODY_LOG_SIZE) {
            log.debug("Request body ({} bytes) exceeds access log capture limit ({} bytes), skipping body logging",
                    requestContentLength, MAX_REQUEST_BODY_LOG_SIZE);
            needRequestBody = false;
        }
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

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // FIX: response headers are only available at write time (NOT in the pre-chain
                // phase where they were previously checked), so SSE/download detection must
                // happen here to actually take effect.
                if (binaryContentDetector.isFileDownload(getDelegate(), config)) {
                    log.debug("Skipping response body logging for file download, routeId: {}", routeId);
                    exchange.getAttributes().put(IS_FILE_DOWNLOAD_ATTR, true);
                    return super.writeWith(body);
                }
                if (binaryContentDetector.isSseResponse(getHeaders().getContentType())) {
                    log.debug("Skipping response body logging for SSE response, routeId: {}", routeId);
                    return super.writeWith(body);
                }

                // FIX (streaming + OOM): the previous implementation used collectList() to aggregate
                // the ENTIRE response into memory before checking the size cap, so first-byte latency
                // became the full response time, SSE/streaming responses were fully buffered, and large
                // downloads piled up in heap. Now chunks stream through untouched while their content
                // is copied into a bounded heap buffer for logging.
                final java.io.ByteArrayOutputStream capture = new java.io.ByteArrayOutputStream();
                final java.util.concurrent.atomic.AtomicBoolean exceeded = new java.util.concurrent.atomic.AtomicBoolean(false);
                // var keeps the capture-of-? extends DataBuffer typing; writeWith accepts it
                var streamingBody = Flux.from(body).doOnNext(buffer -> {
                    if (!exceeded.get()) {
                        int size = buffer.readableByteCount();
                        if (capture.size() + size > MAX_RESPONSE_BODY_LOG_SIZE) {
                            exceeded.set(true);
                        } else {
                            // Copy chunk content without consuming the buffer's read position
                            java.nio.ByteBuffer view = buffer.asByteBuffer();
                            byte[] tmp = new byte[view.remaining()];
                            view.get(tmp);
                            capture.write(tmp, 0, tmp.length);
                        }
                    }
                });
                return super.writeWith(streamingBody)
                        .doFinally(signal -> {
                            if (!exceeded.get() && capture.size() > 0) {
                                try {
                                    String bodyContent = sensitiveDataSanitizer.sanitizeBody(
                                            capture.toString(StandardCharsets.UTF_8),
                                            getHeaders().getContentType() != null ?
                                                    getHeaders().getContentType().toString() : null,
                                            config);
                                    exchange.getAttributes().put(RESPONSE_BODY_ATTR, bodyContent);
                                } catch (Exception e) {
                                    log.error("Error sanitizing response body for access log: {}", e.getMessage());
                                }
                            } else if (exceeded.get()) {
                                log.debug("Response body exceeded access log capture limit {} bytes, body not logged",
                                        MAX_RESPONSE_BODY_LOG_SIZE);
                            }
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

        // FIX (OOM): bounded join. Content-Length pre-check happens in filter(); this limit also
        // guards chunked requests. If the limit is exceeded mid-stream the body is already partially
        // consumed and cannot be forwarded safely, so reject with 413 instead of forwarding garbage.
        return DataBufferUtils.join(request.getBody(), (int) MAX_REQUEST_BODY_LOG_SIZE)
                .defaultIfEmpty(new DefaultDataBufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    // Read body bytes with guaranteed release to prevent direct buffer memory leak
                    final byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    try {
                        dataBuffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }

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
                            .then(Mono.<Void>fromRunnable(() -> {
                                String responseBody = needResponseBody ?
                                        exchange.getAttribute(RESPONSE_BODY_ATTR) : null;
                                logRequest(exchange, traceId, requestId, routeId, serviceId, authInfo,
                                        method, path, query, clientIp, userAgent, contentType,
                                        requestBody, responseBody, startTime);
                            }));
                })
                .onErrorResume(org.springframework.core.io.buffer.DataBufferLimitException.class, e -> {
                    log.warn("Request body exceeds access log capture limit ({} bytes) for route {}, "
                            + "rejecting with 413 (body stream already partially consumed)",
                            MAX_REQUEST_BODY_LOG_SIZE, routeId);
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
                    return response.setComplete();
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

    private String getClientIp(ServerWebExchange exchange) {
        // FIX (spoofable access-log IP): this used to read X-Forwarded-For / X-Real-IP straight off
        // the request, both of which the caller sets. Anyone could write any client IP into the
        // access log - including someone else's, which is how an attacker covers their tracks, and
        // including a colleague's, which is how an innocent operator gets blamed. ClientIpResolver
        // is what IPFilterGlobalFilter and both rate limiters already use: it honours the forwarded
        // header only when the TCP peer is a configured trusted proxy, and falls back to the peer
        // address, which the caller cannot forge.
        return clientIpResolver.resolve(exchange);
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