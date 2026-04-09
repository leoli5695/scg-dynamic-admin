package com.leoli.gateway.config;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * WebClient configuration with optimized connection pooling.
 * Provides a shared WebClient instance with connection pool settings
 * for efficient HTTP calls to admin service and OAuth2 servers.
 *
 * @author leoli
 */
@Slf4j
@Configuration
public class WebClientConfig {

    @Value("${gateway.webclient.max-connections:100}")
    private int maxConnections;

    @Value("${gateway.webclient.max-connections-per-host:20}")
    private int maxConnectionsPerHost;

    @Value("${gateway.webclient.pending-acquire-timeout:30000}")
    private int pendingAcquireTimeoutMs;

    @Value("${gateway.webclient.pending-acquire-max-count:500}")
    private int pendingAcquireMaxCount;

    @Value("${gateway.webclient.idle-timeout:60000}")
    private int idleTimeoutMs;

    @Value("${gateway.webclient.max-life-time:300000}")
    private int maxLifeTimeMs;

    @Value("${gateway.webclient.connect-timeout:5000}")
    private int connectTimeoutMs;

    @Value("${gateway.webclient.response-timeout:30000}")
    private int responseTimeoutMs;

    /**
     * Create a ConnectionProvider with optimized settings.
     * - maxConnections: total maximum connections in the pool
     * - maxConnectionsPerHost: maximum connections per host
     * - pendingAcquireTimeout: timeout for acquiring a connection from pool
     * - idleTimeout: time before idle connections are evicted
     * - maxLifeTime: maximum lifetime of a connection in pool
     */
    @Bean
    public ConnectionProvider connectionProvider() {
        ConnectionProvider provider = ConnectionProvider.builder("gateway-webclient-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeoutMs))
                .pendingAcquireMaxCount(pendingAcquireMaxCount)
                .maxIdleTime(Duration.ofMillis(idleTimeoutMs))
                .maxLifeTime(Duration.ofMillis(maxLifeTimeMs))
                .build();

        log.info("WebClient ConnectionPool configured: maxConnections={}, maxConnectionsPerHost={}, " +
                "pendingAcquireTimeout={}ms, idleTimeout={}ms, maxLifeTime={}ms",
                maxConnections, maxConnectionsPerHost, pendingAcquireTimeoutMs, idleTimeoutMs, maxLifeTimeMs);

        return provider;
    }

    /**
     * Create HttpClient with connection provider and timeout settings.
     */
    @Bean
    public HttpClient httpClient(ConnectionProvider connectionProvider) {
        return HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                .compress(true);
    }

    /**
     * Create WebClient.Builder with configured HttpClient.
     * This builder can be injected and used to create WebClient instances
     * that share the same connection pool.
     */
    @Bean
    public WebClient.Builder webClientBuilder(HttpClient httpClient) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    /**
     * Create a shared WebClient instance for general use.
     * OAuth2AuthProcessor and TraceCaptureGlobalFilter can use this
     * instead of creating their own instances.
     */
    @Bean
    public WebClient webClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder.build();
    }
}