package com.leoli.gateway.trace.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
     * <p>
     * CopyOnWriteArrayList: 异步线程（TraceTaskDecorator 共享同一 Trace 引用）
     * 可能与主线程并发 addSpan，普通 ArrayList 会丢数据或抛 CME。
     */
    private List<ServiceSpan> spans = new CopyOnWriteArrayList<>();

    /**
     * 进行中 Span 的注册表（spanId → span），用于 O(1) 定位 span 退出/打标签。
     * 挂在 Trace 对象上（而非 ThreadLocal），异步子线程共享同一 Trace 引用时同样可查。
     * 不参与序列化。
     */
    @JsonIgnore
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private final Map<String, ServiceSpan> spanRegistry = new ConcurrentHashMap<>();

    /**
     * Client IP
     */
    private String clientIp;

    /**
     * Upstream service name (caller service, from X-Caller-Service header)
     * Used for topology graph generation
     */
    private String upstreamService;

    /**
     * Downstream service names (services called by this service)
     * Extracted from ServiceSpan metadata with SERVICE_TARGET tag
     * Used for topology graph generation
     */
    private List<String> downstreamServices = new CopyOnWriteArrayList<>();

    /**
     * Global baggage (cross-service context propagated from upstream)
     */
    private Map<String, String> baggage;

    /**
     * 因超过 span 上限被丢弃的 span 数量（TraceContextHolder.MAX_SPANS_PER_TRACE）
     */
    private int droppedSpanCount = 0;

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
     * 记录一个因超过上限被丢弃的 span（计数允许近似）
     */
    public void incrementDroppedSpans() {
        this.droppedSpanCount++;
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