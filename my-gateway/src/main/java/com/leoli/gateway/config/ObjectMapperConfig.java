package com.leoli.gateway.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Unified ObjectMapper configuration.
 * <p>
 * Provides singleton ObjectMapper and XmlMapper instances
 * for consistent JSON/XML serialization across all components.
 * <p>
 * Benefits:
 * - Reduced memory footprint (single instance)
 * - Consistent configuration
 * - Better performance (no repeated instantiation)
 *
 * @author leoli
 */
@Configuration
public class ObjectMapperConfig {

    /**
     * Primary ObjectMapper bean for JSON operations.
     * <p>
     * Configuration:
     * - Ignore unknown properties during deserialization
     * - Don't fail on empty beans
     * - Don't write dates as timestamps
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Ignore unknown properties - useful for config parsing
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Don't fail on empty beans
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // Don't write dates as timestamps
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    /**
     * XmlMapper bean for XML operations.
     * <p>
     * Used for protocol transformation (JSON <-> XML).
     */
    @Bean
    public XmlMapper xmlMapper() {
        XmlMapper mapper = new XmlMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }
}