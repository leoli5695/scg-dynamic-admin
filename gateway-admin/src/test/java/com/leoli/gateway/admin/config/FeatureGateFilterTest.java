package com.leoli.gateway.admin.config;

import com.leoli.gateway.admin.service.SystemSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The gate is a servlet Filter, so every @WebMvcTest slice loads it; these tests cover the gate
 * itself without a Spring context, which lets the controller slices mock SystemSettingsService.
 */
class FeatureGateFilterTest {

    private final SystemSettingsService settings = mock(SystemSettingsService.class);
    private final FeatureGateFilter filter = new FeatureGateFilter(settings);

    private MockHttpServletResponse call(String method, String uri) throws Exception {
        return call(method, uri, "");
    }

    private MockHttpServletResponse call(String method, String uri, String contextPath) throws Exception {
        var request = new MockHttpServletRequest(method, contextPath + uri);
        request.setContextPath(contextPath);
        var response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertEquals(!reached.get() ? 403 : 200, response.getStatus(),
                () -> "chain reached=" + reached.get() + " for " + method + " " + uri);
        return response;
    }

    private void assertBlocked(MockHttpServletResponse response, String feature) throws Exception {
        assertEquals(403, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertTrue(response.getContentAsString().contains(feature), response.getContentAsString());
    }

    @Test void disabledStressTestBlocksBothWriteEndpoints() throws Exception {
        assertBlocked(call("POST", "/api/stress-test/start"), "stressTest");
        assertBlocked(call("POST", "/api/stress-test/quick"), "stressTest");
    }

    @Test void enabledStressTestLetsWritesThrough() throws Exception {
        when(settings.isStressTestEnabled()).thenReturn(true);
        assertEquals(200, call("POST", "/api/stress-test/start").getStatus());
        assertEquals(200, call("POST", "/api/stress-test/quick").getStatus());
    }

    @Test void readOnlyStressTestHistoryStaysReachableWhenDisabled() throws Exception {
        assertEquals(200, call("GET", "/api/stress-test/1").getStatus());
        assertEquals(200, call("GET", "/api/stress-test/1/status").getStatus());
        assertEquals(200, call("HEAD", "/api/stress-test/1").getStatus());
    }

    @Test void pluginsGateCoversBindingsAndPerRoutePlugins() throws Exception {
        assertBlocked(call("POST", "/api/plugins"), "plugins");
        assertBlocked(call("PUT", "/api/plugin-bindings/7"), "plugins");
        assertBlocked(call("POST", "/api/routes/r1/plugins"), "plugins");
        assertBlocked(call("DELETE", "/api/routes/r1/plugins/p9"), "plugins");
    }

    @Test void middlewareGateCoversTheMetadataEndpoint() throws Exception {
        assertBlocked(call("POST", "/api/service-middleware"), "middleware");
        assertBlocked(call("POST", "/api/services/middleware-metadata"), "middleware");
    }

    @Test void kubernetesGateBlocksReadsTooUnlikeTheOtherFeatures() throws Exception {
        assertBlocked(call("GET", "/api/kubernetes/pods"), "kubernetes");
        assertBlocked(call("POST", "/api/kubernetes/apply"), "kubernetes");
    }

    @Test void unrelatedWritesAreNotGated() throws Exception {
        assertEquals(200, call("POST", "/api/routes").getStatus());
        assertEquals(200, call("DELETE", "/api/services/3").getStatus());
    }

    @Test void gateMatchesWholePathSegmentsNotPrefixes() throws Exception {
        assertEquals(200, call("POST", "/api/plugins-extra").getStatus());
        assertEquals(200, call("POST", "/api/kubernetesx").getStatus());
        assertEquals(200, call("POST", "/api/routes/r1/plugins-extra").getStatus());
    }

    @Test void contextPathIsStrippedBeforeMatching() throws Exception {
        assertBlocked(call("POST", "/api/stress-test/start", "/admin"), "stressTest");
    }

    @Test void eachFeatureIsCheckedAgainstItsOwnSwitch() throws Exception {
        when(settings.isPluginsEnabled()).thenReturn(true);
        assertEquals(200, call("POST", "/api/plugins").getStatus());
        assertBlocked(call("POST", "/api/stress-test/start"), "stressTest");
        assertBlocked(call("GET", "/api/kubernetes/pods"), "kubernetes");
        verify(settings, never()).isMiddlewareEnabled();
    }
}