package com.leoli.gateway.filter.security;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CORS handling: which origins get an {@code Access-Control-Allow-Origin}, how preflights are
 * answered, and that a rejected preflight cannot be used to write arbitrary response content.
 *
 * <p>This filter had no tests. It is a security surface in the ordinary sense - a wrong answer here
 * either opens an origin that should be closed or silently breaks a legitimate one - and the
 * rejection path builds a JSON body out of a caller-controlled header.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CorsGlobalFilterTest {

    private static final String ALLOWED = "https://app.example.com";

    @Mock
    private StrategyManager strategyManager;
    @Mock
    private GatewayFilterChain chain;

    private CorsGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorsGlobalFilter();
        ReflectionTestUtils.setField(filter, "strategyManager", strategyManager);
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private Map<String, Object> config(Object... keyValuePairs) {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            config.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return config;
    }

    private void givenConfig(Map<String, Object> config) {
        when(strategyManager.getCorsConfig(any())).thenReturn(config);
    }

    private MockServerWebExchange simpleGet(String origin) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/orders");
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private MockServerWebExchange preflight(String origin, String requestHeaders) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.options("/api/orders")
                .header("Access-Control-Request-Method", "POST");
        if (origin != null) {
            builder.header("Origin", origin);
        }
        if (requestHeaders != null) {
            builder.header("Access-Control-Request-Headers", requestHeaders);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private String bodyOf(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }

    // ---------------- configuration gating ----------------

    @Test
    @DisplayName("no CORS config means the filter does nothing and the request continues")
    void noConfigPassesThrough() {
        when(strategyManager.getCorsConfig(any())).thenReturn(null);
        MockServerWebExchange exchange = simpleGet(ALLOWED);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        assertNull(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("disabled CORS config passes through without touching headers")
    void disabledConfigPassesThrough() {
        givenConfig(config("enabled", false, "allowedOrigins", List.of(ALLOWED)));
        MockServerWebExchange exchange = simpleGet(ALLOWED);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        assertNull(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    // ---------------- simple (non-preflight) requests ----------------

    @Test
    @DisplayName("an allowed origin is echoed back and the request continues")
    void allowedOriginIsEchoed() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED, "https://other.example.com")));
        MockServerWebExchange exchange = simpleGet(ALLOWED);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(ALLOWED, exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("a disallowed origin gets no Allow-Origin header but the request still proceeds")
    void disallowedOriginGetsNoHeader() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED)));
        MockServerWebExchange exchange = simpleGet("https://evil.example.com");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Not blocking here is correct: CORS is enforced by the browser, so the server still serves
        // the request and simply withholds the header that would let the caller read the response.
        assertNull(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("a wildcard allowedOrigins answers with *")
    void wildcardOrigin() {
        givenConfig(config("allowedOrigins", List.of("*")));
        MockServerWebExchange exchange = simpleGet("https://anywhere.example.com");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals("*", exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("allowCredentials is only emitted when configured")
    void credentialsHeaderIsOptIn() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED), "allowCredentials", true));
        MockServerWebExchange withCredentials = simpleGet(ALLOWED);
        StepVerifier.create(filter.filter(withCredentials, chain)).verifyComplete();
        assertEquals("true", withCredentials.getResponse().getHeaders().getFirst("Access-Control-Allow-Credentials"));

        givenConfig(config("allowedOrigins", List.of(ALLOWED)));
        MockServerWebExchange without = simpleGet(ALLOWED);
        StepVerifier.create(filter.filter(without, chain)).verifyComplete();
        assertNull(without.getResponse().getHeaders().getFirst("Access-Control-Allow-Credentials"));
    }

    @Test
    @DisplayName("a comma-separated origin list with spaces still matches - values are trimmed")
    void commaSeparatedOriginsAreTrimmed() {
        // Regression: splitting on "," without trimming produced " https://b.example.com", which then
        // never matched a real Origin header, so a configuration that looks correct silently rejected.
        givenConfig(config("allowedOrigins", "https://a.example.com, https://b.example.com"));
        MockServerWebExchange exchange = simpleGet("https://b.example.com");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals("https://b.example.com",
                exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("exposed headers are emitted when configured")
    void exposedHeaders() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED),
                "exposedHeaders", List.of("X-Trace-Id", "X-Request-Id")));
        MockServerWebExchange exchange = simpleGet(ALLOWED);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals("X-Trace-Id, X-Request-Id",
                exchange.getResponse().getHeaders().getFirst("Access-Control-Expose-Headers"));
    }

    // ---------------- preflight ----------------

    @Test
    @DisplayName("an allowed preflight is answered 200 and short-circuits the chain")
    void allowedPreflightShortCircuits() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED),
                "allowedMethods", List.of("GET", "POST"),
                "allowedHeaders", List.of("Authorization", "Content-Type"),
                "maxAge", 600L));
        MockServerWebExchange exchange = preflight(ALLOWED, null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals(ALLOWED, exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
        assertEquals("GET, POST", exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Methods"));
        assertEquals("Authorization, Content-Type",
                exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Headers"));
        assertEquals("600", exchange.getResponse().getHeaders().getFirst("Access-Control-Max-Age"));
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("the preflight rejects requested headers outside the configured allowlist")
    void preflightRejectsUnconfiguredHeaders() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED),
                "allowedHeaders", List.of("Authorization")));
        MockServerWebExchange exchange = preflight(ALLOWED, "X-Custom-Header, Authorization");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertTrue(bodyOf(exchange).contains("X-Custom-Header"));
    }

    @Test
    @DisplayName("a disallowed preflight is rejected 403 and never reaches the chain")
    void disallowedPreflightIsRejected() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED)));
        MockServerWebExchange exchange = preflight("https://evil.example.com", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
        assertTrue(bodyOf(exchange).contains("CORS preflight request rejected"));
    }

    @Test
    @DisplayName("a preflight with no Origin at all is rejected rather than treated as same-origin")
    void missingOriginPreflightIsRejected() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED)));
        MockServerWebExchange exchange = preflight(null, null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertTrue(bodyOf(exchange).contains("unknown"));
    }

    /**
     * The Origin header is caller-controlled and is quoted into the rejection body. Built with
     * String.format it was possible to close the JSON string and inject arbitrary response content;
     * the body is now serialised with Jackson.
     */
    @Test
    @DisplayName("a quote-bearing Origin cannot break out of the rejection JSON")
    void maliciousOriginCannotInjectResponseBody() throws Exception {
        givenConfig(config("allowedOrigins", List.of(ALLOWED)));
        String payload = "\",\"injected\":\"yes";
        MockServerWebExchange exchange = preflight(payload, null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        String body = bodyOf(exchange);

        // The most meaningful check first: with String.format the payload closed the JSON string and
        // added a real "injected" key, and the result was still parseable JSON - so "does it parse"
        // is not the test, "does it have only the intended keys" is.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, Map.class);
        assertEquals(List.of("httpStatus", "code", "error", "message", "data"),
                List.copyOf(parsed.keySet()), "the Origin must not add fields to the response: " + body);
        assertEquals(403, parsed.get("httpStatus"));

        // The payload survives as one escaped string value rather than as JSON structure.
        assertTrue(body.contains("\\\""), "the embedded quotes must be escaped: " + body);
        assertTrue(String.valueOf(parsed.get("message")).contains(payload),
                "the whole origin must land inside the message: " + body);
    }

    @Test
    @DisplayName("OPTIONS without Access-Control-Request-Method is a normal request, not a preflight")
    void optionsWithoutRequestMethodIsNotAPreflight() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED)));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.options("/api/orders").header("Origin", ALLOWED).build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Handled by the simple-request path: headers added, chain continues, no 403/200 short-circuit.
        verify(chain).filter(exchange);
        assertEquals(ALLOWED, exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("the rejection body is UTF-8, so a non-ASCII Origin does not corrupt it")
    void rejectionBodyIsUtf8() {
        givenConfig(config("allowedOrigins", List.of(ALLOWED)));
        MockServerWebExchange exchange = preflight("https://例え.example.com", null);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String body = bodyOf(exchange);
        assertTrue(body.contains("例え"), "the origin should round-trip: " + body);
    }

    @Test
    @DisplayName("runs at the CORS order, ahead of authentication")
    void orderIsBeforeAuthentication() {
        assertEquals(FilterOrderConstants.CORS, filter.getOrder());
        assertTrue(filter.getOrder() < FilterOrderConstants.AUTHENTICATION,
                "a preflight carries no credentials, so CORS must be answered before auth rejects it");
    }
}
