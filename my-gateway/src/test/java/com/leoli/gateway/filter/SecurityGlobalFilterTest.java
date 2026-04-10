package com.leoli.gateway.filter;

import com.leoli.gateway.filter.security.SecurityGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
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

    @InjectMocks
    private SecurityGlobalFilter filter;

    @BeforeEach
    void setUp() {
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
        @DisplayName("Should detect SQL comment injection")
        void testSqlInjection_commentPattern() {
            MockServerWebExchange exchange = createGetExchange("/api/test?id=1--");
            Map<String, Object> config = createConfigWithSql(true);
            when(strategyManager.getSecurityConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
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
    @DisplayName("Filter order should be -500")
    void testGetOrder() {
        assertEquals(-500, filter.getOrder());
    }

    // ============================================================
    // Helper Methods
    // ============================================================

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