package com.leoli.gateway.filter.security;

import com.leoli.gateway.constants.InternalApiConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * FIX (internal endpoints unauthenticated): inbound authentication for /internal/** endpoints.
 * <p>
 * Previously the internal endpoints (filter-chain statistics, circuit breaker control,
 * optimization recommendations) were exposed on the main gateway port WITHOUT any
 * authentication - X-Internal-Token was only attached to OUTBOUND gateway-to-admin requests.
 * Any client able to reach the port could clear monitoring data, change thresholds or
 * force-open circuit breakers (control-plane DoS without credentials).
 * <p>
 * SECURITY FIX (unprotected internal endpoints): previously an empty token left
 * /internal/** open to forged reports with only a startup warning. The policy now
 * mirrors the JWT secret posture - startup fails when the token is not configured,
 * so the endpoints can never run unprotected. Requests without a matching token are
 * rejected with 401 (constant-time compare).
 *
 * @author leoli
 */
@Slf4j
@Component
public class InternalApiAuthWebFilter implements WebFilter, Ordered, InitializingBean {

    public static final String TOKEN_HEADER = InternalApiConstants.TOKEN_HEADER;

    private static final String PROTECTED_PREFIX = "/internal/";

    @Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}")
    private String internalToken;

    /**
     * Fail fast when the shared secret is not configured - same posture as the
     * JWT secret. Running with unprotected /internal/** endpoints would let any
     * client forge monitoring reports or control-plane calls.
     */
    @Override
    public void afterPropertiesSet() {
        if (internalToken == null || internalToken.isEmpty()) {
            throw new IllegalStateException(
                    "gateway.admin.internal-token is not configured - refusing to start with "
                            + "UNPROTECTED /internal/** endpoints. Set GATEWAY_INTERNAL_TOKEN "
                            + "(must match gateway.admin.security.internal-token on the admin console). "
                            + "Generate with: openssl rand -base64 32");
        }
        log.info("Internal API token configured - /internal/** endpoints are protected");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path == null || !path.startsWith(PROTECTED_PREFIX)) {
            return chain.filter(exchange);
        }

        String provided = exchange.getRequest().getHeaders().getFirst(TOKEN_HEADER);
        if (provided != null && MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            return chain.filter(exchange);
        }

        log.warn("Rejected internal API request without valid {} header: {} {}",
                TOKEN_HEADER, exchange.getRequest().getMethod(), path);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json");
        String body = "{\"success\":false,\"message\":\"Missing or invalid internal token\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        // Run before all gateway filters so rejected internal requests never enter the pipeline
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
