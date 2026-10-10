package com.leoli.gateway.admin.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Provider API credential for the AI proxy.
 * <p>
 * The upstream LLM provider key is stored AES-GCM encrypted ({@link #encryptedKey});
 * plaintext never touches the DB or an external API response. {@link #credentialRef}
 * is the stable id referenced by AiProxyConfig.providerApiKeyRefs.
 * <p>
 * dev(ddl-auto=update) 自动建表；prod(validate) 需执行 {@code resources/sql/provider-credentials.sql}。
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "provider_credentials", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"credential_ref"})
})
public class ProviderCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable reference used by gateway strategy config (never the numeric id). */
    @Column(name = "credential_ref", length = 64, nullable = false)
    private String credentialRef;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    /** Provider id, e.g. deepseek / qwen / zhipu / moonshot / openai / anthropic / custom. */
    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    /** AES-GCM ciphertext, base64(iv || ciphertext). Never plaintext. */
    @Column(name = "encrypted_key", length = 1024, nullable = false)
    private String encryptedKey;

    /** Masked tail for UI display, e.g. "****abcd". */
    @Column(name = "key_hint", length = 16)
    private String keyHint;

    /** Optional tenant scoping. */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
