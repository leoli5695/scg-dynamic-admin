-- AI 代理 provider 凭据表（prod ddl-auto=validate 需手动执行；dev=update 自动建）
-- 对应实体: com.leoli.gateway.admin.entity.ProviderCredentialEntity
-- encrypted_key 存 AES-GCM 密文 base64(iv || ciphertext)，明文只经内部 API 下发给网关实例。
CREATE TABLE IF NOT EXISTS provider_credentials (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    credential_ref VARCHAR(64)   NOT NULL COMMENT '策略配置引用的稳定标识，非数字 id',
    name           VARCHAR(128)  NOT NULL,
    provider       VARCHAR(32)   NOT NULL COMMENT 'deepseek/qwen/zhipu/moonshot/openai/anthropic/custom',
    encrypted_key  VARCHAR(1024) NOT NULL COMMENT 'AES-GCM 密文 base64(iv||ciphertext)',
    key_hint       VARCHAR(16)   NULL COMMENT 'UI 展示用掩码尾串，如 ****abcd',
    tenant_id      VARCHAR(64)   NULL,
    enabled        TINYINT(1)    NOT NULL DEFAULT 1,
    created_at     DATETIME      NULL,
    updated_at     DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pc_credential_ref (credential_ref),
    KEY idx_pc_provider (provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 代理 Provider API 凭据(密文托管)';
