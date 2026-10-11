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
}
