-- Apply before deploying the updated gateway-admin (production uses ddl-auto=validate).
-- Existing rows are LEGACY: their original certainty cannot be reconstructed.
ALTER TABLE token_usage_history ADD COLUMN usage_status VARCHAR(20) DEFAULT 'LEGACY';
ALTER TABLE token_usage_history ADD COLUMN settlement_status VARCHAR(20) DEFAULT 'LEGACY';
ALTER TABLE token_usage_history ADD COLUMN provider VARCHAR(64);
ALTER TABLE token_usage_history ADD COLUMN service_id VARCHAR(255);
ALTER TABLE token_usage_history ADD COLUMN charged_tokens BIGINT;
ALTER TABLE token_usage_history ADD COLUMN reserved_tokens BIGINT;
