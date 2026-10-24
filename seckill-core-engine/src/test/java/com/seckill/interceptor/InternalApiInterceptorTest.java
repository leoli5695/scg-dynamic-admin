package com.seckill.interceptor;

import com.seckill.annotation.InternalApi;
import com.seckill.config.SeckillConfig;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IP whitelist is the only access control protecting @InternalApi endpoints
 * (log-level changes, warmup, the payment callback), so the IP it is evaluated
 * against must not be one the caller supplies.
 * <p>
 * No active Spring profile is set on purpose: {@code allowDevBypass} defaults to
 * true and a dev/local profile would short-circuit the check, making every
 * assertion below pass vacuously.
 */
class InternalApiInterceptorTest {

    /** Stand-in handler carrying @InternalApi so preHandle reaches the IP check. */
    static class ProtectedEndpoint {
        @InternalApi(description = "test")
        public void endpoint() {
        }
    }

    /** Stand-in handler without the annotation. */
    static class PlainEndpoint {
        public void endpoint() {
        }
    }

    /** TEST-NET-3: routable-looking but never in the configured whitelist. */
    private static final String OUTSIDER = "203.0.113.9";
    private static final String WHITELIST = "127.0.0.1,10.0.0.0/8";

    private InternalApiInterceptor interceptor(boolean trustProxyHeaders) {
        SeckillConfig config = new SeckillConfig();
        config.getInternalApi().setEnabled(true);
        config.getInternalApi().setWhitelist(WHITELIST);
        config.getInternalApi().setTrustProxyHeaders(trustProxyHeaders);
        return new InternalApiInterceptor(config, new MockEnvironment());
    }

    private HandlerMethod protectedHandler() throws NoSuchMethodException {
        return new HandlerMethod(new ProtectedEndpoint(),
                ProtectedEndpoint.class.getMethod("endpoint"));
    }

    private MockHttpServletRequest outsiderClaimingLoopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(OUTSIDER);
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        return request;
    }

    @Test
    @DisplayName("a spoofed X-Forwarded-For does not satisfy the whitelist by default")
    void spoofedForwardedForIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor(false)
                .preHandle(outsiderClaimingLoopback(), response, protectedHandler());

        assertFalse(allowed, "a client-supplied X-Forwarded-For must not be trusted");
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    @DisplayName("a spoofed X-Real-IP does not satisfy the whitelist either")
    void spoofedRealIpIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(OUTSIDER);
        request.addHeader("X-Real-IP", "10.1.2.3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor(false).preHandle(request, response, protectedHandler()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    @DisplayName("forwarded headers are honoured once the operator opts in")
    void forwardedForHonouredWhenTrustProxyHeadersEnabled() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor(true)
                .preHandle(outsiderClaimingLoopback(), response, protectedHandler()));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    @DisplayName("the real TCP peer still decides when it is genuinely internal")
    void genuineInternalPeerIsAllowed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.9.8.7");

        assertTrue(interceptor(false)
                .preHandle(request, new MockHttpServletResponse(), protectedHandler()));
    }

    @Test
    @DisplayName("a genuinely external peer is rejected")
    void genuineExternalPeerIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(OUTSIDER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor(false).preHandle(request, response, protectedHandler()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    @DisplayName("handlers without @InternalApi are never blocked")
    void unannotatedHandlerPasses() throws Exception {
        HandlerMethod plain = new HandlerMethod(new PlainEndpoint(),
                PlainEndpoint.class.getMethod("endpoint"));

        assertTrue(interceptor(false)
                .preHandle(outsiderClaimingLoopback(), new MockHttpServletResponse(), plain));
    }
}
