package com.leoli.gateway.manager;

import com.leoli.gateway.cache.JwtValidationCache;
import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AuthBindingManager.
 * Tests policy management, route binding, and credential matching.
 *
 * @author leoli
 */
class AuthBindingManagerTest {

    private AuthBindingManager manager;
    private JwtValidationCache jwtValidationCache;

    @BeforeEach
    void setUp() throws Exception {
        manager = new AuthBindingManager();
        jwtValidationCache = new JwtValidationCache();
        // Use reflection to inject the cache
        Field cacheField = AuthBindingManager.class.getDeclaredField("jwtValidationCache");
        cacheField.setAccessible(true);
        cacheField.set(manager, jwtValidationCache);
    }

    @Nested
    @DisplayName("Policy Management Tests")
    class PolicyManagementTests {

        @Test
        @DisplayName("Should add and retrieve policy")
        void shouldAddAndRetrievePolicy() {
            // Given
            String policyId = "policy-1";
            AuthConfig config = createBasicAuthConfig("user1", "pass1");

            // When
            manager.putPolicy(policyId, config);
            AuthConfig retrieved = manager.getPolicy(policyId);

            // Then
            assertNotNull(retrieved);
            assertEquals("BASIC", retrieved.getAuthType());
        }

        @Test
        @DisplayName("Should return null for non-existent policy")
        void shouldReturnNullForNonExistentPolicy() {
            // When
            AuthConfig retrieved = manager.getPolicy("non-existent");

            // Then
            assertNull(retrieved);
        }

        @Test
        @DisplayName("Should remove policy")
        void shouldRemovePolicy() {
            // Given
            String policyId = "policy-1";
            AuthConfig config = createBasicAuthConfig("user1", "pass1");
            manager.putPolicy(policyId, config);

            // When
            manager.removePolicy(policyId);
            AuthConfig retrieved = manager.getPolicy(policyId);

            // Then
            assertNull(retrieved);
        }

        @Test
        @DisplayName("Should handle null policyId in putPolicy")
        void shouldHandleNullPolicyId() {
            // Given
            AuthConfig config = createBasicAuthConfig("user1", "pass1");

            // When
            manager.putPolicy(null, config);

            // Then - Should not throw exception
            assertEquals(0, manager.getAllPolicies().size());
        }

        @Test
        @DisplayName("Should handle null config in putPolicy")
        void shouldHandleNullConfig() {
            // When
            manager.putPolicy("policy-1", null);

            // Then - Should not throw exception
            assertEquals(0, manager.getAllPolicies().size());
        }

        @Test
        @DisplayName("Should get policies by type")
        void shouldGetPoliciesByType() {
            // Given
            manager.putPolicy("basic-1", createBasicAuthConfig("user1", "pass1"));
            manager.putPolicy("basic-2", createBasicAuthConfig("user2", "pass2"));
            manager.putPolicy("jwt-1", createJwtAuthConfig());

            // When
            List<String> basicPolicies = manager.getPoliciesByType(AuthType.BASIC);
            List<String> jwtPolicies = manager.getPoliciesByType(AuthType.JWT);

            // Then
            assertEquals(2, basicPolicies.size());
            assertEquals(1, jwtPolicies.size());
        }

