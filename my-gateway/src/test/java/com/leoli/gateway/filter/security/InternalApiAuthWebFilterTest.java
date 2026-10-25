package com.leoli.gateway.filter.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The gateway's inbound guard for {@code /internal/**}.
 *
 * <p>These endpoints are the control plane: filter-chain statistics, circuit-breaker control,
 * threshold changes. Before this filter existed they were reachable on the main gateway port with no
 * authentication at all, because {@code X-Internal-Token} was only ever attached to <i>outbound</i>
 * gateway-to-admin calls - so any client that could reach the port could clear monitoring data or
 * force-open a circuit breaker. There was no test for any of it.
 */
@ExtendWith(MockitoExtension.class)
class InternalApiAuthWebFilterTest {

    private static final String TOKEN = "shared-internal-secret";

    @Mock
    private WebFilterChain chain;

    private InternalApiAuthWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalApiAuthWebFilter();
        ReflectionTestUtils.setField(filter, "internalToken", TOKEN);
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange get(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private MockServerWebExchange getWithToken(String path, String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(path).header(InternalApiAuthWebFilter.TOKEN_HEADER, token).build());
    }

    @Test
    @DisplayName("startup fails when the shared secret is not configured")
    void refusesToStartWithoutAToken() {
        InternalApiAuthWebFilter unconfigured = new InternalApiAuthWebFilter();

        ReflectionTestUtils.setField(unconfigured, "internalToken", "");
        IllegalStateException empty = assertThrows(IllegalStateException.class, unconfigured::afterPropertiesSet);
        assertTrue(empty.getMessage().contains("refusing to start"), empty.getMessage());

        ReflectionTestUtils.setField(unconfigured, "internalToken", null);
        assertThrows(IllegalStateException.class, unconfigured::afterPropertiesSet);
    }

    @Test
    @DisplayName("startup succeeds once a token is present")
    void startsWhenTokenConfigured() {
        filter.afterPropertiesSet();
    }

    @Test
    @DisplayName("an internal request without the token is rejected with 401 and never enters the pipeline")
    void internalRequestWithoutTokenIsRejected() {
        MockServerWebExchange exchange = get("/internal/filter-chain/stats");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("a wrong token is rejected, not just a missing one")
    void internalRequestWithWrongTokenIsRejected() {
        MockServerWebExchange exchange = getWithToken("/internal/circuit-breaker/reset", "not-the-token");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("the correct token is accepted")
    void internalRequestWithValidTokenPasses() {
        MockServerWebExchange exchange = getWithToken("/internal/filter-chain/stats", TOKEN);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("non-internal paths are not gated by this filter")
    void nonInternalPathsPassThrough() {
        for (String path : new String[]{"/api/orders", "/actuator/health", "/", "/internalx/not-actually-internal"}) {
            MockServerWebExchange exchange = get(path);

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    /**
     * Encoded and doubled separators must not slip past the prefix check.
     *
     * <p>The encoded case has to be built with {@code method(GET, URI)} rather than
     * {@code get(String)}: the String overload treats its argument as a URI template and re-encodes
     * the percent sign, so {@code "/internal%2Fx"} arrives as {@code /internal%252Fx} and the test
     * would be asserting on an input no client can actually send. With a real URI, {@code getPath()}
     * decodes {@code %2F} to {@code /} exactly as it does for a Netty request.
     */
    @Test
    @DisplayName("an encoded separator cannot hide the /internal/ prefix")
    void encodedSeparatorsDoNotBypass() {
        MockServerWebExchange encoded = MockServerWebExchange.from(MockServerHttpRequest
                .method(org.springframework.http.HttpMethod.GET,
                        java.net.URI.create("/internal%2Ffilter-chain%2Fstats"))
                .build());
        assertEquals("/internal/filter-chain/stats", encoded.getRequest().getURI().getPath(),
                "precondition: URI.getPath() decodes %2F, matching what Netty hands the filter");

        StepVerifier.create(filter.filter(encoded, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, encoded.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("a doubled leading slash is still gated")
    void doubledLeadingSlashIsGated() {
        // Not a known exploit - Spring's PathPattern would not route this to an /internal/** handler
        // either - but the admin-side sibling guard was genuinely bypassed by this class of
        // mismatch, so the filter collapses leading slashes rather than depending on that argument.
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(org.springframework.http.HttpMethod.GET,
                        // Must be absolute: in a relative URI "//internal" parses as the AUTHORITY,
                        // so getPath() would return "/filter-chain/stats" and the test would silently
                        // stop testing anything. A real request line "GET //internal/x" plus a Host
                        // header yields exactly this absolute form.
                        java.net.URI.create("http://localhost//internal/filter-chain/stats"))
                .build());
        assertEquals("//internal/filter-chain/stats", exchange.getRequest().getURI().getPath(),
                "precondition: the doubled slash must survive into getPath()");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("a look-alike path is not treated as internal")
    void lookAlikePathIsNotInternal() {
        MockServerWebExchange exchange = get("/internalx/filter-chain/stats");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("runs before every gateway filter so a rejected internal request never enters the pipeline")
    void orderIsAheadOfThePipeline() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, filter.getOrder());
        assertTrue(filter.getOrder() < 0, "must precede the routing filters");
    }
}
