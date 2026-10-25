package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static com.leoli.gateway.constants.GatewayConfigConstants.AUTH_POLICIES_INDEX;
import static com.leoli.gateway.constants.GatewayConfigConstants.AUTH_POLICY_PREFIX;
import static com.leoli.gateway.constants.GatewayConfigConstants.AUTH_ROUTES_PREFIX;
import static com.leoli.gateway.constants.GatewayConfigConstants.GROUP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the periodic reconcile that stands in for config-center push, which
 * nacos-client 3.0.3 never receives from nacos-server 2.5.4.
 * <p>
 * The auth case is the security-relevant one: a revoked policy or a route unbound from a
 * policy re-publishes content without touching the index ID set, so an ID-only sync left
 * the old credentials and the old bindings enforced until the gateway restarted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthPolicyRefresherTest {

    @Mock
    private AuthBindingManager authBindingManager;

    @Mock
    private ConfigCenterService configService;

    private AuthPolicyRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new AuthPolicyRefresher(authBindingManager, configService, new ObjectMapper());
    }

    private static String policyJson(String id, String authType) {
        return "{\"policyId\":\"" + id + "\",\"authType\":\"" + authType + "\",\"enabled\":true}";
    }

    private void stubIndex(String... ids) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(ids[i]).append('"');
        }
        when(configService.getConfig(AUTH_POLICIES_INDEX, GROUP)).thenReturn(json.append(']').toString());
    }

    private void stubPolicy(String id, String authType) {
        when(configService.getConfig(AUTH_POLICY_PREFIX + id, GROUP)).thenReturn(policyJson(id, authType));
    }

    private void stubPolicyRoutes(String id, String... routeIds) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < routeIds.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(routeIds[i]).append('"');
        }
        when(configService.getConfig(AUTH_ROUTES_PREFIX + id, GROUP)).thenReturn(json.append(']').toString());
    }

    @Test
    @DisplayName("an edit to an already-indexed policy is applied - the case ID-set sync misses")
    void appliesEditToKnownPolicy() {
        stubIndex("p1");
        stubPolicy("p1", "JWT");
        refresher.periodicSyncPolicies();

        stubPolicy("p1", "API_KEY");
        refresher.periodicSyncPolicies();

        ArgumentCaptor<AuthConfig> captor = ArgumentCaptor.forClass(AuthConfig.class);
        verify(authBindingManager, times(2)).putPolicy(eq("p1"), captor.capture());
        List<AuthConfig> applied = captor.getAllValues();
        assertEquals("JWT", applied.get(0).getAuthType());
        assertEquals("API_KEY", applied.get(1).getAuthType(),
                "a rotated credential must reach AuthBindingManager without a restart");
    }

    @Test
    @DisplayName("rebinding a policy's routes is applied even though no ID changed")
    void appliesEditToRouteBindings() {
        stubIndex("p1");
        stubPolicy("p1", "JWT");
        stubPolicyRoutes("p1", "r1");
        refresher.periodicSyncPolicies();

        stubPolicyRoutes("p1", "r1", "r2");
        refresher.periodicSyncPolicies();

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(authBindingManager, times(2)).setPolicyRoutes(eq("p1"), captor.capture());
        assertEquals(List.of("r1"), captor.getAllValues().get(0));
        assertEquals(List.of("r1", "r2"), captor.getAllValues().get(1));
    }

    @Test
    @DisplayName("unchanged content is not reapplied, so a steady-state cycle is silent")
    void doesNotReapplyUnchangedContent() {
        stubIndex("p1");
        stubPolicy("p1", "JWT");
        stubPolicyRoutes("p1", "r1");
        refresher.periodicSyncPolicies();

        refresher.periodicSyncPolicies();
        refresher.periodicSyncPolicies();

        verify(authBindingManager, times(1)).putPolicy(eq("p1"), any());
        verify(authBindingManager, times(1)).setPolicyRoutes(eq("p1"), any());
    }

    @Test
    @DisplayName("a policy dropped from the index is revoked, not left enforcing")
    void removesPolicyMissingFromIndex() {
        stubIndex("p1");
        stubPolicy("p1", "JWT");
        refresher.periodicSyncPolicies();

        stubIndex();
        refresher.periodicSyncPolicies();

        verify(authBindingManager).removePolicy("p1");
        verify(configService).removeListener(eq(AUTH_POLICY_PREFIX + "p1"), eq(GROUP), any());
        verify(configService).removeListener(eq(AUTH_ROUTES_PREFIX + "p1"), eq(GROUP), any());
    }

    @Test
    @DisplayName("an unreachable (null) index must not revoke every credential")
    void nullIndexDoesNotRevoke() {
        stubIndex("p1");
        stubPolicy("p1", "JWT");
        refresher.periodicSyncPolicies();

        when(configService.getConfig(AUTH_POLICIES_INDEX, GROUP)).thenReturn(null);
        refresher.periodicSyncPolicies();

        verify(authBindingManager, never()).removePolicy(any());
    }

    @Test
    @DisplayName("a corrupt index must not be read as 'every policy was revoked'")
    void corruptIndexDoesNotRevoke() {
        stubIndex("p1");
        stubPolicy("p1", "JWT");
        refresher.periodicSyncPolicies();

        when(configService.getConfig(AUTH_POLICIES_INDEX, GROUP)).thenReturn("{not a list");
        refresher.periodicSyncPolicies();

        verify(authBindingManager, never()).removePolicy(any());
    }

    @Test
    @DisplayName("a policy config that reads back empty keeps the last known good rules")
    void missingPolicyContentDoesNotRevoke() {
        stubIndex("p1");
        stubPolicy("p1", "JWT");
        refresher.periodicSyncPolicies();

        when(configService.getConfig(AUTH_POLICY_PREFIX + "p1", GROUP)).thenReturn(null);
        refresher.periodicSyncPolicies();

        verify(authBindingManager, never()).removePolicy(any());
        verify(authBindingManager, times(1)).putPolicy(eq("p1"), any());
    }
}
