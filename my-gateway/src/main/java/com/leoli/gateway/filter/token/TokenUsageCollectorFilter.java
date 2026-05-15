package com.leoli.gateway.filter.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token Usage Collector Filter.
 *
 * Collects token usage AFTER response is received.
 * Parses usage field from response body and updates Redis counter.
 *
 * Execution Order: TRACE_CAPTURE + 1 (101)
 * - After TraceCaptureGlobalFilter to reuse response body capture if available
 * - In post-phase after response is complete
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

        // Detect response type from Accept header or route config
        boolean isSse = isSseRequest(exchange);

        // Memory-controlled buffer for response capture
        MemoryAwareBuffer buffer = new MemoryAwareBuffer(
                config.tokenQuotaConfig.getSseMaxBufferSize(),
                config.tokenQuotaConfig.getSseMaxChunks(),
                maxBodySize
        );

        // Track stream state
        AtomicInteger totalTokens = new AtomicInteger(0);
        AtomicInteger bufferedChars = new AtomicInteger(0);

        // Wrap response to intercept body
        ServerHttpResponse decoratedResponse = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                Flux<? extends DataBuffer> flux = Flux.from(body);

                return super.writeWith(flux.doOnNext(dataBuffer -> {
                    // Read and buffer response chunk
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String chunk = new String(bytes, StandardCharsets.UTF_8);

                    if (isSse) {
                        // SSE mode: smart buffering based on usage info
                        handleSseChunk(chunk, buffer, totalTokens, bufferedChars, config.tokenQuotaConfig);
                    } else {
                        // JSON mode: collect complete body with size limit
                        handleJsonChunk(chunk, buffer, bufferedChars);
                    }

                    // Reset buffer position for actual write
                    dataBuffer.readPosition(0);
                }));
            }
        };

        ServerWebExchange decoratedExchange = exchange.mutate()
                .response(decoratedResponse)
                .build();

        // Continue filter chain and process response in doFinally
        return chain.filter(decoratedExchange)
                .doFinally(signalType -> {
                    // Check for stream interruption
                    boolean interrupted = isStreamInterrupted(signalType, isSse, buffer);

                    // Process collected response body with pre-deduct settlement
                    processTokenUsageWithSettlement(exchange, tenantId, config.tokenQuotaConfig,
                            buffer, totalTokens.get(), preDeductedTokens, interrupted, isSse);
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
    private boolean shouldBufferChunk(String chunk) {
        // Skip empty chunks and [DONE] markers
        if (chunk == null || chunk.trim().isEmpty() || chunk.contains("[DONE]")) {
            return true;  // Buffer [DONE] for completion detection
        }

        // Check for usage-related fields
        if (chunk.contains("usage") || chunk.contains("tokens") ||
            chunk.contains("message_stop") || chunk.contains("message_delta")) {
            return true;
        }

        // For OpenAI format, last chunk usually contains usage
        // Buffer chunks near end (contain finish_reason)
        if (chunk.contains("finish_reason")) {
            return true;
        }

        return false;  // Skip content-only chunks to save memory
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
        // Signal types indicating interruption
        if (signalType == reactor.core.publisher.SignalType.CANCEL) {
            log.warn("Stream cancelled by client");
            return true;
        }

        if (signalType == reactor.core.publisher.SignalType.ON_ERROR) {
            log.warn("Stream error occurred");
            return true;
        }

        // For SSE, check if [DONE] marker received
        if (isSse && !tokenUsageParser.isSseStreamComplete(buffer.getSseChunks())) {
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
                usage = processJsonUsage(config, buffer.getJsonBody());
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

            // Add usage to response headers for debugging
            if (usage != null && usage.hasUsage()) {
                exchange.getResponse().getHeaders().add("X-Token-Usage-Prompt", String.valueOf(usage.getPromptTokens()));
                exchange.getResponse().getHeaders().add("X-Token-Usage-Completion", String.valueOf(usage.getCompletionTokens()));
                exchange.getResponse().getHeaders().add("X-Token-Usage-Total", String.valueOf(actualTokens));
                exchange.getResponse().getHeaders().add("X-Token-Pre-Deducted", String.valueOf(preDeductedTokens));
                if (interrupted) {
                    exchange.getResponse().getHeaders().add("X-Token-Stream-Interrupted", "true");
                }
            }

            log.info("Token usage settled: tenant={}, preDeducted={}, actual={}, interrupted={}",
                    tenantId, preDeductedTokens, actualTokens, interrupted);

        } catch (Exception e) {
            log.error("Error processing token usage for tenant {}: {}", tenantId, e.getMessage());

            // On error, refund pre-deducted tokens if configured
            if (preDeductedTokens > 0 && config.isAutoRefundEnabled()) {
                tokenQuotaManager.refundTokens(tenantId, preDeductedTokens, config, "PROCESSING_ERROR");
            }
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
            // Partial billing: charge minimum or actual/incremental
            long minCharge = config.getSseMinChargeOnInterrupt();

            if (actualTokens > 0) {
                // Use actual parsed tokens
                chargeTokens = Math.max(actualTokens, minCharge);
            } else if (incrementalTokens > 0) {
                // Use incremental tracking tokens
                chargeTokens = Math.max(incrementalTokens, minCharge);
            } else {
                // No token info, charge minimum
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
     */
    private TokenUsage processJsonUsage(TokenQuotaConfig config, String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return TokenUsage.empty();
        }

        String format = config.getResponseFormat();
        Map<String, String> fieldMapping = config.getCustomFieldMapping();

        return tokenUsageParser.parseJsonResponse(responseBody, format, fieldMapping);
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
                    TokenQuotaConfig tokenQuotaConfig = objectMapper.convertValue(configMap, TokenQuotaConfig.class);
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
                        TokenQuotaConfig tokenQuotaConfig = objectMapper.convertValue(configMap, TokenQuotaConfig.class);
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
        // After TraceCaptureGlobalFilter (100)
        return FilterOrderConstants.TOKEN_USAGE_COLLECTOR;
    }

    // ============== Inner Classes ==============

    @Data
    private static class ConfigWrapper {
        private boolean enabled = false;
        private TokenQuotaConfig tokenQuotaConfig = new TokenQuotaConfig();
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

        private String jsonBody = "";
        private List<String> sseChunks = new ArrayList<>();
        private int totalBufferedBytes = 0;

        public MemoryAwareBuffer(int maxBufferSize, int maxChunks, int maxJsonSize) {
            this.maxBufferSize = maxBufferSize;
            this.maxChunks = maxChunks;
            this.maxJsonSize = maxJsonSize;
        }

        public void appendJsonBody(String chunk) {
            if (totalBufferedBytes + chunk.length() <= maxJsonSize) {
                jsonBody += chunk;
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