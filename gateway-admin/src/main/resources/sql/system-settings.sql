-- Feature switches: run before starting the upgraded gateway-admin with ddl-auto=validate.
-- Idempotent: preserves existing switches and operator information on repeated execution.
CREATE TABLE IF NOT EXISTS system_settings (
    id BIGINT NOT NULL,
    stress_test_enabled BIT(1) NOT NULL DEFAULT 1,
    middleware_enabled BIT(1) NOT NULL DEFAULT 1,
    plugins_enabled BIT(1) NOT NULL DEFAULT 1,
    kubernetes_enabled BIT(1) NOT NULL DEFAULT 1,
    updated_by VARCHAR(100) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO system_settings (id, stress_test_enabled, middleware_enabled, plugins_enabled, kubernetes_enabled)
VALUES (1, 1, 1, 1, 1)
ON DUPLICATE KEY UPDATE id = system_settings.id;
