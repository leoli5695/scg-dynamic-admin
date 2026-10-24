package com.leoli.gateway.admin.filter;

import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the single-source internal-endpoint list.
 *
 * <p>{@code SecurityConfig} (permitAll) and {@link InternalApiTokenFilter} (X-Internal-Token
 * guard) both read {@link InternalApiEndpoints}. This test proves the token filter really does
 * guard every {@code TOKEN_PROTECTED} path and leaves every {@code PERMIT_ALL_NO_TOKEN} path
 * open, so the two sides cannot silently drift again - the failure mode that left
 * {@code /api/prometheus/sd/**} permitAll with no token, leaking internal topology.
 */
class InternalApiEndpointsContractTest {

    private static final String TOKEN = "contract-test-token";

    private static InternalApiTokenFilter newFilter() {
        GatewayAdminProperties props = new GatewayAdminProperties();
        props.getSecurity().setInternalToken(TOKEN);
        return new InternalApiTokenFilter(props);
    }

    /** Turn an Ant pattern into a concrete URI it matches (for {@code /**} suffixes). */
    private static String sampleUri(String pattern) {
        return pattern.endsWith("/**")
                ? pattern.substring(0, pattern.length() - 3) + "/probe"
                : pattern;
    }

    @Test
    @DisplayName("regression: prometheus SD is token-protected, not left wide open")
    void prometheusSdIsProtected() {
        assertTrue(InternalApiEndpoints.TOKEN_PROTECTED.contains("/api/prometheus/sd/**"),
                "/api/prometheus/sd/** must be in TOKEN_PROTECTED so it cannot leak topology unauthenticated");
    }

    @Test
    @DisplayName("the two lists are disjoint (no path is both public and token-guarded)")
    void listsAreDisjoint() {
        Set<String> overlap = new HashSet<>(InternalApiEndpoints.TOKEN_PROTECTED);
        overlap.retainAll(InternalApiEndpoints.PERMIT_ALL_NO_TOKEN);
        assertTrue(overlap.isEmpty(), "a path cannot be both permitAll-no-token and token-protected: " + overlap);
    }

    @Test
    @DisplayName("every TOKEN_PROTECTED path 401s without the internal token")
    void everyProtectedPathRejectsMissingToken() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        for (String pattern : InternalApiEndpoints.TOKEN_PROTECTED) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", sampleUri(pattern));
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), "expected 401 without token for " + pattern);
            assertNull(chain.getRequest(), "filter chain must NOT proceed for " + pattern);
        }
    }

    @Test
    @DisplayName("every TOKEN_PROTECTED path passes with a valid internal token")
    void everyProtectedPathAcceptsValidToken() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        for (String pattern : InternalApiEndpoints.TOKEN_PROTECTED) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", sampleUri(pattern));
            req.addHeader(InternalApiTokenFilter.TOKEN_HEADER, TOKEN);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(200, resp.getStatus(), "valid token must not be rejected for " + pattern);
            assertNotNull(chain.getRequest(), "filter chain must proceed for " + pattern);
        }
    }

    @Test
    @DisplayName("a wrong token is rejected, not just a missing one")
    void wrongTokenRejected() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", sampleUri("/api/prometheus/sd/**"));
        req.addHeader(InternalApiTokenFilter.TOKEN_HEADER, "not-the-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("every PERMIT_ALL_NO_TOKEN path is left open by the token filter")
    void publicPathsAreNotTokenGuarded() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        for (String pattern : InternalApiEndpoints.PERMIT_ALL_NO_TOKEN) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", sampleUri(pattern));
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertNotNull(chain.getRequest(),
                    "public path " + pattern + " must pass the token filter (it is guarded by JWT or truly public)");
        }
    }

    /**
     * Regression for the P0 bypass: Spring Security resolves its permitAll matchers through
     * UrlPathHelper, which strips matrix parameters (";...") and collapses duplicate slashes, while
     * this filter used to compare only the raw {@code getRequestURI()}. The two sides therefore
     * disagreed on the same request - "/api/instances/heartbeat;x=1" matched permitAll (JWT skipped)
     * but not the exact Ant pattern (token skipped), reaching the controller fully unauthenticated.
     *
     * <p>Only literal-";" and "//" variants are asserted. A percent-encoded "%3B" is decoded AFTER
     * semicolon stripping in UrlPathHelper, so it survives normalisation on BOTH sides; such a
     * request does not match permitAll either and falls through to anyRequest().authenticated().
     */
    @Test
    @DisplayName("regression: matrix-param and duplicate-slash variants are still token-guarded")
    void obfuscatedVariantsAreStillGuarded() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        String[] variants = {
                "/api/instances/heartbeat;x=1",
                "/api/instances/started;jsessionid=ABC123",
                "/api/services/traces;foo=bar",
                "/api/services/middleware-metadata;a=b",
                "/api/filter-executions/internal;x=1",
                "/api/traces/internal/batch;x=1",
                "//api/instances/heartbeat",
                "/api/gateway/health/probe;x=1"
        };
        for (String uri : variants) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), "expected 401 without token for obfuscated variant " + uri);
            assertNull(chain.getRequest(), "filter chain must NOT proceed for " + uri);
        }
    }

    @Test
    @DisplayName("an obfuscated variant is still accepted when it carries a valid token")
    void obfuscatedVariantWithValidTokenPasses() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/instances/heartbeat;x=1");
        req.addHeader(InternalApiTokenFilter.TOKEN_HEADER, TOKEN);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus(), "a valid token must not be rejected because the path was normalised");
        assertNotNull(chain.getRequest());
    }

    /**
     * Prometheus cannot send X-Internal-Token: stock http_sd_configs supports only basic_auth and
     * authorization. Without the Bearer form, /api/prometheus/sd/** would either stay permanently
     * 401 or have to be dropped from TOKEN_PROTECTED, re-leaking the topology it guards.
     */
    @Test
    @DisplayName("the token is also accepted as Authorization: Bearer, case-insensitively")
    void bearerTokenAccepted() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        for (String scheme : new String[]{"Bearer ", "bearer ", "BEARER "}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/prometheus/sd/exporters");
            req.addHeader("Authorization", scheme + TOKEN);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(200, resp.getStatus(), "scheme prefix \"" + scheme.trim() + "\" must be accepted");
            assertNotNull(chain.getRequest());
        }
    }

    @Test
    @DisplayName("a Bearer credential that is not the internal token is rejected, not treated as a JWT")
    void wrongBearerTokenRejected() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        String[] bad = {"Bearer not-the-token", "Bearer ", "Bearer", "Basic " + TOKEN, TOKEN};
        for (String header : bad) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/prometheus/sd/exporters");
            req.addHeader("Authorization", header);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), "Authorization: " + header + " must not pass the internal guard");
            assertNull(chain.getRequest());
        }
    }

    @Test
    @DisplayName("X-Internal-Token still wins when both headers are present")
    void directHeaderTakesPrecedence() throws Exception {
        InternalApiTokenFilter filter = newFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/prometheus/sd/exporters");
        req.addHeader(InternalApiTokenFilter.TOKEN_HEADER, TOKEN);
        req.addHeader("Authorization", "Bearer something-else");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }
}
