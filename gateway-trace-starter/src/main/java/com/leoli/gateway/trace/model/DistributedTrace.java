package com.leoli.gateway.trace.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed trace record
 * <p>
 * A complete Trace chain for a request, containing multiple Spans
 *
 * @author leoli
 */
@Data
public class DistributedTrace {

    /**
     * TraceId (corresponds to gateway-generated X-Trace-Id)
     */
    private String traceId;

    /**
     * Service name
     */
    private String serviceName;

    /**
     * 请求路径
     */
    private String path;

    /**
     * HTTP method
     */
    private String method;

    /**
     * 开始时间（毫秒）
     */
    private long startTime;

    /**
     * End time (milliseconds)
     */
    private long endTime;

    /**
     * 总耗时（毫秒）
     */
    private long totalDurationMs;

    /**
     * HTTP response status code
     */
    private int statusCode;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * Error message
     */
    private String errorMessage;

    /**
     * Span列表
     */
    private List<ServiceSpan> spans = new ArrayList<>();

    /**
     * Client IP
     */
    private String clientIp;

    /**
     * 重试次数（上报失败时递增）
     */
    private int retryCount = 0;

    /**
     * Max retry count
     */
    public static final int MAX_RETRY_COUNT = 3;

    /**
     * 添加Span
     */
    public void addSpan(ServiceSpan span) {
        spans.add(span);
    }

    /**
     * Increment retry count
     */
    public void incrementRetry() {
        this.retryCount++;
    }

    /**
     * 是否超过最大重试次数
     */
    public boolean isMaxRetryExceeded() {
        return this.retryCount >= MAX_RETRY_COUNT;
    }

    /**
     * 计算总耗时
     */
    public void calculateTotalDuration() {
        this.totalDurationMs = endTime - startTime;
    }

    /**
     * Check if this is a slow request
     *
     * @param thresholdMs Threshold (milliseconds)
     * @return Whether this is a slow request
     */
    public boolean isSlow(long thresholdMs) {
        return totalDurationMs > thresholdMs;
    }
}