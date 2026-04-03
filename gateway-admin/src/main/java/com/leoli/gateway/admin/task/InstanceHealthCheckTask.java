package com.leoli.gateway.admin.task;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.InstanceStatus;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Instance Health Check Task.
 * 
 * Scheduled task that checks instance health and updates status:
 * 1. Heartbeat timeout detection: RUNNING(1) -> ERROR(2) when heartbeat not received for 30 seconds
 * 2. Startup timeout detection: STARTING(0) -> ERROR(2) when no heartbeat for 3 minutes
 * 3. Stop completion detection: STOPPING(3) -> STOPPED(4) when all pods terminated
 * 
 * @author leoli
 */
@Slf4j
@Component
public class InstanceHealthCheckTask {

    @Autowired
    private GatewayInstanceRepository instanceRepository;

    @Autowired
    private GatewayInstanceService instanceService;

    /**
     * Heartbeat timeout in seconds (default: 30 seconds = 3 heartbeat cycles).
     */
    @Value("${gateway.health.heartbeat-timeout:30}")
    private int heartbeatTimeoutSeconds;

    /**
     * Startup timeout in seconds (default: 3 minutes).
     */
    @Value("${gateway.health.startup-timeout:180}")
    private int startupTimeoutSeconds;

    /**
     * Number of consecutive missed heartbeats before marking as ERROR.
     */
    @Value("${gateway.health.missed-heartbeat-threshold:3}")
    private int missedHeartbeatThreshold;

    /**
     * Health check task runs every 10 seconds.
     */
    @Scheduled(fixedRateString = "${gateway.health.check-interval:10000}")
    @Transactional
    public void checkInstanceHealth() {
        log.debug("Running instance health check...");
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime heartbeatDeadline = now.minusSeconds(heartbeatTimeoutSeconds);
        LocalDateTime startupDeadline = now.minusSeconds(startupTimeoutSeconds);
        
        // 1. Check heartbeat timeout for RUNNING instances
        checkHeartbeatTimeout(heartbeatDeadline);
        
        // 2. Check startup timeout for STARTING instances
        checkStartupTimeout(startupDeadline);
        
        // 3. Check stop completion for STOPPING instances
        checkStopCompletion();
    }

    /**
     * Check heartbeat timeout for RUNNING instances.
     * If no heartbeat received for heartbeatTimeoutSeconds, change to ERROR.
     */
    private void checkHeartbeatTimeout(LocalDateTime deadline) {
        List<GatewayInstanceEntity> runningInstances = instanceRepository
                .findByStatusCode(InstanceStatus.RUNNING.getCode());
        
        for (GatewayInstanceEntity instance : runningInstances) {
            LocalDateTime lastHeartbeat = instance.getLastHeartbeatTime();
            
            // If never received heartbeat, use creation time
            if (lastHeartbeat == null) {
                lastHeartbeat = instance.getCreatedAt();
            }
            
            if (lastHeartbeat != null && lastHeartbeat.isBefore(deadline)) {
                // Increment missed heartbeats
                int missed = instance.getMissedHeartbeats() != null ? 
                        instance.getMissedHeartbeats() + 1 : 1;
                instance.setMissedHeartbeats(missed);
                
                if (missed >= missedHeartbeatThreshold) {
                    // Change to ERROR
                    instance.setStatus(InstanceStatus.ERROR.getDescription());
                    instance.setStatusCode(InstanceStatus.ERROR.getCode());
                    instance.setStatusMessage("Heartbeat timeout: no heartbeat for " + 
                            heartbeatTimeoutSeconds + " seconds");
                    
                    log.warn("Instance {} heartbeat timeout, status changed to ERROR. " +
                            "Last heartbeat: {}", instance.getInstanceId(), lastHeartbeat);
                }
                
                instanceRepository.save(instance);
            }
        }
    }

    /**
     * Check startup timeout for STARTING instances.
     * If no heartbeat received for startupTimeoutSeconds (3 minutes), change to ERROR.
     */
    private void checkStartupTimeout(LocalDateTime deadline) {
        List<GatewayInstanceEntity> startingInstances = instanceRepository
                .findByStatusCode(InstanceStatus.STARTING.getCode());
        
        for (GatewayInstanceEntity instance : startingInstances) {
            LocalDateTime lastHeartbeat = instance.getLastHeartbeatTime();
            
            // If never received heartbeat, use creation time
            if (lastHeartbeat == null) {
                lastHeartbeat = instance.getCreatedAt();
            }
            
            if (lastHeartbeat != null && lastHeartbeat.isBefore(deadline)) {
                // Startup timeout - change to ERROR
                instance.setStatus(InstanceStatus.ERROR.getDescription());
                instance.setStatusCode(InstanceStatus.ERROR.getCode());
                instance.setStatusMessage("Startup timeout: no heartbeat received within " + 
                        startupTimeoutSeconds + " seconds");
                
                log.error("Instance {} startup timeout, status changed to ERROR. " +
                        "Created at: {}", instance.getInstanceId(), instance.getCreatedAt());
                
                instanceRepository.save(instance);
            }
        }
    }

    /**
     * Check stop completion for STOPPING instances.
     * If all pods are terminated, change to STOPPED.
     */
    private void checkStopCompletion() {
        List<GatewayInstanceEntity> stoppingInstances = instanceRepository
                .findByStatusCode(InstanceStatus.STOPPING.getCode());
        
        for (GatewayInstanceEntity instance : stoppingInstances) {
            int runningPods = instanceService.getRunningPodCount(instance);
            
            if (runningPods == 0) {
                // All pods terminated - change to STOPPED
                instance.setStatus(InstanceStatus.STOPPED.getDescription());
                instance.setStatusCode(InstanceStatus.STOPPED.getCode());
                instance.setStatusMessage(null);
                
                log.info("Instance {} stopped successfully, all pods terminated", 
                        instance.getInstanceId());
                
                instanceRepository.save(instance);
            }
        }
    }
}