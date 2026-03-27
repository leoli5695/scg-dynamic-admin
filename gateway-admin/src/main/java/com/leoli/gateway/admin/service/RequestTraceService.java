package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for managing request traces.
 * Stores error/slow requests and supports replay functionality.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestTraceService {

    private final RequestTraceRepository requestTraceRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Maximum size for request/response body storage
    private static final int MAX_BODY_SIZE = 65536; // 64KB

    /**
     * Save a new request trace
     */
    @Transactional
    public RequestTrace saveTrace(RequestTrace trace) {
        // Truncate large bodies
        if (trace.getRequestBody() != null && trace.getRequestBody().length() > MAX_BODY_SIZE) {
            trace.setRequestBody(trace.getRequestBody().substring(0, MAX_BODY_SIZE) + "...[TRUNCATED]");
        }
        if (trace.getResponseBody() != null && trace.getResponseBody().length() > MAX_BODY_SIZE) {
            trace.setResponseBody(trace.getResponseBody().substring(0, MAX_BODY_SIZE) + "...[TRUNCATED]");
        }

        return requestTraceRepository.save(trace);
    }

    /**
     * Get trace by ID
     */
    public Optional<RequestTrace> getTraceById(Long id) {
        return requestTraceRepository.findById(id);
    }

    /**
     * Get trace by trace ID
     */
    public Optional<RequestTrace> getTraceByTraceId(String traceId) {
        return requestTraceRepository.findByTraceId(traceId);
    }

    /**
     * Get recent error traces
     */
    public List<RequestTrace> getRecentErrors(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return requestTraceRepository.findRecentErrors(pageable);
    }

    /**
     * Get all traces with pagination
     */
    public Page<RequestTrace> getAllTraces(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return requestTraceRepository.findAll(pageable);
    }

    /**
     * Get error traces (4xx and 5xx) with pagination
     */
    public Page<RequestTrace> getErrorTraces(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("traceTime").descending());
        return requestTraceRepository.findErrorTraces(400, pageable);
    }

    /**
     * Get slow request traces
     */
    public Page<RequestTrace> getSlowTraces(int page, int size, long thresholdMs) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("latencyMs").descending());
        return requestTraceRepository.findSlowRequests(thresholdMs, pageable);
    }

    /**
     * Get traces by time range
     */
    public Page<RequestTrace> getTracesByTimeRange(LocalDateTime start, LocalDateTime end, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("traceTime").descending());
        return requestTraceRepository.findByTimeRange(start, end, pageable);
    }

    /**
     * Get traces by route ID
     */
    public List<RequestTrace> getTracesByRouteId(String routeId) {
        return requestTraceRepository.findByRouteId(routeId);
    }

    /**
     * Get traces by client IP
     */
    public List<RequestTrace> getTracesByClientIp(String clientIp) {
        return requestTraceRepository.findByClientIp(clientIp);
    }

    /**
     * Replay a request
     */
    @Transactional
    public Map<String, Object> replayRequest(Long traceId, String gatewayUrl) {
        Optional<RequestTrace> traceOpt = requestTraceRepository.findById(traceId);
        if (traceOpt.isEmpty()) {
            return Map.of("success", false, "error", "Trace not found");
        }

        RequestTrace trace = traceOpt.get();
        if (!Boolean.TRUE.equals(trace.getReplayable())) {
            return Map.of("success", false, "error", "This trace cannot be replayed");
        }

        try {
            // Build request URL
            String url = gatewayUrl + trace.getPath();
            if (trace.getQueryString() != null && !trace.getQueryString().isEmpty()) {
                url += "?" + trace.getQueryString();
            }

            // Parse headers
            HttpHeaders headers = new HttpHeaders();
            if (trace.getRequestHeaders() != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> headerMap = objectMapper.readValue(trace.getRequestHeaders(), Map.class);
                headerMap.forEach((key, value) -> {
                    if (!key.equalsIgnoreCase("host") && !key.equalsIgnoreCase("content-length")) {
                        headers.set(key, value);
                    }
                });
            }
            headers.set("X-Replay-Trace-Id", trace.getTraceId());

            // Build request entity
            HttpEntity<String> entity;
            if (trace.getRequestBody() != null && !trace.getRequestBody().isEmpty()) {
                headers.setContentType(MediaType.APPLICATION_JSON);
                entity = new HttpEntity<>(trace.getRequestBody(), headers);
            } else {
                entity = new HttpEntity<>(headers);
            }

            // Execute request
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.valueOf(trace.getMethod()),
                    entity,
                    String.class
            );
            long latency = System.currentTimeMillis() - startTime;

            // Update replay count
            String result = String.format("Status: %d, Latency: %dms",
                    response.getStatusCodeValue(), latency);
            requestTraceRepository.incrementReplayCount(traceId, result);

            // Build response
            Map<String, Object> result2 = new HashMap<>();
            result2.put("success", true);
            result2.put("statusCode", response.getStatusCodeValue());
            result2.put("latency", latency);
            result2.put("responseBody", truncateBody(response.getBody()));
            result2.put("requestUrl", url);
            result2.put("method", trace.getMethod());

            return result2;

        } catch (org.springframework.web.client.ResourceAccessException e) {
            // Connection refused, timeout, etc.
            String errorMsg = buildConnectionErrorMessage(e, gatewayUrl);
            log.error("Failed to replay request: {}", errorMsg);
            requestTraceRepository.incrementReplayCount(traceId, "Connection Error: " + errorMsg);
            return Map.of(
                "success", false,
                "error", errorMsg,
                "errorType", "CONNECTION_ERROR",
                "targetUrl", gatewayUrl
            );
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 4xx errors
            String errorMsg = String.format("HTTP %d: %s", e.getStatusCode().value(), e.getResponseBodyAsString());
            log.error("Client error during replay: {}", errorMsg);
            requestTraceRepository.incrementReplayCount(traceId, errorMsg);
            return Map.of(
                "success", false,
                "error", errorMsg,
                "errorType", "CLIENT_ERROR",
                "statusCode", e.getStatusCode().value()
            );
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // 5xx errors
            String errorMsg = String.format("HTTP %d: %s", e.getStatusCode().value(), e.getResponseBodyAsString());
            log.error("Server error during replay: {}", errorMsg);
            requestTraceRepository.incrementReplayCount(traceId, errorMsg);
            return Map.of(
                "success", false,
                "error", errorMsg,
                "errorType", "SERVER_ERROR",
                "statusCode", e.getStatusCode().value()
            );
        } catch (Exception e) {
            log.error("Failed to replay request", e);
            requestTraceRepository.incrementReplayCount(traceId, "Error: " + e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * Build user-friendly connection error message
     */
    private String buildConnectionErrorMessage(org.springframework.web.client.ResourceAccessException e, String targetUrl) {
        String message = e.getMessage();
        if (message.contains("Connection refused")) {
            return String.format("无法连接到目标服务 [%s] - 连接被拒绝。请检查：\n" +
                    "1. 目标服务是否正在运行\n" +
                    "2. 端口号是否正确\n" +
                    "3. 防火墙是否阻止了连接", targetUrl);
        } else if (message.contains("timeout") || message.contains("Timeout")) {
            return String.format("连接超时 [%s] - 目标服务响应时间过长", targetUrl);
        } else if (message.contains("Unknown host")) {
            return String.format("无法解析主机名 [%s] - 请检查域名配置", targetUrl);
        } else {
            return String.format("网络错误 [%s]: %s", targetUrl, message);
        }
    }

    /**
     * Delete old traces
     */
    @Transactional
    public int deleteOldTraces(int daysToKeep) {
        LocalDateTime before = LocalDateTime.now().minusDays(daysToKeep);
        return requestTraceRepository.deleteOldTraces(before);
    }

    /**
     * Delete all traces
     */
    @Transactional
    public long deleteAllTraces() {
        long count = requestTraceRepository.count();
        requestTraceRepository.deleteAll();
        return count;
    }

    /**
     * Count errors in time range
     */
    public long countErrorsInTimeRange(LocalDateTime start, LocalDateTime end) {
        return requestTraceRepository.countErrorsInTimeRange(start, end);
    }

    /**
     * Get trace statistics
     */
    public Map<String, Object> getTraceStats() {
        Map<String, Object> stats = new HashMap<>();

        // Total count
        stats.put("total", requestTraceRepository.count());

        // Error count today
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        stats.put("errorsToday", countErrorsInTimeRange(startOfDay, endOfDay));

        // Error count last hour
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        stats.put("errorsLastHour", countErrorsInTimeRange(oneHourAgo, LocalDateTime.now()));

        // Recent errors
        stats.put("recentErrors", getRecentErrors(10));

        return stats;
    }

    /**
     * Truncate body if too large
     */
    private String truncateBody(String body) {
        if (body == null) return null;
        if (body.length() > MAX_BODY_SIZE) {
            return body.substring(0, MAX_BODY_SIZE) + "...[TRUNCATED]";
        }
        return body;
    }

    /**
     * Create trace from request data
     */
    public RequestTrace createTrace(String traceId, String routeId, String method, String uri,
                                     String path, String queryString, Map<String, String> headers,
                                     String requestBody, String clientIp, String userAgent) {
        RequestTrace trace = new RequestTrace();
        trace.setTraceId(traceId);
        trace.setRouteId(routeId);
        trace.setMethod(method);
        trace.setUri(uri);
        trace.setPath(path);
        trace.setQueryString(queryString);
        trace.setClientIp(clientIp);
        trace.setUserAgent(userAgent);
        trace.setRequestBody(requestBody);
        trace.setTraceType("ERROR");

        try {
            trace.setRequestHeaders(objectMapper.writeValueAsString(headers));
        } catch (Exception e) {
            log.error("Failed to serialize headers", e);
        }

        return trace;
    }

    /**
     * Delete trace by ID
     */
    @Transactional
    public void deleteById(Long id) {
        requestTraceRepository.deleteById(id);
    }
}