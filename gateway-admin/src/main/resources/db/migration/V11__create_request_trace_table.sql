-- ============================================================================
-- Request Trace Table Migration
-- Create request_trace table for traffic topology and request replay
-- ============================================================================

CREATE TABLE IF NOT EXISTS request_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(36) NOT NULL COMMENT 'Unique trace identifier (UUID)',
    instance_id VARCHAR(36) COMMENT 'Gateway instance ID for isolation',

    -- Request info
    method VARCHAR(10) NOT NULL COMMENT 'HTTP method (GET, POST, PUT, DELETE, etc)',
    uri VARCHAR(500) NOT NULL COMMENT 'Request URI/path',
    host VARCHAR(255) COMMENT 'Host header',
    route_id VARCHAR(36) COMMENT 'Matched route ID',
    service_id VARCHAR(100) COMMENT 'Target service ID',

    -- Client info
    client_ip VARCHAR(50) COMMENT 'Client IP address',
    user_agent VARCHAR(500) COMMENT 'User agent header',

    -- Response info
    status_code INT COMMENT 'HTTP response status code',
    response_size BIGINT COMMENT 'Response body size in bytes',

    -- Timing
    latency_ms BIGINT COMMENT 'Request latency in milliseconds',
    trace_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Time when trace was recorded',

    -- Error tracking
    error_type VARCHAR(50) COMMENT 'Error type if request failed',
    error_message TEXT COMMENT 'Error message if request failed',

    -- Metadata
    request_headers TEXT COMMENT 'Request headers (JSON)',
    response_headers TEXT COMMENT 'Response headers (JSON)',
    request_body TEXT COMMENT 'Request body (for replay)',

    -- Replay support
    replay_count INT DEFAULT 0 COMMENT 'Number of times this request has been replayed',

    -- Timestamps
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    -- Indexes for common queries
    INDEX idx_trace_instance (instance_id),
    INDEX idx_trace_time (trace_time),
    INDEX idx_trace_route (route_id),
    INDEX idx_trace_client (client_ip),
    INDEX idx_trace_status (status_code),
    INDEX idx_trace_instance_time (instance_id, trace_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Request trace data for topology and replay';