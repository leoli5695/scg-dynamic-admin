package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leoli.gateway.admin.util.ApiKeyEncryptor;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_config")
public class AiConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "provider", nullable = false, unique = true, length = 50)
    private String provider;
    
    @Column(name = "provider_name", length = 100)
    private String providerName;
    
    @Column(name = "region", nullable = false, length = 20)
    private String region = "DOMESTIC";
    
    @Column(name = "model", length = 100)
    private String model;
    
    /**
     * Encrypted API key stored in database.
     * Use getApiKeyForUse() to decrypt, getApiKeyMasked() for display.
     */
    @Column(name = "api_key", length = 500)
    @JsonIgnore  // Never expose raw API key in JSON responses
    private String apiKeyEncrypted;
    
    @Column(name = "base_url", length = 255)
    private String baseUrl;
    
    @Column(name = "is_valid")
    private Boolean isValid = false;
    
    @Column(name = "last_validated_at")
    private LocalDateTime lastValidatedAt;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // Transient field for API key encryption/decryption (injected via setter)
    @Transient
    private ApiKeyEncryptor apiKeyEncryptor;
    
    @Autowired
    public void setApiKeyEncryptor(ApiKeyEncryptor apiKeyEncryptor) {
        this.apiKeyEncryptor = apiKeyEncryptor;
    }
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Encrypt API key before saving if not already encrypted
        encryptApiKeyIfNeeded();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        encryptApiKeyIfNeeded();
    }
    
    /**
     * Encrypt API key if it appears to be plaintext.
     */
    private void encryptApiKeyIfNeeded() {
        if (apiKeyEncrypted != null && !apiKeyEncrypted.isEmpty() && apiKeyEncryptor != null) {
            // Check if already encrypted (encrypted values are longer and contain Base64 chars)
            // Simple heuristic: if it looks like a plaintext API key, encrypt it
            if (apiKeyEncrypted.startsWith("sk-") || apiKeyEncrypted.startsWith("AIza") ||
                apiKeyEncrypted.startsWith("Bearer ") || apiKeyEncrypted.length() < 50) {
                apiKeyEncrypted = apiKeyEncryptor.encrypt(apiKeyEncrypted);
            }
        }
    }
    
    /**
     * Get decrypted API key for actual API calls.
     */
    public String getApiKeyForUse() {
        if (apiKeyEncrypted == null || apiKeyEncrypted.isEmpty()) {
            return null;
        }
        if (apiKeyEncryptor == null) {
            return apiKeyEncrypted;  // Fallback if encryptor not injected
        }
        return apiKeyEncryptor.decrypt(apiKeyEncrypted);
    }
    
    /**
     * Get masked API key for display in UI (never expose full key).
     */
    public String getApiKeyMasked() {
        String decrypted = getApiKeyForUse();
        if (decrypted == null || decrypted.isEmpty()) {
            return "";
        }
        if (apiKeyEncryptor != null) {
            return apiKeyEncryptor.maskForDisplay(decrypted);
        }
        // Basic masking fallback
        if (decrypted.length() <= 8) {
            return "***";
        }
        return decrypted.substring(0, 3) + "..." + decrypted.substring(decrypted.length() - 4);
    }
    
    /**
     * Set API key (encrypts before storing).
     */
    public void setApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            this.apiKeyEncrypted = null;
            return;
        }
        if (apiKeyEncryptor != null) {
            this.apiKeyEncrypted = apiKeyEncryptor.encrypt(apiKey);
        } else {
            // Store as-is if encryptor not available (will be encrypted on persist)
            this.apiKeyEncrypted = apiKey;
        }
    }
    
    // Legacy getter for backward compatibility (returns encrypted value)
    public String getApiKey() {
        return apiKeyEncrypted;
    }
}