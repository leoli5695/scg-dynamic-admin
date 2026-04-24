-- Add export and share features for stress test reports

-- 1. Add ai_analysis_result column to stress_test table
ALTER TABLE stress_test ADD COLUMN ai_analysis_result TEXT;

-- 2. Create stress_test_share table for sharing reports with expiration
CREATE TABLE stress_test_share (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    share_id VARCHAR(36) NOT NULL UNIQUE,
    test_id BIGINT NOT NULL,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    view_count INT DEFAULT 0,
    created_by VARCHAR(100),
    
    CONSTRAINT fk_stress_test_share_test FOREIGN KEY (test_id) REFERENCES stress_test(id) ON DELETE CASCADE
);

-- Indexes for share queries
CREATE INDEX idx_stress_test_share_id ON stress_test_share(share_id);
CREATE INDEX idx_stress_test_share_expires ON stress_test_share(expires_at);
CREATE INDEX idx_stress_test_share_test ON stress_test_share(test_id);