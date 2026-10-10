package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.entity.ProviderCredentialEntity;
import com.leoli.gateway.admin.repository.ProviderCredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD for provider API credentials.
 * <p>
 * Keys are encrypted on write and masked on every external read; the plaintext is
 * only ever returned by {@link #resolveKey(String)}, which backs the token-guarded
 * internal endpoint the gateway pods call at runtime.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderCredentialService {

    private final ProviderCredentialRepository repository;
    private final CredentialCryptoService crypto;
    private final AuditLogService auditLogService;

    /** Masked view of all credentials (no ciphertext, no plaintext). */
    public List<Map<String, Object>> listMasked() {
        return repository.findAll().stream().map(this::toView).collect(Collectors.toList());
    }

    public Map<String, Object> getMasked(String credentialRef) {
        ProviderCredentialEntity entity = require(credentialRef);
        return toView(entity);
    }

    @Transactional
    public Map<String, Object> create(String credentialRef, String name, String provider,
                                      String apiKey, String tenantId, String operator) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }

        String ref = (credentialRef == null || credentialRef.isBlank())
                ? UUID.randomUUID().toString()
                : credentialRef.trim();
        if (repository.existsByCredentialRef(ref)) {
            throw new IllegalArgumentException("credentialRef already exists: " + ref);
        }

        ProviderCredentialEntity entity = new ProviderCredentialEntity();
        entity.setCredentialRef(ref);
        entity.setName(name.trim());
        entity.setProvider(provider.trim());
        entity.setEncryptedKey(crypto.encrypt(apiKey));
        entity.setKeyHint(CredentialCryptoService.mask(apiKey));
        entity.setTenantId(tenantId);
        entity.setEnabled(true);
        ProviderCredentialEntity saved = repository.save(entity);

        auditLogService.recordAuditLog(operator, "CREATE", "PROVIDER_CREDENTIAL", ref,
                "Created provider credential: provider=" + provider, null);
        log.info("Created provider credential {} (provider={})", ref, provider);
        return toView(saved);
    }

    @Transactional
    public Map<String, Object> update(String credentialRef, String name, String apiKey,
                                      Boolean enabled, String tenantId, String operator) {
        ProviderCredentialEntity entity = require(credentialRef);

        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        if (apiKey != null && !apiKey.isBlank()) {
            entity.setEncryptedKey(crypto.encrypt(apiKey));
            entity.setKeyHint(CredentialCryptoService.mask(apiKey));
        }
        if (enabled != null) {
            entity.setEnabled(enabled);
        }
        if (tenantId != null) {
            entity.setTenantId(tenantId);
        }
        ProviderCredentialEntity saved = repository.save(entity);

        auditLogService.recordAuditLog(operator, "UPDATE", "PROVIDER_CREDENTIAL", credentialRef,
                "Updated provider credential (rotated=" + (apiKey != null && !apiKey.isBlank()) + ")", null);
        return toView(saved);
    }

    @Transactional
    public void delete(String credentialRef, String operator) {
        ProviderCredentialEntity entity = require(credentialRef);
        repository.delete(entity);
        auditLogService.recordAuditLog(operator, "DELETE", "PROVIDER_CREDENTIAL", credentialRef,
                "Deleted provider credential", null);
        log.info("Deleted provider credential {}", credentialRef);
    }

    /**
     * INTERNAL ONLY: decrypt and return the plaintext key for a credential ref.
     * Backed by the X-Internal-Token-guarded endpoint the gateway calls at runtime.
     */
    public String resolveKey(String credentialRef) {
        ProviderCredentialEntity entity = require(credentialRef);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalArgumentException("Credential is disabled: " + credentialRef);
        }
        return crypto.decrypt(entity.getEncryptedKey());
    }

    private ProviderCredentialEntity require(String credentialRef) {
        return repository.findByCredentialRef(credentialRef)
                .orElseThrow(() -> new IllegalArgumentException("Credential not found: " + credentialRef));
    }

    private Map<String, Object> toView(ProviderCredentialEntity e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("credentialRef", e.getCredentialRef());
        view.put("name", e.getName());
        view.put("provider", e.getProvider());
        view.put("keyHint", e.getKeyHint());
        view.put("tenantId", e.getTenantId());
        view.put("enabled", e.getEnabled());
        view.put("createdAt", e.getCreatedAt());
        view.put("updatedAt", e.getUpdatedAt());
        return view;
    }
}
