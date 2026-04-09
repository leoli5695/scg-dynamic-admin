-- ============================================================================
-- Add statistics fields to kubernetes_clusters table
-- ============================================================================

ALTER TABLE kubernetes_clusters 
ADD COLUMN node_count INTEGER DEFAULT 0;

ALTER TABLE kubernetes_clusters 
ADD COLUMN pod_count INTEGER DEFAULT 0;

ALTER TABLE kubernetes_clusters 
ADD COLUMN namespace_count INTEGER DEFAULT 0;

ALTER TABLE kubernetes_clusters 
ADD COLUMN total_cpu_cores DOUBLE PRECISION DEFAULT 0.0;

ALTER TABLE kubernetes_clusters 
ADD COLUMN total_memory_gb DOUBLE PRECISION DEFAULT 0.0;

-- ============================================================================
-- Add monitoring fields to gateway_instances table
-- ============================================================================

ALTER TABLE gateway_instances 
ADD COLUMN cpu_usage_percent DOUBLE PRECISION DEFAULT 0.0;

ALTER TABLE gateway_instances 
ADD COLUMN memory_usage_mb DOUBLE PRECISION DEFAULT 0.0;

ALTER TABLE gateway_instances 
ADD COLUMN requests_per_second DOUBLE PRECISION DEFAULT 0.0;

ALTER TABLE gateway_instances 
ADD COLUMN active_connections INTEGER DEFAULT 0;

ALTER TABLE gateway_instances 
ADD COLUMN total_requests BIGINT DEFAULT 0;

ALTER TABLE gateway_instances 
ADD COLUMN error_rate_percent DOUBLE PRECISION DEFAULT 0.0;

ALTER TABLE gateway_instances 
ADD COLUMN avg_response_time_ms DOUBLE PRECISION DEFAULT 0.0;

-- Add comments for documentation
COMMENT ON COLUMN kubernetes_clusters.node_count IS 'Number of nodes in the cluster';
COMMENT ON COLUMN kubernetes_clusters.pod_count IS 'Total number of running pods';
COMMENT ON COLUMN kubernetes_clusters.namespace_count IS 'Number of active namespaces';
COMMENT ON COLUMN kubernetes_clusters.total_cpu_cores IS 'Total CPU capacity in cores';
COMMENT ON COLUMN kubernetes_clusters.total_memory_gb IS 'Total memory capacity in GB';

COMMENT ON COLUMN gateway_instances.cpu_usage_percent IS 'Current CPU usage percentage (0-100)';
COMMENT ON COLUMN gateway_instances.memory_usage_mb IS 'Current memory usage in MB';
COMMENT ON COLUMN gateway_instances.requests_per_second IS 'Current request rate (RPS)';
COMMENT ON COLUMN gateway_instances.active_connections IS 'Current active connections count';
COMMENT ON COLUMN gateway_instances.total_requests IS 'Total requests handled since deployment';
COMMENT ON COLUMN gateway_instances.error_rate_percent IS 'Error rate percentage (0-100)';
COMMENT ON COLUMN gateway_instances.avg_response_time_ms IS 'Average response time in milliseconds';
