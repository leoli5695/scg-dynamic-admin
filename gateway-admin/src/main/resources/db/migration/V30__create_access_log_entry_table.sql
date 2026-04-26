-- Access Log Entry table for storing historical access logs
-- Supports real-time log collection from Fluent Bit and historical queries

CREATE TABLE access_log_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    -- Instance isolation
    instance_id VARCHAR(36) NOT NULL COMMENT 'Gateway instance ID',
    
    -- Request identification
    trace_id VARCHAR(64) COMMENT 'Distributed trace ID',
    request_id VARCHAR(64) COMMENT 'Unique request ID',
    
    -- Routing information
    route_id VARCHAR(100) COMMENT 'Route ID',
    service_id VARCHAR(100) COMMENT 'Target service ID',
    
    -- Request details
    method VARCHAR(10) NOT NULL COMMENT 'HTTP method (GET, POST, PUT, DELETE)',
    path VARCHAR(500) NOT NULL COMMENT 'Request path',
    query_string VARCHAR(1000) COMMENT 'Query string',
    
    -- Client information
    client_ip VARCHAR(50) COMMENT 'Client IP address',
    user_agent VARCHAR(500) COMMENT 'User agent',
    
    -- Response details
    status_code INT NOT NULL COMMENT 'HTTP status code',
    duration_ms BIGINT NOT NULL COMMENT 'Request duration in milliseconds',
    
    -- Authentication information
    auth_type VARCHAR(50) COMMENT 'Authentication type (JWT, BASIC, etc.)',
    auth_policy VARCHAR(100) COMMENT 'Authentication policy name',
    auth_user VARCHAR(200) COMMENT 'Authenticated user',
    
    -- Error information
    error_message TEXT COMMENT 'Error message if request failed',
    
    -- Timestamp (from log entry, not DB insert time)
    log_timestamp DATETIME(3) NOT NULL COMMENT 'Original log timestamp',
    
    -- DB insert time
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'DB insert timestamp',
    
    -- Indexes for common queries
    INDEX idx_log_time_instance (log_timestamp, instance_id),
    INDEX idx_log_instance_time (instance_id, log_timestamp),
    INDEX idx_log_trace_id (trace_id),
    INDEX idx_log_route_id (route_id),
    INDEX idx_log_service_id (service_id),
    INDEX idx_log_status_code (status_code, log_timestamp),
    INDEX idx_log_method (method, log_timestamp),
    INDEX idx_log_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Historical access log entries';

-- Add partitioning for large-scale log storage (optional, can be enabled later)
-- ALTER TABLE access_log_entries PARTITION BY RANGE (TO_DAYS(log_timestamp)) (
--     PARTITION p_default VALUES LESS THAN MAXVALUE
-- );