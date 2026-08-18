package com.leoli.license.client.config;

import com.leoli.license.client.validator.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * License Client Auto Configuration
 * <p>
 * Automatically configures License validation components when:
 * - license.client.enabled=true (default)
 * - LicenseProperties is available
 * <p>
 * Provides:
 * - LicenseValidator: Offline RSA signature verification
 * - LicenseStore: In-memory license storage
 * - FeatureGatekeeper: Feature authorization checker
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "license.client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LicenseProperties.class)
public class LicenseAutoConfiguration {

    /**
     * Create LicenseValidator bean
     */
    @Bean
    @ConditionalOnMissingBean
    public LicenseValidator licenseValidator() {
        return new LicenseValidator();
    }

    /**
     * Create LicenseStore bean
     */
    @Bean
    @ConditionalOnMissingBean
    public LicenseStore licenseStore(LicenseValidator validator, LicenseProperties properties) {
        return new LicenseStore(validator, properties);
    }

    /**
     * Create FeatureGatekeeper bean
     */
    @Bean
    @ConditionalOnMissingBean
    public FeatureGatekeeper featureGatekeeper(LicenseStore store, LicenseProperties properties) {
        return new FeatureGatekeeper(store, properties);
    }
}