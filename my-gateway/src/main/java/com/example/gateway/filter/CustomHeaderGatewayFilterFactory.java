package com.example.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom Header Filter Factory
 * 
 * Configuration example:
 * - name: CustomHeader
 *   args:
 *     headers:
 *       X-Request-Id: ${random.uuid}
 *       X-Forwarded-For: client-ip
 *       Authorization: Bearer token123
 * 
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class CustomHeaderGatewayFilterFactory extends AbstractGatewayFilterFactory<CustomHeaderGatewayFilterFactory.Config> {

    public CustomHeaderGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new CustomHeaderGatewayFilter(config);
    }

    @Override
    public String name() {
        return "CustomHeader";
    }

    private static class CustomHeaderGatewayFilter implements GatewayFilter {
        private final Config config;

        public CustomHeaderGatewayFilter(Config config) {
            this.config = config;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            ServerHttpRequest request = exchange.getRequest();
            
            // If no header configuration, pass through directly
            if (config.getHeaders() == null || config.getHeaders().isEmpty()) {
                return chain.filter(exchange);
            }

            try {
                // Create new request builder
                ServerHttpRequest.Builder builder = request.mutate();
                
                // Iterate through configured headers and add to request
                for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
                    String headerName = entry.getKey();
                    String headerValue = entry.getValue();
                    
                    // Support simple variable substitution (optional)
                    String resolvedValue = resolveHeaderValue(headerValue, exchange);
                    
                    log.debug("Adding header: {} = {}", headerName, resolvedValue);
                    builder.header(headerName, resolvedValue);
                }
                
                ServerHttpRequest modifiedRequest = builder.build();
                
                log.info("閴?Added {} custom header(s) to request", config.getHeaders().size());
                
                return chain.filter(exchange.mutate().request(modifiedRequest).build());
                
            } catch (Exception e) {
                log.error("閴?Error adding custom headers", e);
                return chain.filter(exchange);
            }
        }
        
        /**
         * Resolve header value (supports simple placeholder substitution)
         */
        private String resolveHeaderValue(String value, ServerWebExchange exchange) {
            if (value == null || value.isEmpty()) {
                return value;
            }
            
            // Support ${random.uuid} placeholder
            if ("${random.uuid}".equals(value)) {
                return java.util.UUID.randomUUID().toString();
            }
            
            // Support ${client.ip} placeholder
            if ("${client.ip}".equals(value)) {
                return exchange.getRequest().getRemoteAddress() != null 
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() 
                    : "unknown";
            }
            
            // Support ${request.path} placeholder
            if ("${request.path}".equals(value)) {
                return exchange.getRequest().getPath().value();
            }
            
            // Return as-is for other cases
            return value;
        }
    }

    /**
     * Configuration class
     */
    public static class Config {
        /**
         * Custom headers, key is header name, value is header value
         */
        private Map<String, String> headers = new LinkedHashMap<>();

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }
}
