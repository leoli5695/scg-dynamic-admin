package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Active health checker (TCP ping)
 * @author leoli
 */
@Component
@Slf4j
public class ActiveHealthChecker {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private HybridHealthChecker hybridHealthChecker;
    
    @Value("${gateway.health-check.endpoint:/actuator/health}")
    private String healthEndpoint;
    
    @Value("${gateway.health-check.timeout:3000}")
    private int timeoutMs;
    
    /**
     * Probe instance health status using TCP ping
     */
    public void probe(String serviceId, String ip, int port) {
        log.info("Probing instance health: {}:{}:{} via TCP ping", serviceId, ip, port);
        
        try {
            // Use TCP socket to check if port is open (faster and simpler)
            boolean isReachable = checkPortOpen(ip, port, timeoutMs);
            
            log.info("TCP check result for {}:{}:{} - reachable={}, port={}", 
                    serviceId, ip, port, isReachable, port);
            
            if (isReachable) {
                // Port is open, mark as healthy
                log.info("Marking instance {}:{}:{} as HEALTHY", serviceId, ip, port);
                hybridHealthChecker.markHealthy(serviceId, ip, port, "ACTIVE");
            } else {
                // Port is closed, mark as unhealthy
                log.warn("Marking instance {}:{}:{} as UNHEALTHY (port closed)", serviceId, ip, port);
                hybridHealthChecker.markUnhealthy(
                    serviceId, ip, port,
                    "TCP_UNREACHABLE: Port " + port + " is not responding",
                    "ACTIVE"
                );
            }
        } catch (Exception e) {
            // Connection failed, mark as unhealthy
            log.error("Marking instance {}:{}:{} as UNHEALTHY (exception)", serviceId, ip, port, e);
            hybridHealthChecker.markUnhealthy(
                serviceId, ip, port,
                "TCP_ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                "ACTIVE"
            );
        }
    }
    
    /**
     * Check if a TCP port is open using Socket
     */
    private boolean checkPortOpen(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            log.trace("Port {}:{} is not reachable: {}", ip, port, e.getMessage());
            return false;
        }
    }
    
    /**
     * Build health check URL (not used in TCP mode)
     */
    @Deprecated
    private String buildHealthUrl(String ip, int port) {
        return String.format("http://%s:%d%s", ip, port, healthEndpoint);
    }
    
    /**
     * Check if response is healthy (not used in TCP mode)
     */
    @Deprecated
    private boolean isHealthyResponse(String response) {
        try {
            com.fasterxml.jackson.databind.JsonNode jsonNode = 
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            String status = jsonNode.get("status").asText();
            return "UP".equals(status);
        } catch (Exception e) {
            // If not JSON, check if contains "UP" or "OK"
            return response != null && (response.contains("UP") || response.contains("OK"));
        }
    }
}
