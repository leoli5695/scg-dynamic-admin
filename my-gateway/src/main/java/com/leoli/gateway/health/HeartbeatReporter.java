package com.leoli.gateway.health;

import com.leoli.gateway.config.HeartbeatProperties;
import com.leoli.gateway.constants.InternalApiConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Heartbeat Reporter.
 * Reports heartbeat to Admin Console periodically.
 * 
 * State transitions triggered:
 * - STARTING(0) + heartbeat -> RUNNING(1)
 * - ERROR(2) + heartbeat -> RUNNING(1)
 * - RUNNING(1) + heartbeat -> no status change, only update heartbeat time
 *
 * @author leoli
 */
@Slf4j
@Component
public class HeartbeatReporter {

    @Value("${gateway.admin.url:http://127.0.0.1:9090}")
    private String adminUrl;

    /**
     * Shared secret for admin internal report endpoints (X-Internal-Token header).
     * Must match gateway.admin.security.internal-token on the admin console.
     */
    @Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}")
    private String internalToken;

    @Value("${GATEWAY_INSTANCE_ID:}")
    private String instanceId;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${management.server.port:#{null}}")
    private Integer managementPort;

    /**
     * Manually configured access URL (optional).
     * If set, this will be used as the reported access URL.
     * Otherwise, auto-detected IP:port will be used.
     */
    @Value("${gateway.access-url:}")
    private String manualAccessUrl;

    /**
     * Mirror of the {@code gateway.trace.*} keys that TraceCaptureGlobalFilter binds, reported so
     * the console's sampling panel can show what this gateway actually runs. The defaults here must
     * stay identical to that filter's - a drift would make the panel lie in the opposite direction.
     */
    @Value("${gateway.trace.capture-all:false}")
    private boolean traceCaptureAll;

    @Value("${gateway.trace.capture-errors:true}")
    private boolean traceCaptureErrors;

    @Value("${gateway.trace.capture-slow:true}")
    private boolean traceCaptureSlow;

    @Value("${gateway.trace.slow-threshold-ms:3000}")
    private long traceSlowThresholdMs;

    @Value("${gateway.trace.sampling-rate:10}")
    private int traceSamplingRate;

    @Value("${gateway.trace.sampling-rate-for-errors:100}")
    private int traceSamplingRateForErrors;

    @Value("${gateway.trace.max-body-size:65536}")
    private int traceMaxBodySize;

    @Value("${gateway.trace.include-filter-chain:true}")
    private boolean traceIncludeFilterChain;

    @Autowired
    private HeartbeatProperties properties;

    @Autowired
    private RestTemplate restTemplate;

    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Send initial heartbeat when application is ready.
     * This triggers STARTING -> RUNNING transition.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            log.info("Heartbeat reporting is disabled");
            return;
        }

        if (instanceId == null || instanceId.isEmpty()) {
            log.warn("GATEWAY_INSTANCE_ID not set, heartbeat reporting disabled");
            return;
        }

        log.info("Application ready, sending initial heartbeat for instance: {}", instanceId);
        started.set(true);
        
        // Send initial heartbeat immediately
        sendHeartbeat();
    }

    /**
     * Periodic heartbeat task.
     * Runs every 10 seconds by default.
     */
    @Scheduled(fixedRateString = "${gateway.heartbeat.interval-ms:10000}")
    public void reportHeartbeat() {
        if (!properties.isEnabled() || !started.get()) {
            return;
        }

        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }

        sendHeartbeat();
    }

    /**
     * Send heartbeat to Admin Console.
     */
    private void sendHeartbeat() {
        String url = adminUrl + "/api/instances/heartbeat?instanceId=" + instanceId;
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("instanceId", instanceId);
        requestBody.put("timestamp", System.currentTimeMillis());
        requestBody.put("metrics", collectMetrics());
        
        // Report access URL
        String accessUrl = getAccessUrl();
        if (accessUrl != null) {
            requestBody.put("accessUrl", accessUrl);
        }
        
        // Report ports
        requestBody.put("serverPort", serverPort);
        if (managementPort != null) {
            requestBody.put("managementPort", managementPort);
        }

        // Report the trace capture settings this gateway is running with
        requestBody.put("traceConfig", collectTraceConfig());

        int retries = 0;
        while (retries < properties.getMaxRetries()) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (internalToken != null && !internalToken.isEmpty()) {
                    headers.set(InternalApiConstants.TOKEN_HEADER, internalToken);
                }
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

                ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.debug("Heartbeat sent successfully for instance: {}", instanceId);
                    return;
                } else {
                    log.warn("Heartbeat failed with status: {}", response.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("Heartbeat failed (attempt {}/{}): {}", 
                        retries + 1, properties.getMaxRetries(), e.getMessage());
            }
            
            retries++;
            if (retries < properties.getMaxRetries()) {
                try {
                    // 这里已经是 @Scheduled 线程（非 event-loop），直接 sleep 即可。
                    // 用 Mono.delay().block() 只会绕一圈并生产 ScheduledExecutorService 线程。
                    Thread.sleep(properties.getRetryIntervalMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.debug("Heartbeat retry delay interrupted");
                    break;
                }
            }
        }
        
        log.error("Failed to send heartbeat after {} retries", properties.getMaxRetries());
    }

    /**
     * Get the access URL for this gateway.
     * Priority: manual configuration > auto-detected.
     */
    private String getAccessUrl() {
        // 1. Manual configuration
        if (manualAccessUrl != null && !manualAccessUrl.isEmpty()) {
            return manualAccessUrl;
        }
        
        // 2. Auto-detect
        try {
            String hostAddress = InetAddress.getLocalHost().getHostAddress();
            return "http://" + hostAddress + ":" + serverPort;
        } catch (Exception e) {
            log.debug("Failed to auto-detect access URL: {}", e.getMessage());
            return "http://127.0.0.1:" + serverPort;
        }
    }

    /**
     * Snapshot of the effective trace capture settings. Every value is fixed after startup, so this
     * is the same map each cycle; rebuilding it keeps the reported values honest if a future change
     * makes any of them dynamic.
     */
    private Map<String, Object> collectTraceConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("captureAll", traceCaptureAll);
        config.put("captureErrors", traceCaptureErrors);
        config.put("captureSlow", traceCaptureSlow);
        config.put("slowThresholdMs", traceSlowThresholdMs);
        config.put("samplingRate", traceSamplingRate);
        config.put("samplingRateForErrors", traceSamplingRateForErrors);
        config.put("maxBodySize", traceMaxBodySize);
        config.put("includeFilterChain", traceIncludeFilterChain);
        return config;
    }

    /**
     * Collect metrics to include in heartbeat.
     * Can be extended to include memory, CPU, request counts, etc.
     */
    private Map<String, Object> collectMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // Basic JVM metrics
        Runtime runtime = Runtime.getRuntime();
        metrics.put("maxMemory", runtime.maxMemory());
        metrics.put("totalMemory", runtime.totalMemory());
        metrics.put("freeMemory", runtime.freeMemory());
        metrics.put("usedMemory", runtime.totalMemory() - runtime.freeMemory());
        metrics.put("availableProcessors", runtime.availableProcessors());
        
        return metrics;
    }
}