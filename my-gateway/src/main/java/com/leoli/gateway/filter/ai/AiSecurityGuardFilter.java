package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.leoli.gateway.ai.security.AiContentInspector;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiSecurityConfig;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RequestBodyCache;
import com.leoli.gateway.util.RouteUtils;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Request/response text guard. External I/O is reactive; SSE BUFFER explicitly delays delivery. */
@Component
public class AiSecurityGuardFilter implements GlobalFilter, Ordered {
    public static final String REQUEST_VERDICT_HEADER = "X-AI-Security-Request";
    public static final String RESPONSE_VERDICT_HEADER = "X-AI-Security-Response";
    private final StrategyManager strategies;
    private final ObjectMapper mapper;
    private final AiContentInspector inspector;

    public AiSecurityGuardFilter(StrategyManager strategies, ObjectMapper mapper, AiContentInspector inspector) {
        this.strategies = strategies;
        this.mapper = mapper;
        this.inspector = inspector;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        AiSecurityConfig config = strategies.getConfig(RouteUtils.getRouteId(exchange),
                StrategyDefinition.TYPE_AI_SECURITY, AiSecurityConfig.class);
        if (config == null || !config.isEnabled()) return chain.filter(exchange);
        ServerWebExchange guarded = config.isScanResponse()
                ? exchange.mutate().response(guardResponse(exchange, config)).build() : exchange;
        if (!config.isScanRequest() || exchange.getRequest().getMethod() != HttpMethod.POST) return chain.filter(guarded);
        return RequestBodyCache.read(exchange, bodyLimit(config))
                .onErrorResume(DataBufferLimitException.class, error -> failure(exchange.getResponse(),
                        HttpStatus.PAYLOAD_TOO_LARGE, "内容超过审核大小限制", "BODY_TOO_LARGE").then(Mono.empty()))
                .flatMap(bytes -> {
            if (bytes.length == 0) return chain.filter(guarded);
            JsonNode root;
            try { root = mapper.readTree(bytes); }
            catch (Exception error) {
                return failure(exchange.getResponse(), HttpStatus.BAD_REQUEST, "请求不是可审核的 JSON", "INVALID_JSON");
            }
            return inspector.inspect(root, "REQUEST", config, false).flatMap(result -> {
                verdict(exchange.getResponse(), REQUEST_VERDICT_HEADER, result);
                if (result.blocked()) return failure(exchange.getResponse(),
                        result.externalFailed() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN,
                        result.externalFailed() ? "内容审核服务不可用" : config.getBlockMessage(),
                        result.externalFailed() ? "MODERATION_UNAVAILABLE" : "CONTENT_BLOCKED");
                try {
                    byte[] output = mapper.writeValueAsBytes(result.body());
                    return chain.filter(guarded.mutate().request(RequestBodyCache.wrap(exchange, output)).build());
                } catch (Exception error) { return Mono.error(error); }
            });
        });
    }