        @Test
        @DisplayName("Should return empty list for type with no policies")
        void shouldReturnEmptyListForNoPolicies() {
            // When
            List<String> hmacPolicies = manager.getPoliciesByType(AuthType.HMAC);

            // Then
            assertTrue(hmacPolicies.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for null auth type")
        void shouldReturnEmptyListForNullAuthType() {
            // When
            List<String> policies = manager.getPoliciesByType((AuthType) null);

            // Then
            assertTrue(policies.isEmpty());
        }

        @Test
        @DisplayName("Should get all policies")
        void shouldGetAllPolicies() {
            // Given
            manager.putPolicy("policy-1", createBasicAuthConfig("user1", "pass1"));
            manager.putPolicy("policy-2", createJwtAuthConfig());

            // When
            Map<String, AuthConfig> allPolicies = manager.getAllPolicies();

            // Then
            assertEquals(2, allPolicies.size());
        }
    }

    @Nested
    @DisplayName("Policy Routes Management Tests")
    class PolicyRoutesTests {

        @Test
        @DisplayName("Should set and get policy routes")
        void shouldSetAndGetPolicyRoutes() {
            // Given
            String policyId = "policy-1";
            List<String> routes = Arrays.asList("route-1", "route-2");

            // When
            manager.setPolicyRoutes(policyId, routes);
            Set<String> retrievedRoutes = manager.getPolicyRoutes(policyId);

            // Then
            assertEquals(2, retrievedRoutes.size());
            assertTrue(retrievedRoutes.contains("route-1"));
            assertTrue(retrievedRoutes.contains("route-2"));
        }

        @Test
        @DisplayName("Should return empty set for policy with no routes")
        void shouldReturnEmptySetForNoRoutes() {
            // When
            Set<String> routes = manager.getPolicyRoutes("non-existent");

            // Then
            assertTrue(routes.isEmpty());
        }

        @Test
        @DisplayName("Should check if route is bound to policy")
        void shouldCheckRouteBinding() {
            // Given
            String policyId = "policy-1";
            List<String> routes = Arrays.asList("route-1", "route-2");
            manager.setPolicyRoutes(policyId, routes);

            // When & Then
            assertTrue(manager.isRouteBound(policyId, "route-1"));
            assertFalse(manager.isRouteBound(policyId, "route-3"));
        }

        @Test
        @DisplayName("Should get policies for route (reverse lookup)")
        void shouldGetPoliciesForRoute() {
            // Given
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1", "route-2"));
            manager.setPolicyRoutes("policy-2", Arrays.asList("route-1", "route-3"));

            // When
            Set<String> routePolicies = manager.getPoliciesForRoute("route-1");

            // Then
            assertEquals(2, routePolicies.size());
            assertTrue(routePolicies.contains("policy-1"));
            assertTrue(routePolicies.contains("policy-2"));
        }

        @Test
        @DisplayName("Should update policy routes and maintain reverse index")
        void shouldUpdatePolicyRoutes() {
            // Given
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1", "route-2"));

            // When - Update routes
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-3"));

            // Then
            Set<String> policyRoutes = manager.getPolicyRoutes("policy-1");
            assertEquals(1, policyRoutes.size());
            assertTrue(policyRoutes.contains("route-3"));

            // Old routes should no longer have this policy
            Set<String> route1Policies = manager.getPoliciesForRoute("route-1");
            assertTrue(route1Policies.isEmpty());
        }

        @Test
        @DisplayName("Should handle null policyId in setPolicyRoutes")
        void shouldHandleNullPolicyIdInSetRoutes() {
            // When
            manager.setPolicyRoutes(null, Arrays.asList("route-1"));

            // Then - Should not throw exception
            assertTrue(manager.getPoliciesForRoute("route-1").isEmpty());
        }

        @Test
        @DisplayName("Should remove routes when set to null or empty")
        void shouldRemoveRoutesWhenSetToNull() {
            // Given
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1"));

            // When
            manager.setPolicyRoutes("policy-1", null);

            // Then
            assertTrue(manager.getPolicyRoutes("policy-1").isEmpty());
            assertTrue(manager.getPoliciesForRoute("route-1").isEmpty());
        }
    }

    @Nested
    @DisplayName("Route Authentication Requirements Tests")
    class RouteAuthRequirementsTests {

        @Test
        @DisplayName("Should check if route requires auth")
        void shouldCheckRouteRequiresAuth() {
            // Given
            AuthConfig config = createBasicAuthConfig("user1", "pass1");
            config.setEnabled(true);
            manager.putPolicy("policy-1", config);
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1"));

            // When & Then
            assertTrue(manager.requiresAuth("route-1"));
            assertFalse(manager.requiresAuth("route-2"));
        }

        @Test
        @DisplayName("Should not require auth if policy is disabled")
        void shouldNotRequireAuthIfDisabled() {
            // Given
            AuthConfig config = createBasicAuthConfig("user1", "pass1");
            config.setEnabled(false);
            manager.putPolicy("policy-1", config);
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1"));

            // When & Then
            assertFalse(manager.requiresAuth("route-1"));
        }

        @Test
        @DisplayName("Should require auth if at least one enabled policy is bound")
        void shouldRequireAuthIfAtLeastOneEnabled() {
            // Given
            AuthConfig disabledConfig = createBasicAuthConfig("user1", "pass1");
            disabledConfig.setEnabled(false);
            AuthConfig enabledConfig = createJwtAuthConfig();
            enabledConfig.setEnabled(true);

            manager.putPolicy("disabled-policy", disabledConfig);
            manager.putPolicy("enabled-policy", enabledConfig);
            manager.setPolicyRoutes("disabled-policy", Arrays.asList("route-1"));
            manager.setPolicyRoutes("enabled-policy", Arrays.asList("route-1"));

            // When & Then
            assertTrue(manager.requiresAuth("route-1"));
        }
    }

    @Nested
    @DisplayName("Credential Matching Tests")
    class CredentialMatchingTests {

        @Test
        @DisplayName("Should find policy by Basic Auth credentials")
        void shouldFindPolicyByBasicAuth() {
            // Given
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword");
            manager.putPolicy("basic-policy", config);

            // When
            String policyId = manager.findPolicyByBasicAuth("testuser", "testpassword");

            // Then
            assertEquals("basic-policy", policyId);
        }

        @Test
        @DisplayName("Should return null for wrong Basic Auth credentials")
        void shouldReturnNullForWrongBasicAuth() {
            // Given
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword");
            manager.putPolicy("basic-policy", config);

            // When
            String policyId = manager.findPolicyByBasicAuth("wronguser", "wrongpassword");

            // Then
            assertNull(policyId);
        }

        @Test
        @DisplayName("Should find policy by API Key")
        void shouldFindPolicyByApiKey() {
            // Given
            AuthConfig config = createApiKeyConfig("test-api-key-123", null);
            manager.putPolicy("apikey-policy", config);

            // When
            String policyId = manager.findPolicyByApiKey("test-api-key-123");

            // Then
            assertEquals("apikey-policy", policyId);
        }

        @Test
        @DisplayName("Should find policy by API Key with prefix")
        void shouldFindPolicyByApiKeyWithPrefix() {
            // Given
            AuthConfig config = createApiKeyConfig("real-key", "pk_live_");
            manager.putPolicy("apikey-policy", config);

            // When
            String policyId = manager.findPolicyByApiKey("pk_live_real-key");

            // Then
            assertEquals("apikey-policy", policyId);
        }

        @Test
        @DisplayName("Should return null for wrong API Key")
        void shouldReturnNullForWrongApiKey() {
            // Given
            AuthConfig config = createApiKeyConfig("valid-key", null);
            manager.putPolicy("apikey-policy", config);

            // When
            String policyId = manager.findPolicyByApiKey("invalid-key");

            // Then
            assertNull(policyId);
        }

        @Test
        @DisplayName("Should find policy by HMAC Access Key")
        void shouldFindPolicyByAccessKey() {
            // Given
            AuthConfig config = createHmacConfig("access-key-123");
            manager.putPolicy("hmac-policy", config);

            // When
            String policyId = manager.findPolicyByAccessKey("access-key-123");

            // Then
            assertEquals("hmac-policy", policyId);
        }

        @Test
        @DisplayName("Should find policy by Access Key from secrets map")
        void shouldFindPolicyByAccessKeyFromSecrets() {
            // Given
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("HMAC");
            Map<String, String> secrets = new HashMap<>();
            secrets.put("access-key-123", "secret-key-123");
            config.setAccessKeySecrets(secrets);
            manager.putPolicy("hmac-policy", config);

            // When
            String policyId = manager.findPolicyByAccessKey("access-key-123");

            // Then
            assertEquals("hmac-policy", policyId);
        }

        @Test
        @DisplayName("Should find policy by OAuth2 Client ID")
        void shouldFindPolicyByClientId() {
            // Given
            AuthConfig config = createOAuth2Config("client-id-123");
            manager.putPolicy("oauth2-policy", config);

            // When
            String policyId = manager.findPolicyByClientId("client-id-123");

            // Then
            assertEquals("oauth2-policy", policyId);
        }

        @Test
        @DisplayName("Should not match disabled policies")
        void shouldNotMatchDisabledPolicies() {
            // Given
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword");
            config.setEnabled(false);
            manager.putPolicy("disabled-policy", config);

            // When
            String policyId = manager.findPolicyByBasicAuth("testuser", "testpassword");

            // Then
            assertNull(policyId);
        }
    }

    @Nested
    @DisplayName("Route Authorization Tests")
    class RouteAuthorizationTests {

        @Test
        @DisplayName("Should check if route is authorized for policy")
        void shouldCheckRouteAuthorization() {
            // Given
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1", "route-2"));

            // When & Then
            assertTrue(manager.isRouteAuthorized("policy-1", "route-1"));
            assertFalse(manager.isRouteAuthorized("policy-1", "route-3"));
        }
    }

    @Nested
    @DisplayName("Cache Management Tests")
    class CacheManagementTests {

        @Test
        @DisplayName("Should clear all caches")
        void shouldClearAllCaches() {
            // Given
            manager.putPolicy("policy-1", createBasicAuthConfig("user1", "pass1"));
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1"));

            // When
            manager.clear();

            // Then
            assertTrue(manager.getAllPolicies().isEmpty());
            assertTrue(manager.getPolicyRoutes("policy-1").isEmpty());
            assertTrue(manager.getPoliciesForRoute("route-1").isEmpty());
        }

        @Test
        @DisplayName("Should get cache statistics")
        void shouldGetCacheStats() {
            // Given
            manager.putPolicy("policy-1", createBasicAuthConfig("user1", "pass1"));
            manager.putPolicy("policy-2", createJwtAuthConfig());
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1"));

            // When
            Map<String, Object> stats = manager.getStats();

            // Then
            assertEquals(2, stats.get("policyCount"));
            assertEquals(1, stats.get("policyRoutesCount"));
            assertNotNull(stats.get("policiesByType"));
        }
    }

    @Nested
    @DisplayName("Policy Removal Tests")
    class PolicyRemovalTests {

        @Test
        @DisplayName("Should remove policy and clean up indexes")
        void shouldRemovePolicyAndCleanup() {
            // Given
            manager.putPolicy("policy-1", createBasicAuthConfig("user1", "pass1"));
            manager.setPolicyRoutes("policy-1", Arrays.asList("route-1", "route-2"));

            // When
            manager.removePolicy("policy-1");

            // Then
            assertNull(manager.getPolicy("policy-1"));
            assertTrue(manager.getPolicyRoutes("policy-1").isEmpty());
            assertTrue(manager.getPoliciesForRoute("route-1").isEmpty());
            assertTrue(manager.getPoliciesForRoute("route-2").isEmpty());
            assertTrue(manager.getPoliciesByType(AuthType.BASIC).isEmpty());
        }
    }

    // Helper methods to create test configs
    private AuthConfig createBasicAuthConfig(String username, String password) {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("BASIC");
        config.setBasicUsername(username);
        config.setBasicPassword(password);
        return config;
    }

    private AuthConfig createJwtAuthConfig() {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("JWT");
        config.setSecretKey("jwt-secret-key");
        return config;
    }

    private AuthConfig createApiKeyConfig(String apiKey, String prefix) {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("API_KEY");
        config.setApiKey(apiKey);
        config.setApiKeyPrefix(prefix);
        return config;
    }

    private AuthConfig createHmacConfig(String accessKey) {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("HMAC");
        config.setAccessKey(accessKey);
        return config;
    }

    private AuthConfig createOAuth2Config(String clientId) {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("OAUTH2");
        config.setClientId(clientId);
        return config;
    }
}