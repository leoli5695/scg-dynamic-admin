package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.config.FeatureGateFilter;
import com.leoli.gateway.admin.model.SystemSettingsEntity;
import com.leoli.gateway.admin.repository.SystemSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemSettingsServiceTest {

    private final SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    private final SystemSettingsService service = new SystemSettingsService(repository);

    @Test
    void missingRowFallsBackToAllFeaturesEnabledAndIsPersisted() {
        when(repository.findById(SystemSettingsEntity.DEFAULT_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        SystemSettingsEntity settings = service.get();

        assertTrue(settings.isStressTestEnabled());
        assertTrue(settings.isMiddlewareEnabled());
        assertTrue(settings.isPluginsEnabled());
        assertTrue(settings.isKubernetesEnabled());
        verify(repository).save(any(SystemSettingsEntity.class));
    }

    @Test
    void updateAppliesOnlyProvidedSwitchesAndRecordsOperator() {
        SystemSettingsEntity existing = new SystemSettingsEntity();
        when(repository.findById(SystemSettingsEntity.DEFAULT_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        SystemSettingsEntity saved = service.update(false, null, true, null, "admin");

        assertEquals(false, saved.isStressTestEnabled());
        assertTrue(saved.isMiddlewareEnabled(), "omitted switch keeps its value");
        assertTrue(saved.isPluginsEnabled());
        assertTrue(saved.isKubernetesEnabled());
        assertEquals("admin", saved.getUpdatedBy());
    }

    @Test
    void disabledFeatureBlocksOnlyItsWritePaths() throws Exception {
        SystemSettingsEntity disabled = new SystemSettingsEntity();
        disabled.setStressTestEnabled(false);
        disabled.setPluginsEnabled(false);
        disabled.setMiddlewareEnabled(false);
        disabled.setKubernetesEnabled(false);
        when(repository.findById(SystemSettingsEntity.DEFAULT_ID)).thenReturn(Optional.of(disabled));
        FeatureGateFilter filter = new FeatureGateFilter(service);

        for (String[] request : new String[][]{
                {"POST", "/api/stress-test/start"},
                {"POST", "/api/stress-test/quick"},
                {"POST", "/api/plugins/upload"},
                {"POST", "/api/routes/route-a/plugins"},
                {"PUT", "/api/routes/route-a/plugins/binding-a"},
                {"DELETE", "/api/routes/route-a/plugins/binding-a"},
                {"POST", "/api/routes/route-a/plugins/binding-a/enable"},
                {"POST", "/api/routes/route-a/plugins/binding-a/disable"},
                {"POST", "/api/services/middleware-metadata"},
                {"DELETE", "/api/plugin-bindings/1"},
                {"GET", "/api/kubernetes/clusters"},
        }) {
            MockHttpServletRequest http = new MockHttpServletRequest(request[0], request[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(http, response, new MockFilterChain());
            assertEquals(403, response.getStatus(), request[0] + " " + request[1]);
        }

        MockHttpServletRequest history = new MockHttpServletRequest("GET", "/api/stress-test/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(history, response, new MockFilterChain());
        assertEquals(200, response.getStatus(), "read-only history stays available");
        verify(repository, never()).save(any());
    }

    @Test
    void readOnlyDiagnosticsAndUnrelatedRouteWritesStayAvailable() throws Exception {
        SystemSettingsEntity disabled = new SystemSettingsEntity();
        disabled.setPluginsEnabled(false);
        disabled.setMiddlewareEnabled(false);
        when(repository.findById(SystemSettingsEntity.DEFAULT_ID)).thenReturn(Optional.of(disabled));
        for (String[] request : new String[][]{
                {"GET", "/api/routes/route-a/plugins"},
                {"GET", "/api/service-middleware/services"},
                {"POST", "/api/services/traces"},
                {"PUT", "/api/routes/route-a"},
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new FeatureGateFilter(service).doFilter(new MockHttpServletRequest(request[0], request[1]), response, new MockFilterChain());
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void enabledPluginWritesPassAndDisabledWritesRespectServletContext() throws Exception {
        SystemSettingsEntity settings = new SystemSettingsEntity();
        when(repository.findById(SystemSettingsEntity.DEFAULT_ID)).thenReturn(Optional.of(settings));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/api/routes/route-a/plugins");
        request.setContextPath("/admin");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        new FeatureGateFilter(service).doFilter(request, allowed, new MockFilterChain());
        assertEquals(200, allowed.getStatus());

        settings.setPluginsEnabled(false);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        new FeatureGateFilter(service).doFilter(request, blocked, new MockFilterChain());
        assertEquals(403, blocked.getStatus());
    }
}
