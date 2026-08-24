package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
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
     * FIX: encryption is performed by the service layer (ApiKeyEncryptor) before save.
     * JPA entities are instantiated by Hibernate, so @Autowired injection on the entity
     * never worked - the previous entity-level encryption was dead code and keys were
     * stored in plaintext. Never decrypt here; services must use ApiKeyEncryptor.
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
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * Set API key. Stores the given value as-is into the persistence field;
     * callers from the service layer must pass an already-encrypted value
     * (see AiAnalysisService.saveConfig). Kept for JSON binding/migration paths
     * that copy the stored (encrypted) value between entities.
     */
    public void setApiKey(String apiKey) {
        this.apiKeyEncrypted = apiKey;
    }
    
    // Legacy getter for backward compatibility (returns stored value, encrypted at rest)
    public String getApiKey() {
        return apiKeyEncrypted;
    }
}