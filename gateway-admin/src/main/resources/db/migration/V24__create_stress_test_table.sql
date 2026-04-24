-- V24: Create stress_test table for load testing functionality
-- Drop existing table if exists and recreate with proper schema

DROP TABLE IF EXISTS stress_test;

CREATE TABLE stress_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(100) NOT NULL,

    -- Test Configuration
    test_name VARCHAR(100),
    target_url VARCHAR(500) NOT NULL,
    method VARCHAR(10) DEFAULT 'GET',
    headers TEXT,
    body TEXT,

    -- Load Parameters
    concurrent_users INTEGER NOT NULL DEFAULT 10,
    total_requests INTEGER,
    duration_seconds INTEGER,
    ramp_up_seconds INTEGER DEFAULT 0,
    request_timeout_seconds INTEGER DEFAULT 30,
    target_qps INTEGER,

    -- Test Status
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    start_time TIMESTAMP NULL,
    end_time TIMESTAMP NULL,

    -- Results - Basic Counts
    actual_requests INTEGER DEFAULT 0,
    successful_requests INTEGER DEFAULT 0,
    failed_requests INTEGER DEFAULT 0,
    error_rate DECIMAL(5, 2) DEFAULT 0.0,

    -- Results - Response Time Statistics (milliseconds)
    min_response_time_ms DECIMAL(10, 2) DEFAULT 0.0,
    max_response_time_ms DECIMAL(10, 2) DEFAULT 0.0,
    avg_response_time_ms DECIMAL(10, 2) DEFAULT 0.0,
    p50_response_time_ms DECIMAL(10, 2) DEFAULT 0.0,
    p90_response_time_ms DECIMAL(10, 2) DEFAULT 0.0,
    p95_response_time_ms DECIMAL(10, 2) DEFAULT 0.0,
    p99_response_time_ms DECIMAL(10, 2) DEFAULT 0.0,

    -- Results - Throughput
    requests_per_second DECIMAL(10, 2) DEFAULT 0.0,
    throughput_kbps DECIMAL(10, 2) DEFAULT 0.0,

    -- Results - Distributions (JSON format)
    response_time_distribution TEXT,
    error_distribution TEXT,

    -- Metadata
    created_by VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for common queries
CREATE INDEX idx_stress_test_instance ON stress_test(instance_id);
CREATE INDEX idx_stress_test_status ON stress_test(status);
CREATE INDEX idx_stress_test_created_at ON stress_test(created_at DESC);
CREATE INDEX idx_stress_test_instance_created ON stress_test(instance_id, created_at DESC);
