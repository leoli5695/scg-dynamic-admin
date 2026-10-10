package com.leoli.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Fetches decrypted provider keys from the admin console's internal endpoint
 * ({@code GET /api/provider-credentials/internal/{ref}/key}) using the shared
 * X-Internal-Token, with a short-lived local cache so the hot path rarely blocks
 * on an admin round-trip.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AdminProviderKeyResolver implements ProviderKeyResolver {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final WebClient webClient;

    @Value("${gateway.admin.url:http://127.0.0.1:9090}")
    private String adminUrl;

    @Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}")
    private String internalToken;

    @Value("${gateway.ai.key-cache-ttl-seconds:300}")
    private long keyCacheTtlSeconds;

    private final Cache<String, String> keyCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(300))
            .build();

    public AdminProviderKeyResolver(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<String> resolveKey(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return Mono.empty();
        }
        String cached = keyCache.getIfPresent(credentialRef);
        if (cached != null) {
            return Mono.just(cached);
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("gateway.admin.internal-token not configured; cannot fetch provider key for ref {}", credentialRef);
            return Mono.empty();
        }

        String url = adminUrl + "/api/provider-credentials/internal/" + credentialRef + "/key";
        return webClient.get()
                .uri(url)
                .header(TOKEN_HEADER, internalToken)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .mapNotNull(node -> node.path("data").path("apiKey").asText(null))
                .filter(key -> !key.isBlank())
                .doOnNext(key -> keyCache.put(credentialRef, key))
                .onErrorResume(e -> {
                    log.warn("Failed to fetch provider key for ref {}: {}", credentialRef, e.getMessage());
                    return Mono.empty();
                });
    }

    /** Drop a cached key (e.g. after rotation). */
    public void invalidate(String credentialRef) {
        keyCache.invalidate(credentialRef);
    }
}
