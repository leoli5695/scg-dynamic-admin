-- Apply after alter-token-usage-event-id.sql, before upgrading admin and gateways.
ALTER TABLE token_usage_history ADD COLUMN quota_month VARCHAR(6) NULL;
ALTER TABLE token_usage_history ADD COLUMN quota_day VARCHAR(8) NULL;
ALTER TABLE token_usage_history ADD COLUMN quota_period VARCHAR(10) NULL;

CREATE TABLE token_usage_reconciliation (
    id VARCHAR(36) PRIMARY KEY,
    usage_id BIGINT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    quota_month VARCHAR(6),
    quota_day VARCHAR(8),
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    total_tokens INTEGER NOT NULL,
    charged_tokens BIGINT NOT NULL,
    monthly_delta BIGINT NOT NULL,
    daily_delta BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    original_usage TEXT NOT NULL,
    last_error VARCHAR(1000),
    attempts INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    applied_at TIMESTAMP NULL,
    CONSTRAINT uk_usage_reconciliation UNIQUE (usage_id)
);
CREATE INDEX idx_usage_reconciliation_status ON token_usage_reconciliation (status, created_at);
