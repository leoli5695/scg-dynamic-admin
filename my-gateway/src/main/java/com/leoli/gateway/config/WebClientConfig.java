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
     * Outbound client for the gateway's OWN calls (admin provider-key fetch,
     * OAuth2, embedding, trace export).
     * <p>
     * Deliberately not a {@code @Bean}. SCG's routing {@code HttpClient} is
     * conditional-on-missing, so exporting one here made this pool - sized for a
     * handful of admin calls - the cap on every proxied upstream connection.
     * Measured on the perf harness: upstream connections stayed pinned at
     * {@code maxConnections} (100) at 100, 200 and 400 concurrent streams, so
     * streams queued on pool acquisition and TTFT grew to seconds; raising the
     * value to 800 moved the observed cap to ~400 connections, confirming the
     * causal link. Routing pool sizing belongs in
     * {@code spring.cloud.gateway.httpclient.pool}.
     */
    private HttpClient outboundHttpClient() {
        ConnectionProvider provider = ConnectionProvider.builder("gateway-webclient-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeoutMs))
                .pendingAcquireMaxCount(pendingAcquireMaxCount)
                .maxIdleTime(Duration.ofMillis(idleTimeoutMs))
                .maxLifeTime(Duration.ofMillis(maxLifeTimeMs))
                .build();

        // reactor-netty's maxConnections is already per remote host; there is no
        // separate per-host knob, so the old log line claimed a limit that was
        // never applied.
        log.info("WebClient outbound ConnectionPool configured: maxConnections={} (per host), " +
                "pendingAcquireTimeout={}ms, idleTimeout={}ms, maxLifeTime={}ms",
                maxConnections, pendingAcquireTimeoutMs, idleTimeoutMs, maxLifeTimeMs);

        return HttpClient.create(provider)
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
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(outboundHttpClient()));
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