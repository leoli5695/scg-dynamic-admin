package com.leoli.gateway.health;

import com.leoli.gateway.config.HeartbeatProperties;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;

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

    @Value("${GATEWAY_INSTANCE_ID:}")
    private String instanceId;

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

        int retries = 0;
        while (retries < properties.getMaxRetries()) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
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
                    // Use Mono.delay for non-blocking wait, then block to maintain synchronous API
                    Mono.delay(java.time.Duration.ofMillis(properties.getRetryIntervalMs())).block();
                } catch (Exception ie) {
                    log.debug("Heartbeat retry delay interrupted");
                    break;
                }
            }
        }
        
        log.error("Failed to send heartbeat after {} retries", properties.getMaxRetries());
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