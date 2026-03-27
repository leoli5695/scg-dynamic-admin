package com.leoli.gateway.admin.model;

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
    
    @Column(name = "api_key", length = 500)
    private String apiKey;
    
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
}