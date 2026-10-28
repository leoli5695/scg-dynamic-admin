package com.leoli.gateway.ai.security;

import com.leoli.gateway.model.AiSecurityConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Locale;

/** Reactive HTTP executor shared by Java adapters. Never logs credentials or audited text. */
@Component
public class ExternalContentModerator {
    private final WebClient client;
    private final Map<String, ModerationAdapter> adapters = new LinkedHashMap<>();

    public ExternalContentModerator(WebClient.Builder builder, List<ModerationAdapter> builtins) {
        this.client = builder.clone().codecs(c -> c.defaultCodecs().maxInMemorySize(65536)).build();
        builtins.forEach(this::register);
        ServiceLoader.load(ModerationAdapter.class).forEach(this::register);
    }

    private void register(ModerationAdapter adapter) {
        String id = adapter.id().toUpperCase(Locale.ROOT);
        if (!id.matches("[A-Z0-9_]{1,64}") || adapters.putIfAbsent(id, adapter) != null) {
            throw new IllegalArgumentException("Invalid or duplicate moderation adapter: " + id);
        }
    }

    public Mono<ContentModerator.ModerationResult> moderate(String text, String direction, AiSecurityConfig config) {
        return Mono.fromCallable(() -> {
            ModerationAdapter adapter = adapters.get(config.getExternalAdapter().toUpperCase(Locale.ROOT));
            if (adapter == null) throw new IllegalArgumentException("Unknown moderation adapter");
            URI endpoint = URI.create(config.getExternalUrl());
            Map<String, String> headers = new LinkedHashMap<>();
            if (config.getExternalHeaders() != null) config.getExternalHeaders().forEach((key, value) -> {
                // Whole environment references keep secrets out of the stored policy.
                if (value != null && value.startsWith("${") && value.endsWith("}")) {
                    value = System.getenv(value.substring(2, value.length() - 1));
                    if (value == null) throw new IllegalArgumentException("Missing moderation credential");
                }
                headers.put(key, value);
            });
            Map<String, Object> options = new LinkedHashMap<>();
            if (config.getExternalOptions() != null) config.getExternalOptions().forEach((key, value) -> {
                if (value instanceof String s && s.startsWith("${") && s.endsWith("}")) {
                    value = System.getenv(s.substring(2, s.length() - 1));
                    if (value == null) throw new IllegalArgumentException("Missing moderation option credential");
                }
                options.put(key, value);
            });
            ModerationAdapter.Request request = adapter.prepare(new ModerationAdapter.Context(
                    text, direction, endpoint, Map.copyOf(headers), Map.copyOf(options)));
            URI uri = request.endpoint();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid moderation endpoint");
            }
            return new Prepared(adapter, request);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(prepared -> {
            var request = prepared.request();
            return client.method(org.springframework.http.HttpMethod.valueOf(request.method())).uri(request.endpoint()).headers(headers -> {
                request.headers().forEach(headers::set);
            }).bodyValue(request.body()).exchangeToMono(response -> response.bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0]).map(bytes -> new ModerationAdapter.Response(
                            response.statusCode().value(), response.headers().asHttpHeaders(), bytes)))
                    .publishOn(Schedulers.boundedElastic()).map(response -> {
                        ModerationAdapter.Decision decision;
                        try { decision = prepared.adapter().parse(response); }
                        catch (Exception error) { throw new IllegalStateException("Moderation response parsing failed", error); }
                        if (decision == null) throw new IllegalStateException("Missing moderation decision");
                        var result = ContentModerator.ModerationResult.clean(text);
                        result.setFlagged(decision.flagged());
                        if (decision.flagged()) {
                            result.getCategories().add("EXTERNAL_CONTENT");
                            if (decision.categories() != null) decision.categories().stream().limit(8)
                                    .filter(c -> c != null && c.matches("[A-Za-z0-9_:-]{1,48}"))
                                    .forEach(c -> result.getCategories().add("EXT:" + c));
                        }
                        return result;
                    });
        }).timeout(Duration.ofMillis(Math.max(100, Math.min(config.getExternalTimeoutMs(), 30000))));
    }

    private record Prepared(ModerationAdapter adapter, ModerationAdapter.Request request) {}
}
