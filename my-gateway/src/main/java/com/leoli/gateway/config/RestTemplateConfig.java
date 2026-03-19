package com.leoli.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate configuration.
 *
 * @author leoli
 */
@Configuration
public class RestTemplateConfig {

    @Value("${gateway.health-check.timeout:3000}")
    private int healthCheckTimeoutMs;

    @Value("${gateway.rest-template.connect-timeout:5000}")
    private int connectTimeoutMs;

    @Value("${gateway.rest-template.read-timeout:10000}")
    private int readTimeoutMs;

    /**
     * Main RestTemplate with default timeout.
     * Used for health status sync to admin.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * RestTemplate for health checks with shorter timeout.
     * Health checks should be fast to avoid blocking.
     */
    @Bean(name = "healthCheckRestTemplate")
    public RestTemplate healthCheckRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(healthCheckTimeoutMs);
        factory.setReadTimeout(healthCheckTimeoutMs);
        return new RestTemplate(factory);
    }
}