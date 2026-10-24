package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.security.SecurityGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SecurityGlobalFilter.
 * Tests SQL injection and XSS detection capabilities.
 */
@ExtendWith(MockitoExtension.class)
class SecurityGlobalFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private GatewayFilterChain chain;

    private SecurityGlobalFilter filter;

    @BeforeEach
    void setUp() {
        // Real ObjectMapper: threat responses are serialized with Jackson
        filter = new SecurityGlobalFilter(strategyManager, new ObjectMapper());
        // Use lenient() to avoid UnnecessaryStubbingException for tests that block requests
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // ============================================================
    // Basic Filter Tests
    // ============================================================

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Should pass through when no security config")
        void testFilter_noConfig() {
            MockServerWebExchange exchange = createGetExchange("/api/test");
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(null);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should pass through when security disabled")
        void testFilter_disabled() {
            MockServerWebExchange exchange = createGetExchange("/api/test");
            Map<String, Object> config = createConfig(false, "BLOCK");
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should exclude paths from security check")
        void testFilter_excludePath() {
            MockServerWebExchange exchange = createGetExchange("/health/check");
            Map<String, Object> config = createConfig(true, "BLOCK");
            config.put("excludePaths", Arrays.asList("/health/*"));
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // SQL Injection Detection Tests
    // ============================================================

    @Nested
    @DisplayName("SQL Injection Detection Tests")
    class SqlInjectionTests {

        @Test
        @DisplayName("Should detect SELECT injection")
        void testSqlInjection_selectPattern() {
            MockServerWebExchange exchange = createGetExchange("/api/test?query=SELECT * FROM users");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(exchange);
        }

        @Test
        @DisplayName("Should detect UNION SELECT injection")
        void testSqlInjection_unionPattern() {
            MockServerWebExchange exchange = createGetExchange("/api/test?id=1 UNION SELECT * FROM passwords");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect SQL comment injection correlated with a high-confidence hit")
        void testSqlInjection_commentPattern() {
            MockServerWebExchange exchange = createGetExchange("/api/test?id=1' --");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should NOT block bare comment markers (false-positive reduction)")
        void testSqlComment_bareMarkerPasses() {
            // Values merely ending in "--" (e.g. markdown, passwords, dates) must
            // not be blocked: the low-confidence comment pattern only counts when
            // a high-confidence SQL pattern also matched.
            MockServerWebExchange exchange = createGetExchange("/api/test?password=abc--");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertNull(exchange.getResponse().getStatusCode());
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should detect boolean tautology injection")
        void testSqlInjection_booleanTautology() {
            MockServerWebExchange exchange = createGetExchange("/api/test?id=1 OR '1'='1");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect time-based injection")
        void testSqlInjection_timeBased() {
            MockServerWebExchange exchange = createGetExchange("/api/test?id=1; SLEEP(5)");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect DROP TABLE injection")
        void testSqlInjection_dropTable() {
            MockServerWebExchange exchange = createGetExchange("/api/test?table=users; DROP TABLE users");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect information_schema access")
        void testSqlInjection_informationSchema() {
            MockServerWebExchange exchange = createGetExchange("/api/test?query=SELECT * FROM information_schema.tables");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should pass safe SQL keywords in normal context")
        void testSqlInjection_safeQuery() {
            // "select" alone without SQL context should not trigger
            MockServerWebExchange exchange = createGetExchange("/api/test?word=selection");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Should pass through (no SQL injection pattern matched)
            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // XSS Detection Tests
    // ============================================================

    @Nested
    @DisplayName("XSS Detection Tests")
    class XssDetectionTests {

        @Test
        @DisplayName("Should detect script tag")
        void testXss_scriptTag() {
            MockServerWebExchange exchange = createGetExchange("/api/test?input=<script>alert(1)</script>");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect event handler")
        void testXss_eventHandler() {
            MockServerWebExchange exchange = createGetExchange("/api/test?input=<img onerror=alert(1)>");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect javascript: protocol")
        void testXss_javascriptProtocol() {
            MockServerWebExchange exchange = createGetExchange("/api/test?url=javascript:alert(1)");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect alert function")
        void testXss_alertFunction() {
            MockServerWebExchange exchange = createGetExchange("/api/test?input=alert(document.cookie)");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect iframe injection")
        void testXss_iframe() {
            MockServerWebExchange exchange = createGetExchange("/api/test?html=<iframe src='evil.com'>");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should detect SVG onload")
        void testXss_svgOnload() {
            MockServerWebExchange exchange = createGetExchange("/api/test?svg=<svg onload=alert(1)>");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should pass safe HTML content")
        void testXss_safeContent() {
            MockServerWebExchange exchange = createGetExchange("/api/test?text=Hello World");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // Mode Tests
    // ============================================================

    @Nested
    @DisplayName("Mode Tests")
    class ModeTests {

        @Test
        @DisplayName("DETECT mode should log but not block")
        void testDetectMode() {
            MockServerWebExchange exchange = createGetExchange("/api/test?q=<script>alert(1)</script>");
            Map<String, Object> config = createConfig(true, "DETECT");
            config.put("enableXssProtection", true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Should pass through in DETECT mode
            verify(chain).filter(exchange);
            // Response should not be 400
            assertNotEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("BLOCK mode should reject request")
        void testBlockMode() {
            MockServerWebExchange exchange = createGetExchange("/api/test?q=<script>alert(1)</script>");
            Map<String, Object> config = createConfigWithXss(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(exchange);
        }
    }

    // ============================================================
    // Header Check Tests
    // ============================================================

    @Nested
    @DisplayName("Header Check Tests")
    class HeaderCheckTests {

        @Test
        @DisplayName("Should check headers when enabled")
        void testHeaderCheck_enabled() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Custom", "<script>alert(1)</script>")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            Map<String, Object> config = createConfigWithXss(true);
            config.put("checkHeaders", true);
            config.put("checkParameters", false);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should skip header check when disabled")
        void testHeaderCheck_disabled() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Custom", "<script>alert(1)</script>")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            Map<String, Object> config = createConfigWithXss(true);
            config.put("checkHeaders", false);
            config.put("checkParameters", false);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // Filter Order Test
    // ============================================================

    @Test
    @DisplayName("Filter order should be -480: after IP_FILTER, before AUTHENTICATION")
    void testGetOrder() {
        assertEquals(FilterOrderConstants.SECURITY_GLOBAL, filter.getOrder());
        assertEquals(-480, filter.getOrder());
        // The invariants that actually matter, asserted rather than left to the literal above:
        // a blacklisted address must be rejected BEFORE the WAF buffers and regex-scans up to
        // gateway.security.max-body-size-mb of its body (scanning runs on boundedElastic, which the
        // token-quota / semantic-cache / SSL-refresh paths share), while unauthenticated traffic
        // must still be scanned, which is the entire point of a WAF.
        assertTrue(filter.getOrder() > FilterOrderConstants.IP_FILTER,
                "WAF must run after IP filtering so a blacklisted address cannot force body buffering");
        assertTrue(filter.getOrder() < FilterOrderConstants.AUTHENTICATION,
                "WAF must run before authentication so unauthenticated traffic is scanned too");
    }

    @Test
    @DisplayName("the body cap defaults to 10MB without Spring, is configurable, and clamps nonsense")
    void bodyCapIsConfigurable() {
        SecurityGlobalFilter direct = new SecurityGlobalFilter(strategyManager, new ObjectMapper());
        assertEquals(10 * 1024 * 1024, ReflectionTestUtils.getField(direct, "maxBodySizeBytes"),
                "this test class builds the filter with `new`, so no @Value injection happens - "
                        + "an uninitialised cap of 0 would reject every request body");

        direct.setMaxBodySizeMb(2);
        assertEquals(2 * 1024 * 1024, ReflectionTestUtils.getField(direct, "maxBodySizeBytes"));

        direct.setMaxBodySizeMb(0);
        assertEquals(1024 * 1024, ReflectionTestUtils.getField(direct, "maxBodySizeBytes"),
                "a zero or negative cap is a config typo, not an intent to blackhole every body");
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    // ============================================================
    // Body Size Limit Tests
    // ============================================================

    /**
     * Regression coverage for the streaming size cap.
     *
     * <p>The bug: the oversize handler wrote the 413 and then completed EMPTY, and the following
     * {@code defaultIfEmpty} could not distinguish "rejected" from "body was genuinely empty". The
     * flatMap therefore ran on a zero-byte buffer, found no threats, and called
     * {@code chain.filter(...)} - the request went downstream with a 413 already committed, and the
     * upstream wrote a second response body over it. The size limit blocked nothing.
     *
     * <p>These tests drive the STREAMING accumulator, not the Content-Length fast path (which was
     * already correct and returns without reaching the chain). A small declared Content-Length with a
     * large actual body is exactly the forgery the accumulator exists to catch, and it guarantees the
     * fast path cannot short-circuit the test.
     */
    @Nested
    @DisplayName("Body Size Limit Tests")
    class BodySizeLimitTests {

        private MockServerWebExchange streamedPost(int totalBytes, String declaredContentLength) {
            byte[] payload = new byte[totalBytes];
            Arrays.fill(payload, (byte) 'a');
            // Two chunks, so it is the streaming accumulator that has to notice the total exceeding
            // the cap - a single buffer would not prove the incremental path works.
            int half = totalBytes / 2;
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Flux.just(
                    factory.wrap(Arrays.copyOfRange(payload, 0, half)),
                    factory.wrap(Arrays.copyOfRange(payload, half, totalBytes)));
            MockServerHttpRequest.BodyBuilder builder = MockServerHttpRequest
                    .post("/api/upload")
                    .contentType(MediaType.APPLICATION_JSON);
            if (declaredContentLength != null) {
                builder.header(HttpHeaders.CONTENT_LENGTH, declaredContentLength);
            }
            return MockServerWebExchange.builder(builder.body(body)).build();
        }

        private Map<String, Object> bodyScanConfig() {
            Map<String, Object> config = createConfig(true, "BLOCK");
            config.put("checkBody", true);
            config.put("checkParameters", false);
            config.put("checkHeaders", false);
            return config;
        }

        @Test
        @DisplayName("an oversized streamed body is rejected with 413 and never reaches the chain")
        void oversizedStreamedBodyIsRejectedAndNotForwarded() {
            filter.setMaxBodySizeMb(1);
            // 1.2MB actual body, but declares 10 bytes so the Content-Length fast path cannot fire.
            MockServerWebExchange exchange = streamedPost(1200 * 1024, "10");
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(bodyScanConfig());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("an oversized body with no Content-Length at all is also rejected and not forwarded")
        void oversizedBodyWithoutContentLengthIsRejected() {
            filter.setMaxBodySizeMb(1);
            MockServerWebExchange exchange = streamedPost(1200 * 1024, null);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(bodyScanConfig());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("an oversized declared Content-Length is rejected without reading the body")
        void oversizedContentLengthIsRejected() {
            filter.setMaxBodySizeMb(1);
            MockServerWebExchange exchange = streamedPost(64, String.valueOf(2 * 1024 * 1024));
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(bodyScanConfig());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("a body under the cap is still scanned and forwarded")
        void undersizedBodyStillFlows() {
            filter.setMaxBodySizeMb(1);
            MockServerWebExchange exchange = streamedPost(10 * 1024, null);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(bodyScanConfig());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertNull(exchange.getResponse().getStatusCode(), "a small clean body must not be rejected");
            verify(chain).filter(any());
        }

        @Test
        @DisplayName("an under-cap body carrying an injection is still blocked - the cap did not disable scanning")
        void undersizedMaliciousBodyIsStillBlocked() {
            filter.setMaxBodySizeMb(1);
            MockServerHttpRequest request = MockServerHttpRequest.post("/api/upload")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"q\":\"1 UNION SELECT password FROM users\"}");
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(bodyScanConfig());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
            verify(chain, never()).filter(any());
        }
    }

    private MockServerWebExchange createGetExchange(String uri) {
        MockServerHttpRequest request = MockServerHttpRequest.get(uri).build();
        return MockServerWebExchange.builder(request).build();
    }

    private Map<String, Object> createConfig(boolean enabled, String mode) {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", enabled);
        config.put("mode", mode);
        config.put("checkParameters", true);
        config.put("checkHeaders", false);
        config.put("checkBody", false);
        config.put("enableSqlInjectionProtection", true);
        config.put("enableXssProtection", true);
        return config;
    }

    private Map<String, Object> createConfigWithSql(boolean enabled) {
        Map<String, Object> config = createConfig(enabled, "BLOCK");
        config.put("enableSqlInjectionProtection", true);
        config.put("enableXssProtection", false);
        return config;
    }

    private Map<String, Object> createConfigWithXss(boolean enabled) {
        Map<String, Object> config = createConfig(enabled, "BLOCK");
        config.put("enableSqlInjectionProtection", false);
        config.put("enableXssProtection", true);
        return config;
    }
}