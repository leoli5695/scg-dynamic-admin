package com.leoli.gateway.admin.filter;

import java.util.List;

/**
 * Single source of truth for the admin's no-JWT endpoints.
 *
 * <p>These paths are reachable without a JWT because their callers are gateway pods,
 * trace-starter services or Prometheus - machine clients that authenticate with the shared
 * {@code X-Internal-Token} instead of a user JWT. Two places used to hardcode this list
 * independently ({@code SecurityConfig}'s {@code permitAll} matchers and
 * {@link InternalApiTokenFilter}'s {@code PROTECTED_PATTERNS}); they drifted, and
 * {@code /api/prometheus/sd/**} ended up {@code permitAll} with NO token guard, leaking
 * internal exporter/middleware host:port topology to anyone who could reach the admin port.
 *
 * <p>Both sides now read THIS class, so the two sets cannot diverge: adding a path to
 * {@link #TOKEN_PROTECTED} opens it past JWT <em>and</em> guards it with the token in one
 * edit. {@code InternalApiEndpointsContractTest} asserts the token filter actually guards
 * every entry, so a future edit that stops wiring the constant fails the build.
 *
 * @author leoli
 */
public final class InternalApiEndpoints {

    /**
     * permitAll (no JWT) <b>and</b> guarded by {@code X-Internal-Token}. Ant patterns.
     * Callers must send the shared internal token or get 401.
     */
    public static final List<String> TOKEN_PROTECTED = List.of(
            // Gateway instance heartbeat (called by gateway pods).
            "/api/instances/heartbeat",
            "/api/instances/started",
            // Gateway health status sync (called by gateway pods).
            "/api/gateway/health/**",
            // Gateway trace / filter-execution upload (called by gateway pods).
            "/api/traces/internal",
            "/api/traces/internal/batch",
            "/api/filter-executions/internal",
            // gateway-trace-starter upload (called by services embedding the starter).
            "/api/services/traces",
            "/api/services/traces/batch",
            "/api/services/middleware-metadata",
            // Gateway pods fetch decrypted provider API keys here (AI proxy); a leaked pod
            // token is the only way to reach plaintext keys.
            "/api/provider-credentials/internal/**",
            // Gateway pods report settled token usage here (metering/observability page).
            "/api/token-quota/internal/**",
            // Prometheus http_sd_configs scrapes these for exporter targets. Prometheus must
            // be configured to send X-Internal-Token (see PrometheusServiceDiscoveryController
            // javadoc); without the guard the exporter/middleware host:port + service instance
            // topology is readable by anyone who can reach the admin port.
            "/api/prometheus/sd/**"
    );

    /**
     * permitAll and intentionally <b>not</b> token-guarded. Each is a conscious public
     * surface; anything added here widens what an unauthenticated caller can read, so it is
     * reviewed via the contract test.
     */
    public static final List<String> PERMIT_ALL_NO_TOKEN = List.of(
            // Public login; no self-service register handler is whitelisted on purpose.
            "/api/auth/login",
            // Liveness/readiness probe.
            "/actuator/health",
            // Read by the trace UI (features/trace) with a normal JWT. Guarding it with the
            // internal token made every visit to the trace page 401, which the frontend http
            // interceptor treated as an expired session and force-logged the user out.
            "/api/traces/config",
            // Swagger / OpenAPI documentation (optional - can be restricted in production).
            "/swagger-ui/**",
            "/v3/api-docs/**"
    );

    private InternalApiEndpoints() {
    }

    /** {@link #TOKEN_PROTECTED} as an array for {@code requestMatchers(String...)}. */
    public static String[] tokenProtected() {
        return TOKEN_PROTECTED.toArray(new String[0]);
    }

    /** {@link #PERMIT_ALL_NO_TOKEN} as an array for {@code requestMatchers(String...)}. */
    public static String[] permitAllNoToken() {
        return PERMIT_ALL_NO_TOKEN.toArray(new String[0]);
    }
}
