package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.TraceIdGlobalFilter;
import com.leoli.gateway.filter.ai.AiProviderRoutingFilter;
import com.leoli.gateway.limiter.TokenQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.model.TokenQuotaConfig;
import com.leoli.gateway.parser.TokenUsageParser;
import com.leoli.gateway.parser.TokenUsageParser.TokenUsage;
import com.leoli.gateway.parser.TokenUsageParser.ResponseType;
import com.leoli.gateway.util.RouteUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token Usage Collector Filter.
 *
 * Collects token usage AFTER response is received.
 * Parses usage field from response body and updates Redis counter.
 *
 * Execution Order: -44 (MUST be < NettyWriteResponseFilter's -1)
 * - SCG only writes the client-bound body through response decorators installed by
 *   filters ordered BEFORE NettyWriteResponseFilter (-1). At the old order (101) this
 *   decorator's writeWith/writeAndFlushWith were never invoked on the real proxy path,
 *   so usage always parsed as 0 (only unit tests that called writeWith directly passed).
 * - Runs after TokenQuotaPreCheckFilter (-243), so the tenant attribute is already set.
 *
 * Response Type Detection:
 * - JSON: Parse usage field from complete response body
 * - SSE: Aggregate incremental usage from stream chunks
 *
 * @author leoli
 */
@Component
@Slf4j
public class TokenUsageCollectorFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired(required = false)
    private TokenQuotaManager tokenQuotaManager;

    @Autowired
    private TokenUsageParser tokenUsageParser;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private TokenUsageReporter tokenUsageReporter;

    @Value("${gateway.instance-id:gateway-1}")
    private String instanceId;

    @Value("${gateway.token-usage.max-body-size:65536}")
    private int maxBodySize;

    // Exchange attribute keys
    private static final String ATTR_PRE_DEDUCTED = "token_quota_pre_deducted";
    private static final String ATTR_ESTIMATED_INPUT = "token_quota_estimated_input";
    private static final String ATTR_TENANT_ID = "token_quota_tenant_id";
    private static final String ATTR_STREAM_INTERRUPTED = "token_quota_stream_interrupted";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get token quota configuration
        ConfigWrapper config = getConfig(routeId);

        if (!config.enabled || !config.tokenQuotaConfig.isEnabled()) {
            return chain.filter(exchange);
        }

        // Get tenant ID from exchange attributes (set by PreCheckFilter)
        String tenantId = exchange.getAttribute(ATTR_TENANT_ID);

        if (tenantId == null) {
            log.debug("No tenant ID in exchange attributes, skipping token collection");
            return chain.filter(exchange);
        }

        if (tokenQuotaManager == null) {
            log.warn("TokenQuotaManager not available, skipping token collection");
            return chain.filter(exchange);
        }

        // Get pre-deducted tokens from PreCheckFilter (if any)
        Long preDeductedAttr = exchange.getAttribute(ATTR_PRE_DEDUCTED);
        final long preDeductedTokens = preDeductedAttr != null ? preDeductedAttr : 0L;

        // Memory-controlled buffer for response capture
        MemoryAwareBuffer buffer = new MemoryAwareBuffer(
                config.tokenQuotaConfig.getSseMaxBufferSize(),
                config.tokenQuotaConfig.getSseMaxChunks(),
                maxBodySize
        );

        // Track stream state
        AtomicInteger totalTokens = new AtomicInteger(0);
        AtomicInteger bufferedChars = new AtomicInteger(0);
        // Netty's read boundaries have nothing to do with SSE event boundaries: one
        // buffer can hold several events, and an event can be split across two.
        // Reassemble before parsing or the usage block is silently lost.
        SseFrameAccumulator frames = new SseFrameAccumulator();

        // Wrap response to intercept body
        ServerHttpResponse decoratedResponse = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                Flux<? extends DataBuffer> flux = Flux.from(body);

                // Evaluated here, not in the pre-phase: the upstream Content-Type only
                // exists once SCG has copied the response headers, i.e. at write time.
                // Deciding earlier left SSE detection depending solely on the client's
                // Accept header, so a streaming response requested with Accept:*/* was
                // parsed as JSON and billed as 0 tokens.
                if (!isSseRequest(exchange)) {
                    // FIX (dead headers): X-Token-Usage-* were added in doFinally,
                    // AFTER the response was committed, so they were never sent.
                    // For JSON responses aggregate the body first, parse usage and
                    // set the headers BEFORE writing. Known-oversized bodies fall
                    // back to plain streaming (no headers) to keep memory bounded.
                    long contentLength = getHeaders().getContentLength();
                    if (contentLength < 0 || contentLength <= maxBodySize) {
                        return DataBufferUtils.join(flux, maxBodySize)
                                .flatMap(dataBuffer -> {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    DataBufferUtils.release(dataBuffer);

                                    String responseBody = new String(bytes, StandardCharsets.UTF_8);
                                    buffer.setJsonBody(responseBody);
                                    bufferedChars.addAndGet(responseBody.length());

                                    try {
                                        TokenUsage usage = processJsonUsage(config.tokenQuotaConfig, responseBody, aiResponseFormat(exchange));
                                        addTokenUsageHeaders(exchange.getResponse(), usage, 0L, preDeductedTokens, false);
                                    } catch (Exception e) {
                                        log.debug("Failed to pre-parse token usage for headers: {}", e.getMessage());
                                    }

                                    return super.writeWith(Flux.just(
                                            exchange.getResponse().bufferFactory().wrap(bytes)));
                                })
                                .onErrorResume(DataBufferLimitException.class, e -> {
                                    // Chunked body exceeded maxBodySize mid-stream; the body is
                                    // already consumed, so report the misconfiguration.
                                    log.warn("Response body exceeded max-body-size {} during token usage collection, routeId: {}",
                                            maxBodySize, routeId);
                                    exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.BAD_GATEWAY);
                                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                                    byte[] err = "{\"code\":502,\"message\":\"Response too large for token usage collection\"}"
                                            .getBytes(StandardCharsets.UTF_8);
                                    return super.writeWith(Flux.just(
                                            exchange.getResponse().bufferFactory().wrap(err)));
                                });
                    }
                    // Known oversized body: stream through without usage headers
                    return super.writeWith(flux);
                }

                return super.writeWith(flux.doOnNext(dataBuffer -> {
                    // Read and buffer response chunk
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String chunk = new String(bytes, StandardCharsets.UTF_8);

                    // SSE mode: smart buffering based on usage info
                    for (String event : frames.accept(chunk)) {
                        handleSseChunk(event, buffer, totalTokens, bufferedChars, config.tokenQuotaConfig);
                    }

                    // Reset buffer position for actual write
                    dataBuffer.readPosition(0);
                }));
            }

            @Override
            public Mono<Void> writeAndFlushWith(
                    org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends DataBuffer>> body) {
                // Streaming (SSE) responses are written via writeAndFlushWith, which
                // BYPASSES writeWith - without this override the usage carried in the
                // streamed chunks was never captured. Side-copy each chunk for parsing
                // while preserving flush boundaries (keeps time-to-first-token intact).
                Flux<? extends org.reactivestreams.Publisher<? extends DataBuffer>> groups =
                        Flux.from(body).map(group -> Flux.from(group).doOnNext(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            String chunk = new String(bytes, StandardCharsets.UTF_8);
                            for (String event : frames.accept(chunk)) {
                                handleSseChunk(event, buffer, totalTokens, bufferedChars, config.tokenQuotaConfig);
                            }
                            dataBuffer.readPosition(0);
                        }));
                return super.writeAndFlushWith(groups);
            }
        };

        ServerWebExchange decoratedExchange = exchange.mutate()
                .response(decoratedResponse)
                .build();

        // Continue filter chain and process response in doFinally
        return chain.filter(decoratedExchange)
                .doFinally(signalType -> {
                    // The last event may still be sitting in the accumulator when the
                    // stream ends without a trailing blank line.
                    for (String event : frames.flush()) {
                        handleSseChunk(event, buffer, totalTokens, bufferedChars, config.tokenQuotaConfig);
                    }
                    // By now the upstream Content-Type is known, so this no longer
                    // depends on the client having sent Accept: text/event-stream.
                    boolean isSse = isSseRequest(exchange);
                    // Check for stream interruption
                    boolean interrupted = isStreamInterrupted(signalType, isSse, buffer);

                    // Process collected response body with pre-deduct settlement.
                    // FIX (blocking Redis on EventLoop): doFinally runs on the Netty
                    // EventLoop; settlement performs blocking Redis calls (consume/refund),
                    // so offload it to boundedElastic.
                    Mono.fromRunnable(() -> processTokenUsageWithSettlement(exchange, tenantId,
                                    config.tokenQuotaConfig, buffer, totalTokens.get(),
                                    preDeductedTokens, interrupted, isSse))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                });
    }

    /**
     * Handle SSE chunk with smart buffering.
     * Only buffers chunks that may contain usage info, others are discarded.
     */
    private void handleSseChunk(String chunk, MemoryAwareBuffer buffer,
                                 AtomicInteger totalTokens, AtomicInteger bufferedChars,
                                 TokenQuotaConfig config) {
        // Check memory limit
        int currentSize = bufferedChars.get();
        int maxSize = config.getSseMaxBufferSize();

        if (currentSize >= maxSize) {
            // Buffer full, only parse for usage (don't store)
            log.debug("SSE buffer full ({} bytes), parsing without storing", currentSize);
            parseIncrementalUsage(chunk, totalTokens, config);
            return;
        }

        // Check if chunk might contain usage info
        if (shouldBufferChunk(chunk)) {
            // Check chunk limit
            if (buffer.getSseChunks().size() < config.getSseMaxChunks()) {
                buffer.addSseChunk(chunk);
                bufferedChars.addAndGet(chunk.length());
            } else {
                // Chunk limit reached, only parse
                parseIncrementalUsage(chunk, totalTokens, config);
            }
        }

        // Always try to parse incremental usage
        parseIncrementalUsage(chunk, totalTokens, config);
    }

    /**
     * Check if chunk should be buffered (contains potential usage info).
     */
    boolean shouldBufferChunk(String chunk) {
        // Skip empty chunks and [DONE] markers
        if (chunk == null || chunk.trim().isEmpty() || chunk.contains("[DONE]")) {
            return true;  // Buffer [DONE] for completion detection
        }

        // Usage and finish_reason only carry a value on the terminal chunks. Matching
        // the bare field names would buffer the entire stream, because OpenAI-compatible
        // providers emit "usage":null AND "finish_reason":null on every single chunk.
        if (hasNonNullField(chunk, "usage")) {
            return true;
        }

        // Anthropic native SSE carries usage on message_delta / message_stop events
        if (chunk.contains("message_stop") || chunk.contains("message_delta")) {
            return true;
        }

        if (hasNonNullField(chunk, "finish_reason")) {
            return true;
        }

        return false;  // Skip content-only chunks to save memory
    }

    /**
     * True when {@code "field"} appears with a value that is not JSON null.
     * Whitespace tolerant.
     */
    static boolean hasNonNullField(String chunk, String field) {
        String quoted = "\"" + field + "\"";
        int from = 0;
        while (true) {
            int start = chunk.indexOf(quoted, from);
            if (start < 0) {
                return false;
            }
            int i = skipWhitespace(chunk, start + quoted.length());
            if (i < chunk.length() && chunk.charAt(i) == ':') {
                i = skipWhitespace(chunk, i + 1);
                if (i < chunk.length() && !chunk.startsWith("null", i)) {
                    return true;
                }
            }
            from = start + 1;
        }
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Parse incremental usage from SSE chunk.
     */
    private void parseIncrementalUsage(String chunk, AtomicInteger totalTokens, TokenQuotaConfig config) {
        if (!config.isSseIncrementalTracking()) {
            return;
        }

        TokenUsage chunkUsage = tokenUsageParser.parseSseChunk(chunk);
        if (chunkUsage.hasUsage()) {
            // Track maximum (handles both cumulative and incremental)
            if (chunkUsage.getTotalTokens() > totalTokens.get()) {
                totalTokens.set(chunkUsage.getTotalTokens());
            }
        }
    }

    /**
     * Handle JSON chunk with size limit.
     */
    private void handleJsonChunk(String chunk, MemoryAwareBuffer buffer, AtomicInteger bufferedChars) {
        int currentSize = bufferedChars.get();

        if (currentSize + chunk.length() <= maxBodySize) {
            buffer.appendJsonBody(chunk);
            bufferedChars.addAndGet(chunk.length());
        } else if (currentSize < maxBodySize) {
            int remaining = maxBodySize - currentSize;
            buffer.appendJsonBody(chunk.substring(0, remaining));
            bufferedChars.addAndGet(remaining);
        }
    }

    /**
     * Check if stream was interrupted (cancelled or error).
     */
    private boolean isStreamInterrupted(reactor.core.publisher.SignalType signalType,
                                           boolean isSse, MemoryAwareBuffer buffer) {
        // A stream that delivered its terminal [DONE] marker COMPLETED normally.
        // Clients routinely close the connection right after [DONE], which surfaces
        // as a CANCEL signal - that is a clean finish, NOT an interruption, and must
        // not trigger the min-charge fallback (doing so overbilled every small stream:
        // a 22-token completion was charged the 100-token floor).
        if (isSse && tokenUsageParser.isSseStreamComplete(buffer.getSseChunks())) {
            return false;
        }

        // Signal types indicating interruption (only when the stream did not complete)
        if (signalType == reactor.core.publisher.SignalType.CANCEL) {
            log.warn("Stream cancelled by client before completion");
            return true;
        }

        if (signalType == reactor.core.publisher.SignalType.ON_ERROR) {
            log.warn("Stream error occurred");
            return true;
        }

        // For SSE, no [DONE] marker means the stream ended prematurely
        if (isSse) {
            log.warn("SSE stream incomplete (no [DONE] marker)");
            return true;
        }

        return false;
    }

    /**
     * Process token usage with pre-deduct settlement.
     * Handles stream interruption and refund logic.
     */
    private void processTokenUsageWithSettlement(ServerWebExchange exchange, String tenantId,
                                                  TokenQuotaConfig config, MemoryAwareBuffer buffer,
                                                  int incrementalTokens, long preDeductedTokens,
                                                  boolean interrupted, boolean isSse) {
        try {
            TokenUsage usage;

            if (isSse) {
                // SSE stream processing
                usage = processSseUsage(config, buffer, incrementalTokens, interrupted);
            } else {
                // JSON response processing
                usage = processJsonUsage(config, buffer.getJsonBody(), aiResponseFormat(exchange));
            }

            // Attribute the AI-proxy-resolved model so per-model usage breakdown works.
            Object aiModel = exchange.getAttributes().get(AiProviderRoutingFilter.AI_MODEL_ATTR);
            if (usage != null && aiModel != null) {
                usage.setModel(aiModel.toString());
            }

            long actualTokens = usage != null && usage.hasUsage() ? usage.getTotalTokens() : 0;

            // Handle interrupted stream
            if (interrupted && isSse) {
                actualTokens = handleInterruptedStream(tenantId, actualTokens, incrementalTokens,
                        preDeductedTokens, config);
            }

            // Settlement: refund or consume based on pre-deducted vs actual
            if (preDeductedTokens > 0) {
                tokenQuotaManager.settleTokenUsage(tenantId, preDeductedTokens, actualTokens, config);
            } else if (actualTokens > 0) {
                // No pre-deduct, just consume actual
                tokenQuotaManager.consumeTokens(tenantId, actualTokens, config);
            }

            // FIX (dead headers): X-Token-Usage-* were previously added here, after
            // the response was already committed, so they never reached the client.
            // For JSON responses they are now set before the body is written
            // (see writeWith); for SSE streams headers cannot be added after
            // commit, so the values are only logged.
            if (usage != null && usage.hasUsage()) {
                log.debug("Token usage detail: prompt={}, completion={}, total={}, preDeducted={}, interrupted={}",
                        usage.getPromptTokens(), usage.getCompletionTokens(), actualTokens,
                        preDeductedTokens, interrupted);
            }

            log.info("Token usage settled: tenant={}, preDeducted={}, actual={}, interrupted={}",
                    tenantId, preDeductedTokens, actualTokens, interrupted);

            // 旁路上报 admin 计量库（可观测页数据源）；失败静默，不影响代理与结算
            reportUsageToAdmin(exchange, tenantId, usage, actualTokens);

        } catch (Exception e) {
            log.error("Error processing token usage for tenant {}: {}", tenantId, e.getMessage());

            // On error, refund pre-deducted tokens if configured
            if (preDeductedTokens > 0 && config.isAutoRefundEnabled()) {
                tokenQuotaManager.refundTokens(tenantId, preDeductedTokens, config, "PROCESSING_ERROR");
            }
        }
    }

    /**
     * 把本轮已结算用量上报 admin（字段契约 = admin TokenQuotaService.recordTokenUsage）。
     * 延迟取自 PreCheckFilter 打的起点戳（含上游往返）；起点缺失则不报 latency。
     */
    private void reportUsageToAdmin(ServerWebExchange exchange, String tenantId,
                                    TokenUsage usage, long actualTokens) {
        if (tokenUsageReporter == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenantId", tenantId);
            payload.put("traceId", exchange.getAttribute(TraceIdGlobalFilter.TRACE_ID_ATTR));
            payload.put("routeId", RouteUtils.getRouteId(exchange));
            payload.put("model", usage != null ? usage.getModel() : null);
            payload.put("promptTokens", usage != null ? usage.getPromptTokens() : 0);
            payload.put("completionTokens", usage != null ? usage.getCompletionTokens() : 0);
            payload.put("totalTokens", actualTokens);
            payload.put("responseFormat", aiResponseFormat(exchange));
            payload.put("instanceId", instanceId);
            Long startNs = exchange.getAttribute(TokenQuotaPreCheckFilter.ATTR_START_NS);
            if (startNs != null) {
                payload.put("latencyMs", (System.nanoTime() - startNs) / 1_000_000L);
            }
            payload.put("requestTime", LocalDateTime.now().toString());
            tokenUsageReporter.report(payload);
        } catch (Exception e) {
            log.debug("Failed to build token usage report (ignored): {}", e.getMessage());
        }
    }

    /**
     * Add token usage headers before the response is committed.
     * Must be called BEFORE the body is written, otherwise the headers are
     * silently dropped by the already-committed response.
     */
    private void addTokenUsageHeaders(ServerHttpResponse response, TokenUsage usage,
                                       long actualTokensFallback, long preDeductedTokens, boolean interrupted) {
        if (usage == null || !usage.hasUsage()) {
            return;
        }
        long actualTokens = actualTokensFallback > 0 ? actualTokensFallback : usage.getTotalTokens();
        response.getHeaders().add("X-Token-Usage-Prompt", String.valueOf(usage.getPromptTokens()));
        response.getHeaders().add("X-Token-Usage-Completion", String.valueOf(usage.getCompletionTokens()));
        response.getHeaders().add("X-Token-Usage-Total", String.valueOf(actualTokens));
        response.getHeaders().add("X-Token-Pre-Deducted", String.valueOf(preDeductedTokens));
        if (interrupted) {
            response.getHeaders().add("X-Token-Stream-Interrupted", "true");
        }
    }

    /**
     * Handle interrupted stream and determine actual charge.
     */
    private long handleInterruptedStream(String tenantId, long actualTokens, int incrementalTokens,
                                          long preDeductedTokens, TokenQuotaConfig config) {
        // Determine what to charge for interrupted stream
        long chargeTokens;

        if (config.isSsePartialBillingOnInterrupt()) {
            // Partial billing on a genuinely interrupted stream.
            long minCharge = config.getSseMinChargeOnInterrupt();

            if (actualTokens > 0) {
                // Trust the provider-reported usage; the min-charge floor is only a
                // fallback for when usage is unknown. (Previously max(actual, min)
                // overbilled small interrupted streams.)
                chargeTokens = actualTokens;
            } else if (incrementalTokens > 0) {
                // Use incremental tracking tokens
                chargeTokens = incrementalTokens;
            } else {
                // No token info at all - charge the safety floor.
                chargeTokens = minCharge;
            }

            log.info("Interrupted stream partial billing: tenant={}, charge={}, min={}",
                    tenantId, chargeTokens, minCharge);
        } else {
            // No partial billing: refund all pre-deducted, charge nothing
            chargeTokens = 0;
            log.info("Interrupted stream no billing: tenant={}, refunding all", tenantId);
        }

        return chargeTokens;
    }

    /**
     * Process collected token usage after response is complete.
     * @deprecated Use processTokenUsageWithSettlement instead
     */
    @Deprecated
    private void processTokenUsage(ServerWebExchange exchange, String tenantId,
                                   TokenQuotaConfig config, String responseBody,
                                   List<String> sseChunks, int incrementalTokens) {
        // Legacy method - kept for compatibility
        MemoryAwareBuffer buffer = new MemoryAwareBuffer(config.getSseMaxBufferSize(),
                config.getSseMaxChunks(), maxBodySize);
        buffer.setJsonBody(responseBody);
        buffer.setSseChunks(sseChunks);

        processTokenUsageWithSettlement(exchange, tenantId, config, buffer,
                incrementalTokens, 0L, false, isSseRequest(exchange));
    }

    /**
     * Process SSE stream usage with interruption handling.
     */
    private TokenUsage processSseUsage(TokenQuotaConfig config, MemoryAwareBuffer buffer,
                                        int incrementalTokens, boolean interrupted) {
        List<String> sseChunks = buffer.getSseChunks();

        if (sseChunks.isEmpty()) {
            return TokenUsage.empty();
        }

        // Check if stream is complete (has [DONE] marker)
        boolean isComplete = !interrupted && tokenUsageParser.isSseStreamComplete(sseChunks);

        if (isComplete) {
            // Stream complete, aggregate usage from all chunks
            TokenUsage aggregated = tokenUsageParser.aggregateSseUsage(sseChunks);

            if (aggregated.hasUsage()) {
                log.debug("SSE stream complete, aggregated usage: total={}", aggregated.getTotalTokens());
                return aggregated;
            }
        }

        // Stream incomplete or interrupted, use incremental if available
        if (incrementalTokens > 0) {
            TokenUsage usage = new TokenUsage();
            usage.setTotalTokens(incrementalTokens);
            usage.setFormat("SSE_INCREMENTAL");
            log.debug("Using incremental SSE usage: total={}, interrupted={}", incrementalTokens, interrupted);
            return usage;
        }

        // No usage found - this may happen for very short or interrupted streams
        if (interrupted) {
            log.warn("SSE stream interrupted with no usage info for tenant");
        } else {
            log.warn("SSE stream ended but no usage information found");
        }

        return TokenUsage.empty();
    }

    /**
     * Process JSON response usage.
     *
     * @param formatOverride AI-proxy-resolved response format (set by
     *                       AiProviderRoutingFilter). When present it wins over the
     *                       strategy's static responseFormat so per-provider formats work.
     */
    private TokenUsage processJsonUsage(TokenQuotaConfig config, String responseBody, String formatOverride) {
        if (responseBody == null || responseBody.isEmpty()) {
            return TokenUsage.empty();
        }

        String format = (formatOverride != null && !formatOverride.isBlank())
                ? formatOverride
                : config.getResponseFormat();
        Map<String, String> fieldMapping = config.getCustomFieldMapping();

        return tokenUsageParser.parseJsonResponse(responseBody, format, fieldMapping);
    }

    /**
     * Response format resolved by AiProviderRoutingFilter, if this is an AI-proxy route.
     */
    private String aiResponseFormat(ServerWebExchange exchange) {
        Object value = exchange.getAttributes().get(AiProviderRoutingFilter.AI_RESPONSE_FORMAT_ATTR);
        return value != null ? value.toString() : null;
    }

    /**
     * Get configuration from StrategyManager.
     */
    private ConfigWrapper getConfig(String routeId) {
        ConfigWrapper wrapper = new ConfigWrapper();

        try {
            // Look for TOKEN_RATE_LIMITER strategy type
            StrategyDefinition strategy = strategyManager.getStrategyForRoute(routeId, "TOKEN_RATE_LIMITER");

            if (strategy != null && strategy.isEnabled()) {
                Map<String, Object> configMap = strategy.getConfig();
                if (configMap != null && !configMap.isEmpty()) {
                    // FIX (performance): conversion result is cached per strategy
                    // version inside StrategyManager instead of converting per request.
                    TokenQuotaConfig tokenQuotaConfig = strategyManager.convertStrategyConfig(
                            routeId, "TOKEN_RATE_LIMITER", strategy, TokenQuotaConfig.class);
                    if (tokenQuotaConfig != null && tokenQuotaConfig.isEnabled()) {
                        wrapper.enabled = true;
                        wrapper.tokenQuotaConfig = tokenQuotaConfig;
                        return wrapper;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get token quota config for route {}: {}", routeId, e.getMessage());
        }

        // Try global strategy
        try {
            List<StrategyDefinition> globalStrategies = strategyManager.getGlobalStrategiesByType("TOKEN_RATE_LIMITER");
            if (globalStrategies != null && !globalStrategies.isEmpty()) {
                StrategyDefinition globalStrategy = globalStrategies.get(0);
                if (globalStrategy.isEnabled()) {
                    Map<String, Object> configMap = globalStrategy.getConfig();
                    if (configMap != null && !configMap.isEmpty()) {
                        // Separate cache namespace (global: prefix) so the global
                        // entry does not flap with the route-bound one.
                        TokenQuotaConfig tokenQuotaConfig = strategyManager.convertStrategyConfig(
                                "global:" + globalStrategy.getStrategyId(), "TOKEN_RATE_LIMITER",
                                globalStrategy, TokenQuotaConfig.class);
                        if (tokenQuotaConfig != null && tokenQuotaConfig.isEnabled()) {
                            wrapper.enabled = true;
                            wrapper.tokenQuotaConfig = tokenQuotaConfig;
                            return wrapper;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("No global token quota config: {}", e.getMessage());
        }

        wrapper.enabled = false;
        return wrapper;
    }

    /**
     * Check if request expects SSE response.
     */
    private boolean isSseRequest(ServerWebExchange exchange) {
        String accept = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }

        // Also check response content type
        ServerHttpResponse response = exchange.getResponse();
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && contentType.includes(MediaType.TEXT_EVENT_STREAM)) {
            return true;
        }

        return false;
    }

    @Override
    public int getOrder() {
        // Must be < NettyWriteResponseFilter (-1) so this filter's response decorator
        // is actually in the client-write path (see FilterOrderConstants).
        return FilterOrderConstants.TOKEN_USAGE_COLLECTOR;
    }

    // ============== Inner Classes ==============

    @Data
    private static class ConfigWrapper {
        private boolean enabled = false;
        private TokenQuotaConfig tokenQuotaConfig = new TokenQuotaConfig();
    }

    /**
     * Reassembles SSE event frames across DataBuffer boundaries.
     * <p>
     * One Netty read can carry several events, and one event can be split across two
     * reads - including the terminal {@code data: [DONE]}. Parsing per buffer made the
     * usage block vanish whenever the boundaries did not line up, which settled the
     * request at 0 tokens. Events are separated by a blank line; CR is dropped so CRLF,
     * LF and a CRLF split across two buffers all normalise to the same boundary.
     */
    private static final class SseFrameAccumulator {

        private static final String BOUNDARY = "\n\n";

        private final StringBuilder carry = new StringBuilder();

        /** Complete events contained in this chunk, in wire order. */
        List<String> accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return List.of();
            }
            carry.append(chunk.indexOf('\r') >= 0 ? chunk.replace("\r", "") : chunk);

            List<String> events = new ArrayList<>();
            int from = 0;
            int boundary;
            while ((boundary = carry.indexOf(BOUNDARY, from)) >= 0) {
                String event = carry.substring(from, boundary);
                if (!event.isEmpty()) {
                    events.add(event);
                }
                from = boundary + BOUNDARY.length();
            }
            if (from > 0) {
                carry.delete(0, from);
            }
            return events;
        }

        /** Trailing event that never received its blank line, emitted at end of stream. */
        List<String> flush() {
            if (carry.length() == 0) {
                return List.of();
            }
            String rest = carry.toString();
            carry.setLength(0);
            return rest.isBlank() ? List.of() : List.of(rest);
        }
    }

    /**
     * Memory-aware buffer for response body capture.
     * Limits memory usage for long SSE streams.
     */
    @Data
    private static class MemoryAwareBuffer {
        private final int maxBufferSize;      // Max total bytes to buffer
        private final int maxChunks;          // Max chunks to store
        private final int maxJsonSize;        // Max size for JSON response

        private StringBuilder jsonBody = new StringBuilder();
        private List<String> sseChunks = new ArrayList<>();
        private int totalBufferedBytes = 0;

        public MemoryAwareBuffer(int maxBufferSize, int maxChunks, int maxJsonSize) {
            this.maxBufferSize = maxBufferSize;
            this.maxChunks = maxChunks;
            this.maxJsonSize = maxJsonSize;
        }

        public String getJsonBody() {
            return jsonBody.toString();
        }

        public void setJsonBody(String body) {
            // FIX (O(n^2) concatenation): jsonBody was a String appended with +=
            // per chunk, copying the whole buffer on every append.
            jsonBody = new StringBuilder(body != null ? body : "");
            totalBufferedBytes += jsonBody.length();
        }

        public void appendJsonBody(String chunk) {
            if (totalBufferedBytes + chunk.length() <= maxJsonSize) {
                jsonBody.append(chunk);
                totalBufferedBytes += chunk.length();
            }
        }

        public void addSseChunk(String chunk) {
            if (sseChunks.size() < maxChunks && totalBufferedBytes + chunk.length() <= maxBufferSize) {
                sseChunks.add(chunk);
                totalBufferedBytes += chunk.length();
            }
        }

        public boolean isBufferFull() {
            return totalBufferedBytes >= maxBufferSize || sseChunks.size() >= maxChunks;
        }
    }
}