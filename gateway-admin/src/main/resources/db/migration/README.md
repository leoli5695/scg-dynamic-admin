# Database Migration Scripts

## V2__add_cluster_and_instance_stats.sql

This migration adds monitoring and statistics fields to support the enhanced UI design.

### Changes to `kubernetes_clusters` table:

- `node_count` - Number of nodes in the cluster
- `pod_count` - Total number of running pods
- `namespace_count` - Number of active namespaces
- `total_cpu_cores` - Total CPU capacity in cores
- `total_memory_gb` - Total memory capacity in GB

### Changes to `gateway_instances` table:

- `cpu_usage_percent` - Current CPU usage percentage (0-100)
- `memory_usage_mb` - Current memory usage in MB
- `requests_per_second` - Current request rate (RPS)
- `active_connections` - Current active connections count
- `total_requests` - Total requests handled since deployment
- `error_rate_percent` - Error rate percentage (0-100)
- `avg_response_time_ms` - Average response time in milliseconds

## Auto-Migration

The application uses JPA with `ddl-auto: update`, so these fields will be automatically added to the database when the application starts.

If you prefer manual migration, run the SQL script in `V2__add_cluster_and_instance_stats.sql`.

## UI Integration

The new fields are automatically exposed through the existing REST APIs:

- `GET /api/kubernetes/clusters` - Returns cluster list with stats
- `GET /api/kubernetes/clusters/{id}` - Returns cluster details with stats
- `GET /api/instances` - Returns instance list with metrics
- `GET /api/instances/{id}` - Returns instance details with metrics

The frontend components (`ClusterCardPremium`, `StatsCardPremium`) will automatically display these statistics.
