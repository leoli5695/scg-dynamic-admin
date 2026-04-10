package com.leoli.gateway.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate configuration with timeout settings.
 * <p>
 * Timeout configuration is critical for request replay functionality
 * to prevent long-running requests from blocking the system.
 *
 * @author leoli
 */
@Configuration
@ConfigurationProperties(prefix = "gateway.replay")
public class RestTemplateConfig {

    /**
     * Connection timeout in milliseconds.
     * Default: 5000ms (5 seconds)
     */
    private int connectTimeoutMs = 5000;

    /**
     * Read timeout in milliseconds.
     * Default: 30000ms (30 seconds) - allows for slow backend responses
     */
    private int readTimeoutMs = 30000;

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    // Getter and setter for configuration properties
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
