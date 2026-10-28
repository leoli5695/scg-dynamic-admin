-- Apply before deploying the updated gateway-admin. This script is needed even where Hibernate
-- auto-creates columns: the entity maps usage_status/settlement_status without a columnDefinition,
-- so ddl-auto=update adds them as plain nullable VARCHAR(20) and the DEFAULT 'LEGACY' below never
-- lands - historical rows read back NULL instead of LEGACY. (application-prod.yml pins validate,
-- but a deployed container can override that via SPRING_JPA_HIBERNATE_DDL_AUTO: check the env.)
-- Existing rows are LEGACY: their original certainty cannot be reconstructed.
ALTER TABLE token_usage_history ADD COLUMN usage_status VARCHAR(20) DEFAULT 'LEGACY';
ALTER TABLE token_usage_history ADD COLUMN settlement_status VARCHAR(20) DEFAULT 'LEGACY';
ALTER TABLE token_usage_history ADD COLUMN provider VARCHAR(64);
ALTER TABLE token_usage_history ADD COLUMN service_id VARCHAR(255);
ALTER TABLE token_usage_history ADD COLUMN charged_tokens BIGINT;
ALTER TABLE token_usage_history ADD COLUMN reserved_tokens BIGINT;
