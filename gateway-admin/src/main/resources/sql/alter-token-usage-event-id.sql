-- Apply after alter-token-usage-outcome.sql, before deploying reliable usage reporting.
-- NULL preserves historical rows and legacy clients without event IDs.
ALTER TABLE token_usage_history ADD COLUMN event_id VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_token_usage_event_id ON token_usage_history (event_id);
