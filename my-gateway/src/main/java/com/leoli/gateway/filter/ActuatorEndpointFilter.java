package com.leoli.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFilter that handles actuator endpoint requests on the main gateway port.
 * <p>
 * Since actuator endpoints are configured on a separate management port (8081),
 * this filter intercepts requests to /actuator/** on the main port (80) and
 * either forwards them or returns a helpful message.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActuatorEndpointFilter implements WebFilter {

    @Value("${management.server.port:8081}")
    private int managementPort;

    @Value("${server.port:80}")
    private int serverPort;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Check if this is an actuator request
        if (path.startsWith("/actuator")) {
            // Check if this is already on the management port
            int localPort = exchange.getRequest().getLocalAddress() != null
                    ? exchange.getRequest().getLocalAddress().getPort()
                    : serverPort;

            if (localPort == managementPort) {
                // Request is on management port, let it pass through
                return chain.filter(exchange);
            }

            // Request is on main gateway port, return helpful response
            log.debug("Actuator request on main port, redirecting to management port. Path: {}", path);
            return handleActuatorOnMainPort(exchange, path);
        }

        return chain.filter(exchange);
    }

    /**
     * Handle actuator requests on the main gateway port.
     * Returns a JSON response with the correct management port URL.
     */
    private Mono<Void> handleActuatorOnMainPort(ServerWebExchange exchange, String path) {
        ServerHttpResponse response = exchange.getResponse();

        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String host = exchange.getRequest().getHeaders().getHost() != null
                ? exchange.getRequest().getHeaders().getHost().getHostString()
                : "localhost";

        String correctUrl = String.format("http://%s:%d%s", host, managementPort, path);

        String body = String.format(
                "{\"status\":\"redirect\",\"message\":\"Actuator endpoints are available on the management port\"," +
                        "\"correctUrl\":\"%s\",\"managementPort\":%d}",
                correctUrl, managementPort
        );

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }
}