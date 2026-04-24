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

## V25__update_stress_test_analysis_prompt.sql

This migration updates the AI analysis prompt for stress test results.

### Changes to `ai_copilot_prompts` table:

- Updates `stress_test_analysis` prompt with enhanced analysis capabilities
- Adds performance bottleneck detection logic
- Includes optimization recommendations template
- Supports comparison with historical test results

## V26__add_stress_test_export_features.sql

This migration adds export and share features for stress test reports.

### Changes to `stress_test` table:

- `ai_analysis_result` - Stores AI analysis results (TEXT field)

### New `stress_test_share` table:

- `id` - Primary key (BIGINT AUTO_INCREMENT)
- `share_id` - Unique identifier for shareable URL (VARCHAR(36))
- `test_id` - Foreign key to stress_test table (BIGINT)
- `expires_at` - Expiration timestamp (DATETIME, NULL = never expires)
- `created_at` - Creation timestamp (DATETIME)
- `view_count` - Number of times shared report was viewed (INT)
- `created_by` - User who created the share link (VARCHAR(100))

### Indexes:

- `idx_stress_test_share_id` - Index on share_id for fast lookup
- `idx_stress_test_share_expires` - Index on expires_at for expiration queries
- `idx_stress_test_share_test` - Index on test_id for test relationship queries

## Auto-Migration

The application uses JPA with `ddl-auto: update`, so these fields will be automatically added to the database when the application starts.

If you prefer manual migration, run the SQL scripts in order:
1. V2__add_cluster_and_instance_stats.sql
2. V25__update_stress_test_analysis_prompt.sql
3. V26__add_stress_test_export_features.sql

## UI Integration

The new fields are automatically exposed through the existing REST APIs:

- `GET /api/kubernetes/clusters` - Returns cluster list with stats
- `GET /api/kubernetes/clusters/{id}` - Returns cluster details with stats
- `GET /api/instances` - Returns instance list with metrics
- `GET /api/instances/{id}` - Returns instance details with metrics
- `GET /api/stress-test/{testId}/export` - Export test results (PDF/Excel/JSON/Markdown)
- `POST /api/stress-test/{testId}/share` - Create shareable link
- `GET /api/stress-test/share/{shareId}` - Access shared test results

The frontend components (`ClusterCardPremium`, `StatsCardPremium`, `StressTestSharePage`) will automatically display these statistics.
