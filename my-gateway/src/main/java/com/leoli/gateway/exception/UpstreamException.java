package com.leoli.gateway.exception;

/**
 * Upstream service exception.
 * Thrown when communication with upstream service fails.
 *
 * @author leoli
 */
public class UpstreamException extends GatewayException {

    private final String serviceId;
    private final String instanceId;
    private final int upstreamStatus;

    public UpstreamException(ErrorCode errorCode, String serviceId) {
        super(errorCode, "Service: " + serviceId);
        this.serviceId = serviceId;
        this.instanceId = null;
        this.upstreamStatus = 0;
    }

    public UpstreamException(ErrorCode errorCode, String serviceId, String details) {
        super(errorCode, "Service: " + serviceId + " - " + details);
        this.serviceId = serviceId;
        this.instanceId = null;
        this.upstreamStatus = 0;
    }

    public UpstreamException(ErrorCode errorCode, String serviceId, String instanceId, String details) {
        super(errorCode, "Service: " + serviceId + " (" + instanceId + ") - " + details);
        this.serviceId = serviceId;
        this.instanceId = instanceId;
        this.upstreamStatus = 0;
    }

    public UpstreamException(ErrorCode errorCode, String serviceId, int upstreamStatus, String details) {
        super(errorCode, "Service: " + serviceId + " returned " + upstreamStatus + " - " + details);
        this.serviceId = serviceId;
        this.instanceId = null;
        this.upstreamStatus = upstreamStatus;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    /**
     * Create exception for connection refused.
     */
    public static UpstreamException connectionRefused(String serviceId, String instanceId) {
        return new UpstreamException(ErrorCode.CONNECTION_REFUSED, serviceId, instanceId, "Connection refused");
    }

    /**
     * Create exception for no healthy instances.
     */
    public static UpstreamException noHealthyInstances(String serviceId) {
        return new UpstreamException(ErrorCode.NO_HEALTHY_INSTANCES, serviceId, "No healthy instances available");
    }

    /**
     * Create exception for gateway timeout.
     */
    public static UpstreamException timeout(String serviceId, long timeoutMs) {
        return new UpstreamException(ErrorCode.UPSTREAM_TIMEOUT, serviceId, "Timeout after " + timeoutMs + "ms");
    }

    /**
     * Create exception for upstream error.
     */
    public static UpstreamException upstreamError(String serviceId, int status, String details) {
        return new UpstreamException(ErrorCode.UPSTREAM_ERROR, serviceId, status, details);
    }

    @Override
    public java.util.Map<String, Object> toErrorMap() {
        java.util.Map<String, Object> map = super.toErrorMap();
        if (serviceId != null) {
            map.put("serviceId", serviceId);
        }
        if (instanceId != null) {
            map.put("instanceId", instanceId);
        }
        if (upstreamStatus > 0) {
            map.put("upstreamStatus", upstreamStatus);
        }
        return map;
    }
}