    private ServerHttpResponse guardResponse(ServerWebExchange exchange, AiSecurityConfig config) {
        return new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                MediaType type = getHeaders().getContentType();
                boolean stream = type != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type);
                boolean json = type != null && (type.getSubtype().equals("json") || type.getSubtype().endsWith("+json"));
                boolean text = type != null && "text".equals(type.getType());
                if (!json && !text) {
                    getHeaders().set(RESPONSE_VERDICT_HEADER, "UNSUPPORTED_MEDIA_TYPE");
                    return super.writeWith(body);
                }
                if (stream && "SKIP".equalsIgnoreCase(config.getStreamingPolicy())) {
                    getHeaders().set(RESPONSE_VERDICT_HEADER, "STREAM_SKIPPED");
                    return super.writeWith(body);
                }
                if (stream && "BLOCK".equalsIgnoreCase(config.getStreamingPolicy())) {
                    return discard(body).then(failure(getDelegate(), HttpStatus.BAD_GATEWAY,
                            "当前内容安全策略不允许流式响应", "STREAM_BLOCKED"));
                }
                // Reject compressed payloads rather than accidentally scanning compressed bytes as text.
                String encoding = getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    return discard(body).then(failure(getDelegate(), HttpStatus.BAD_GATEWAY,
                            "内容审核不支持压缩响应，请禁用上游压缩", "ENCODED_RESPONSE"));
                }
                return DataBufferUtils.join(Flux.from(body), bodyLimit(config))
                        .timeout(Duration.ofMillis(Math.max(1000, Math.min(config.getResponseTimeoutMs(), 300000))))
                        .flatMap(buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            DataBufferUtils.release(buffer);
                            return audit(bytes, stream, json);
                        })
                        .onErrorResume(error -> getDelegate().isCommitted() ? Mono.error(error)
                                : failure(getDelegate(), HttpStatus.BAD_GATEWAY,
                                    "响应内容审核失败或超过限制", "RESPONSE_AUDIT_FAILED"));
            }

            private Mono<Void> audit(byte[] bytes, boolean stream, boolean json) {
                try {
                    String original = new String(bytes, StandardCharsets.UTF_8);
                    SseDocument sse = stream ? parseSse(original) : null;
                    JsonNode root = stream ? sse.roots : json ? mapper.readTree(bytes) : mapper.createObjectNode().put("text", original);
                    return inspector.inspect(root, "RESPONSE", config, stream).flatMap(result -> {
                        verdict(getDelegate(), RESPONSE_VERDICT_HEADER, result);
                        if (result.blocked()) return failure(getDelegate(), HttpStatus.BAD_GATEWAY,
                                result.externalFailed() ? "内容审核服务不可用" : config.getBlockMessage(),
                                result.externalFailed() ? "MODERATION_UNAVAILABLE" : "CONTENT_BLOCKED");
                        try {
                            byte[] output = stream ? sse.render().getBytes(StandardCharsets.UTF_8)
                                    : json ? mapper.writeValueAsBytes(result.body())
                                    : result.body().path("text").asText().getBytes(StandardCharsets.UTF_8);
                            clearBodyHeaders(getHeaders());
                            getHeaders().setContentLength(output.length);
                            if (stream) getHeaders().set(RESPONSE_VERDICT_HEADER,
                                    result.categories().isEmpty() ? "BUFFERED_CLEAN" : String.join(",", result.categories()));
                            return getDelegate().writeWith(Mono.just(bufferFactory().wrap(output)));
                        } catch (Exception error) { return Mono.error(error); }
                    });
                } catch (Exception error) { return Mono.error(error); }
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                // Explicit SKIP must preserve real-time flushing.
                MediaType type = getHeaders().getContentType();
                if (type != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)
                        && "SKIP".equalsIgnoreCase(config.getStreamingPolicy())) {
                    getHeaders().set(RESPONSE_VERDICT_HEADER, "STREAM_SKIPPED");
                    return getDelegate().writeAndFlushWith(body);
                }
                return writeWith(Flux.from(body).concatMap(publisher -> publisher));
            }
        };
    }

    private int bodyLimit(AiSecurityConfig config) {
        return (int) Math.max(1024, Math.min(config.getMaxBodySize(), 10485760));
    }

    private void verdict(ServerHttpResponse response, String header, AiContentInspector.Result result) {
        if (!result.categories().isEmpty()) response.getHeaders().set(header, String.join(",", result.categories()));
    }

    private Mono<Void> failure(ServerHttpResponse response, HttpStatus status, String message, String code) {
        response.setStatusCode(status);
        clearBodyHeaders(response.getHeaders());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = mapper.writeValueAsBytes(Map.of("httpStatus", status.value(), "code", code, "message", message));
            response.getHeaders().setContentLength(bytes.length);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (Exception error) { return Mono.error(error); }
    }

    private void clearBodyHeaders(HttpHeaders headers) {
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.remove(HttpHeaders.CONTENT_ENCODING);
        headers.remove(HttpHeaders.ETAG);
        headers.remove("Content-MD5");
        headers.remove("Digest");
    }

    private Mono<Void> discard(Publisher<? extends DataBuffer> body) {
        return Mono.create(sink -> Flux.from(body).doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .subscribe(new reactor.core.publisher.BaseSubscriber<DataBuffer>() {
                    @Override protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
                        cancel();
                        sink.success();
                    }
                    @Override protected void hookOnNext(DataBuffer buffer) { DataBufferUtils.release(buffer); }
                    @Override protected void hookOnError(Throwable error) { sink.error(error); }
                }));
    }

    private SseDocument parseSse(String text) throws Exception {
        SseDocument document = new SseDocument();
        for (String frame : text.replace("\r\n", "\n").split("\n\n")) {
            if (frame.isBlank()) continue;
            List<String> prefix = new ArrayList<>();
            List<String> data = new ArrayList<>();
            for (String line : frame.split("\n")) {
                if (line.startsWith("data:")) data.add(line.substring(5).stripLeading());
                else prefix.add(line);
            }
            String payload = String.join("\n", data);
            if (payload.isBlank() || "[DONE]".equals(payload)) document.frames.add(frame + "\n\n");
            else {
                JsonNode node = mapper.readTree(payload);
                document.roots.add(node);
                document.frames.add(new SseFrame(String.join("\n", prefix), node));
            }
        }
        return document;
    }

    private record SseFrame(String prefix, JsonNode body) {}
    private class SseDocument {
        final ArrayNode roots = mapper.createArrayNode();
        final List<Object> frames = new ArrayList<>();
        String render() throws Exception {
            StringBuilder output = new StringBuilder();
            for (Object frame : frames) {
                if (frame instanceof SseFrame data) {
                    if (!data.prefix().isEmpty()) output.append(data.prefix()).append('\n');
                    output.append("data: ").append(mapper.writeValueAsString(data.body())).append("\n\n");
                } else output.append(frame);
            }
            return output.toString();
        }
    }

    @Override public int getOrder() { return FilterOrderConstants.AI_SECURITY; }
}
