-- ============================================================================
-- Performance Indexes Migration
-- Add composite indexes for frequently queried columns
-- ============================================================================

-- ============================================================================
-- route_auth_bindings table - Auth binding query optimization
-- ============================================================================

-- For findByInstanceIdAndRouteIdAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_binding_instance_route_enabled
ON route_auth_bindings(instance_id, route_id, enabled);

-- For findByPolicyIdAndEnabledTrue and findByPolicyIdAndEnabledTrueAndInstanceId
CREATE INDEX IF NOT EXISTS idx_binding_policy_enabled
ON route_auth_bindings(policy_id, enabled);

-- For findByRouteIdAndEnabledTrueOrderByPriorityDesc
CREATE INDEX IF NOT EXISTS idx_binding_route_enabled_priority
ON route_auth_bindings(route_id, enabled, priority DESC);

-- For findByInstanceId queries
CREATE INDEX IF NOT EXISTS idx_binding_instance
ON route_auth_bindings(instance_id);

-- ============================================================================
-- strategies table - Strategy query optimization
-- ============================================================================

-- For findByStrategyTypeAndScopeAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_strategy_type_scope_enabled
ON strategies(strategy_type, scope, enabled);

-- For findByInstanceIdAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_strategy_instance_enabled
ON strategies(instance_id, enabled);

-- For findByScopeAndInstanceIdAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_strategy_scope_instance_enabled
ON strategies(scope, instance_id, enabled);

-- For findByRouteIdAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_strategy_route_enabled
ON strategies(route_id, enabled);

-- For findByStrategyId lookup
CREATE INDEX IF NOT EXISTS idx_strategy_id
ON strategies(strategy_id);

-- ============================================================================
-- alert_history table - Alert query optimization
-- ============================================================================

-- For findByInstanceIdOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_alert_instance_created
ON alert_history(instance_id, created_at DESC);

-- ============================================================================
-- request_trace table - Trace query optimization
-- ============================================================================

-- For findByInstanceId queries
CREATE INDEX IF NOT EXISTS idx_trace_instance
ON request_trace(instance_id);

-- For findByInstanceIdAndRouteId
CREATE INDEX IF NOT EXISTS idx_trace_instance_route
ON request_trace(instance_id, route_id);

-- For findByInstanceIdAndTimeRange
CREATE INDEX IF NOT EXISTS idx_trace_instance_time
ON request_trace(instance_id, trace_time);

-- ============================================================================
-- auth_policies table - Auth policy query optimization
-- ============================================================================

-- For findByInstanceIdAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_auth_policy_instance_enabled
ON auth_policies(instance_id, enabled);

-- For findByInstanceIdAndAuthType
CREATE INDEX IF NOT EXISTS idx_auth_policy_instance_type
ON auth_policies(instance_id, auth_type);

-- ============================================================================
-- routes table - Route query optimization
-- ============================================================================

-- For findByInstanceIdAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_route_instance_enabled
ON routes(instance_id, enabled);

-- ============================================================================
-- services table - Service query optimization
-- ============================================================================

-- For findByInstanceIdAndEnabledTrue
CREATE INDEX IF NOT EXISTS idx_service_instance_enabled
ON services(instance_id, enabled);

-- ============================================================================
-- gateway_instances table - Instance lookup optimization
-- ============================================================================

-- For findByInstanceId (already unique but make explicit)
CREATE INDEX IF NOT EXISTS idx_instance_id
ON gateway_instances(instance_id);