-- Apply after alter-token-usage-reconciliation.sql, before upgrading admin and gateways.
ALTER TABLE token_usage_history ADD COLUMN quota_ledger_id VARCHAR(36) NULL;
ALTER TABLE token_usage_history ADD COLUMN quota_ledger_state VARCHAR(20) NULL;
