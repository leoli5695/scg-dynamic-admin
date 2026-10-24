package com.leoli.gateway.admin.config;

import com.leoli.gateway.admin.filter.InternalApiTokenFilter;
import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
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
    public RestTemplate restTemplate(GatewayAdminProperties properties) {
        // FIX (credential exfiltration via redirect): the interceptor below attaches the shared
        // X-Internal-Token based on the request PATH alone, with no check on the target host.
        // HttpURLConnection re-sends the original request headers when it follows a 30x, so a
        // target that passed validation on the first hop could bounce the token to any host it
        // liked. Redirects are disabled here rather than re-validating every hop: none of the
        // internal endpoints this template talks to (filter-chain stats, circuit-breaker control,
        // health sync) legitimately redirect, and a redirect response is worth surfacing as-is.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        RestTemplate template = new RestTemplate(factory);

        // FIX: gateway instances now protect /internal/** endpoints with the shared
        // X-Internal-Token. Attach the token to all outbound calls targeting those
        // endpoints (filter-chain stats, circuit breaker control, etc.).
        String internalToken = properties.getSecurity().getInternalToken();
        if (internalToken != null && !internalToken.isEmpty()) {
            ClientHttpRequestInterceptor tokenInterceptor = (request, body, execution) -> {
                if (request.getURI().getPath().startsWith("/internal/")) {
                    request.getHeaders().set(InternalApiTokenFilter.TOKEN_HEADER, internalToken);
                }
                return execution.execute(request, body);
            };
            template.getInterceptors().add(tokenInterceptor);
        }
        return template;
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
