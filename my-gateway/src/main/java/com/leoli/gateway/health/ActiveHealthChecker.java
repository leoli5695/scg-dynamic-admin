package com.leoli.gateway.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Active health checker using TCP ping and optional HTTP health endpoint.
 *
 * @author leoli
 */
@Component
@Slf4j
public class ActiveHealthChecker {

    @Autowired
    private HybridHealthChecker hybridHealthChecker;

    @Autowired(required = false)
    @Qualifier("healthCheckRestTemplate")
    private RestTemplate healthCheckRestTemplate;

    @Value("${gateway.health-check.timeout:3000}")
    private int timeoutMs;

    @Value("${gateway.health-check.http-enabled:false}")
    private boolean httpCheckEnabled;

    @Value("${gateway.health-check.http-path:/actuator/health}")
    private String healthCheckPath;

    /**
     * Probe instance health status using TCP ping and optional HTTP check.
     */
    public void probe(String serviceId, String ip, int port) {
        log.debug("Probing instance health: {}:{}:{}", serviceId, ip, port);

        try {
            // Step 1: TCP port check (basic connectivity)
            boolean tcpReachable = checkPortOpen(ip, port, timeoutMs);

            if (!tcpReachable) {
                // TCP failed, definitely unhealthy
                handleUnhealthy(serviceId, ip, port, "TCP_UNREACHABLE: Port " + port + " is not responding");
                return;
            }

            // Step 2: Optional HTTP health check (application-level health)
            if (httpCheckEnabled && healthCheckRestTemplate != null) {
                boolean httpHealthy = checkHttpHealth(ip, port);
                if (!httpHealthy) {
                    handleUnhealthy(serviceId, ip, port, "HTTP_UNHEALTHY: Health endpoint returned error");
                    return;
                }
            }

            // All checks passed, mark as healthy
            log.debug("Instance {}:{}:{} passed all health checks", serviceId, ip, port);
            hybridHealthChecker.markHealthy(serviceId, ip, port, "ACTIVE");

        } catch (Exception e) {
            handleUnhealthy(serviceId, ip, port, "CHECK_ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Handle unhealthy status with alert cooldown.
     */
    private void handleUnhealthy(String serviceId, String ip, int port, String reason) {
        log.warn("Instance {}:{}:{} is UNHEALTHY: {}", serviceId, ip, port, reason);
        hybridHealthChecker.markUnhealthy(serviceId, ip, port, reason, "ACTIVE");
    }

    /**
     * Check if a TCP port is open using Socket.
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
     * Check HTTP health endpoint.
     */
    private boolean checkHttpHealth(String ip, int port) {
        try {
            String url = String.format("http://%s:%d%s", ip, port, healthCheckPath);

            // Use timeout-aware request
            org.springframework.http.ResponseEntity<String> response =
                    healthCheckRestTemplate.getForEntity(url, String.class);

            // Consider 2xx as healthy
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("HTTP health check failed for {}:{}: {}", ip, port, e.getMessage());
            return false;
        }
    }
}