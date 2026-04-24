-- Add Jaeger and Prometheus server address fields for gateway instances
-- Supports custom addresses for cross-cluster scenarios

ALTER TABLE gateway_instances ADD COLUMN jaeger_server_addr VARCHAR(255) COMMENT 'Custom Jaeger OTLP address (optional, for distributed tracing)';
ALTER TABLE gateway_instances ADD COLUMN prometheus_server_addr VARCHAR(255) COMMENT 'Custom Prometheus push address (optional, for metrics)';