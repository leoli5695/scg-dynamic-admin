package com.leoli.gateway.constants;

/**
 * Gateway configuration constants.
 * <p>
 * Defines all Nacos config data IDs and groups used by the gateway.
 * Interface is used so all fields are implicitly public static final.
 * <p>
 * Config Structure in Nacos:
 * <pre>
 * DEFAULT_GROUP/
 * ├── config.gateway.metadata.routes-index          # Route ID list
 * ├── config.gateway.route-{routeId}                # Route definition
 * ├── config.gateway.metadata.strategies-index      # Strategy ID list
 * ├── config.gateway.strategy-{strategyId}          # Strategy definition
 * ├── config.gateway.metadata.services-index        # Service ID list
 * ├── config.gateway.service-{serviceId}            # Service definition
 * ├── config.gateway.metadata.auth-policies-index   # Auth policy ID list
 * ├── config.gateway.auth-policy-{policyId}         # Auth policy definition
 * ├── config.gateway.auth-routes-{routeId}          # Auth binding per route
 * └── config.gateway.access-log                     # Access log config
 * </pre>
 *
 * @author leoli
 */
public interface GatewayConfigConstants {

    // ============================================================
    // Config Group
    // ============================================================

    /**
     * Default Nacos config group.
     */
    String GROUP = "DEFAULT_GROUP";

    // ============================================================
    // Route Configuration
    // ============================================================

    /**
     * Route config data ID prefix.
     * Full data ID: config.gateway.route-{routeId}
     */
    String ROUTE_PREFIX = "config.gateway.route-";

    /**
     * Routes index data ID.
     * Contains JSON array of all route IDs.
     */
    String ROUTES_INDEX = "config.gateway.metadata.routes-index";

    // ============================================================
    // Strategy Configuration
    // ============================================================

    /**
     * Strategy config data ID prefix.
     * Full data ID: config.gateway.strategy-{strategyId}
     */
    String STRATEGY_PREFIX = "config.gateway.strategy-";

    /**
     * Strategies index data ID.
     * Contains JSON array of all strategy IDs.
     */
    String STRATEGIES_INDEX = "config.gateway.metadata.strategies-index";

    // ============================================================
    // Service Configuration
    // ============================================================

    /**
     * Service config data ID prefix.
     * Full data ID: config.gateway.service-{serviceId}
     */
    String SERVICE_PREFIX = "config.gateway.service-";

    /**
     * Services index data ID.
     * Contains JSON array of all service IDs.
     */
    String SERVICES_INDEX = "config.gateway.metadata.services-index";

    // ============================================================
    // Authentication Configuration
    // ============================================================

    /**
     * Auth policy config data ID prefix.
     * Full data ID: config.gateway.auth-policy-{policyId}
     */
    String AUTH_POLICY_PREFIX = "config.gateway.auth-policy-";

    /**
     * Auth policies index data ID.
     * Contains JSON array of all auth policy IDs.
     */
    String AUTH_POLICIES_INDEX = "config.gateway.metadata.auth-policies-index";

    /**
     * Auth routes binding data ID prefix.
     * Full data ID: config.gateway.auth-routes-{routeId}
     * Contains array of policy IDs bound to a route.
     */
    String AUTH_ROUTES_PREFIX = "config.gateway.auth-routes-";

    // ============================================================
    // Global Configuration
    // ============================================================

    /**
     * Access log configuration data ID.
     */
    String ACCESS_LOG_CONFIG = "config.gateway.access-log";

    // ============================================================
    // Metadata Prefix
    // ============================================================

    /**
     * Metadata config data ID prefix.
     * Used for index files that list all IDs of a type.
     */
    String METADATA_PREFIX = "config.gateway.metadata.";

    // ============================================================
    // Utility Methods (default methods)
    // ============================================================

    /**
     * Build route config data ID.
     */
    static String routeDataId(String routeId) {
        return ROUTE_PREFIX + routeId;
    }

    /**
     * Build strategy config data ID.
     */
    static String strategyDataId(String strategyId) {
        return STRATEGY_PREFIX + strategyId;
    }

    /**
     * Build service config data ID.
     */
    static String serviceDataId(String serviceId) {
        return SERVICE_PREFIX + serviceId;
    }

    /**
     * Build auth policy config data ID.
     */
    static String authPolicyDataId(String policyId) {
        return AUTH_POLICY_PREFIX + policyId;
    }

    /**
     * Build auth routes binding data ID.
     */
    static String authRoutesDataId(String routeId) {
        return AUTH_ROUTES_PREFIX + routeId;
    }
}