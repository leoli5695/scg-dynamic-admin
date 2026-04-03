package com.leoli.gateway.admin.model;

/**
 * Gateway Instance Status Enum.
 * 
 * State transitions:
 * - Create instance -> STARTING(0)
 * - STARTING(0) + heartbeat received -> RUNNING(1)
 * - STARTING(0) + timeout (3 min) -> ERROR(2)
 * - RUNNING(1) + heartbeat timeout -> ERROR(2)
 * - ERROR(2) + heartbeat received -> RUNNING(1)
 * - RUNNING(1) or ERROR(2) + user stop -> STOPPING(3)
 * - STOPPING(3) + pods terminated -> STOPPED(4)
 * - STOPPED(4) + user start -> STARTING(0)
 *
 * @author leoli
 */
public enum InstanceStatus {
    
    /**
     * Instance is starting up (Pods being created)
     */
    STARTING(0, "Starting"),
    
    /**
     * Instance is running normally (receiving heartbeats)
     */
    RUNNING(1, "Running"),
    
    /**
     * Instance has error (heartbeat timeout or startup failed)
     */
    ERROR(2, "Error"),
    
    /**
     * Instance is stopping (user initiated stop)
     */
    STOPPING(3, "Stopping"),
    
    /**
     * Instance is stopped (all pods terminated)
     */
    STOPPED(4, "Stopped");
    
    private final int code;
    private final String description;
    
    InstanceStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static InstanceStatus fromCode(int code) {
        for (InstanceStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status code: " + code);
    }
}