package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request Replay Debugger Service.
 * Provides enhanced request replay functionality for debugging:
 * 
 * Features:
 * - Replay with modifications (headers, body, query params)
 * - Compare original vs replayed response
 * - Batch replay multiple requests
 * - Async replay with real-time status
 * - Custom target URL for testing
 * - Response diff visualization
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestReplayService {

    private final RequestTraceRepository traceRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayInstanceService instanceService;

    // In-progress replay sessions
    private final ConcurrentHashMap<String, ReplaySession> activeSessions = new ConcurrentHashMap<>();

    // Maximum body size for storage
    private static final int MAX_BODY_SIZE = 65536;

    /**
     * Prepare a trace for replay - returns editable version.
     */
    public ReplayableRequest prepareReplay(Long traceId) {
        Optional<RequestTrace> traceOpt = traceRepository.findById(traceId);
        if (traceOpt.isEmpty()) {
            return null;
        }

        RequestTrace trace = traceOpt.get();
        if (!Boolean.TRUE.equals(trace.getReplayable())) {
            return null;
        }

        ReplayableRequest request = new ReplayableRequest();
        request.setTraceId(traceId);
        request.setTraceUuid(trace.getTraceId());
        request.setMethod(trace.getMethod());
        request.setPath(trace.getPath());
        request.setQueryString(trace.getQueryString());
        request.setOriginalRequestBody(trace.getRequestBody());
        request.setRequestBody(trace.getRequestBody());
        request.setRouteId(trace.getRouteId());
        request.setClientIp(trace.getClientIp());

        // Parse headers
        if (trace.getRequestHeaders() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = objectMapper.readValue(trace.getRequestHeaders(), Map.class);
                request.setOriginalHeaders(new LinkedHashMap<>(headers));
                request.setHeaders(new LinkedHashMap<>(headers));
                // Remove sensitive headers from editable version
                request.getHeaders().keySet().removeIf(this::isSensitiveHeader);
            } catch (Exception e) {
                log.error("Failed to parse headers for trace: {}", traceId, e);
            }
        }

        // Original response info for comparison
        request.setOriginalStatusCode(trace.getStatusCode());
        request.setOriginalResponseBody(trace.getResponseBody());
        request.setOriginalLatencyMs(trace.getLatencyMs());

        return request;
    }

    /**
     * Execute a replay with optional modifications.
     */
    @Transactional
    public ReplayResult executeReplay(Long traceId, String instanceId, ReplayOptions options) {
        ReplayableRequest prepared = prepareReplay(traceId);
        if (prepared == null) {
            return ReplayResult.error("Trace not found or not replayable");
        }

        // Apply modifications
        if (options != null) {
            if (options.getModifiedPath() != null) {
                prepared.setPath(options.getModifiedPath());
            }
            if (options.getModifiedQueryString() != null) {
                prepared.setQueryString(options.getModifiedQueryString());
            }
            if (options.getModifiedHeaders() != null) {
                prepared.getHeaders().putAll(options.getModifiedHeaders());
            }
            if (options.getRemovedHeaders() != null) {
                options.getRemovedHeaders().forEach(h -> prepared.getHeaders().keySet().removeIf(k -> k.equalsIgnoreCase(h)));
            }
            if (options.getModifiedBody() != null) {
                prepared.setRequestBody(options.getModifiedBody());
            }
        }

        // Get gateway URL
        String gatewayUrl = options != null && options.getCustomTargetUrl() != null 
                ? options.getCustomTargetUrl() 
                : instanceService.getAccessUrl(instanceId);

        if (gatewayUrl == null) {
            return ReplayResult.error("Gateway instance not accessible");
        }

        return doReplay(prepared, gatewayUrl, options != null && options.isCompareWithOriginal());
    }

    /**
     * Internal replay execution.
     */
    private ReplayResult doReplay(ReplayableRequest request, String gatewayUrl, boolean compare) {
        long startTime = System.currentTimeMillis();
        ReplayResult result = new ReplayResult();
        result.setTraceId(request.getTraceId());
        result.setTraceUuid(request.getTraceUuid());
        result.setRequestUrl(gatewayUrl + request.getPath());
        result.setMethod(request.getMethod());

        try {
            // Build request URL
            String url = gatewayUrl + request.getPath();
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
                url += "?" + request.getQueryString();
            }
            result.setRequestUrl(url);

            // Build headers
            HttpHeaders headers = new HttpHeaders();
            if (request.getHeaders() != null) {
                request.getHeaders().forEach((key, value) -> {
                    if (!key.equalsIgnoreCase("host") && !key.equalsIgnoreCase("content-length")) {
                        headers.set(key, value);
                    }
                });
            }
            headers.set("X-Replay-Trace-Id", request.getTraceUuid());
            headers.set("X-Replay-Mode", "debug");

            // Build request entity
            HttpEntity<String> entity;
            if (request.getRequestBody() != null && !request.getRequestBody().isEmpty()) {
                if (headers.getContentType() == null) {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }
                entity = new HttpEntity<>(request.getRequestBody(), headers);
            } else {
                entity = new HttpEntity<>(headers);
            }

            // Execute request
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.valueOf(request.getMethod()),
                    entity,
                    String.class
            );

            long latency = System.currentTimeMillis() - startTime;

            result.setSuccess(true);
            result.setStatusCode(response.getStatusCodeValue());
            result.setResponseBody(truncateBody(response.getBody()));
            result.setLatencyMs(latency);

            // Parse response headers
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.getHeaders().forEach((key, values) -> {
                responseHeaders.put(key, String.join(", ", values));
            });
            result.setResponseHeaders(responseHeaders);

            // Compare with original if requested
            if (compare) {
                result.setComparison(compareResponses(request, result));
            }

            // Update replay count
            traceRepository.incrementReplayCount(request.getTraceId(), 
                    String.format("Status: %d, Latency: %dms", response.getStatusCodeValue(), latency));

        } catch (org.springframework.web.client.ResourceAccessException e) {
            String errorMsg = buildConnectionErrorMessage(e, gatewayUrl);
            log.error("Failed to replay request: {}", errorMsg);
            result.setSuccess(false);
            result.setError(errorMsg);
            result.setErrorType("CONNECTION_ERROR");
            traceRepository.incrementReplayCount(request.getTraceId(), "Error: " + errorMsg);

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String errorMsg = String.format("HTTP %d: %s", e.getStatusCode().value(), 
                    truncateBody(e.getResponseBodyAsString()));
            log.error("Client error during replay: {}", errorMsg);
            result.setSuccess(true); // Still a valid response
            result.setStatusCode(e.getStatusCode().value());
            result.setResponseBody(errorMsg);
            result.setLatencyMs(System.currentTimeMillis() - startTime);
            result.setErrorType("CLIENT_ERROR");
            traceRepository.incrementReplayCount(request.getTraceId(), errorMsg);

        } catch (org.springframework.web.client.HttpServerErrorException e) {
            String errorMsg = String.format("HTTP %d: %s", e.getStatusCode().value(), 
                    truncateBody(e.getResponseBodyAsString()));
            log.error("Server error during replay: {}", errorMsg);
            result.setSuccess(true); // Still a valid response
            result.setStatusCode(e.getStatusCode().value());
            result.setResponseBody(errorMsg);
            result.setLatencyMs(System.currentTimeMillis() - startTime);
            result.setErrorType("SERVER_ERROR");
            traceRepository.incrementReplayCount(request.getTraceId(), errorMsg);

        } catch (Exception e) {
            log.error("Failed to replay request", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
            traceRepository.incrementReplayCount(request.getTraceId(), "Error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Batch replay multiple traces.
     */
    public String startBatchReplay(List<Long> traceIds, String instanceId, ReplayOptions options) {
        String sessionId = UUID.randomUUID().toString();
        
        ReplaySession session = new ReplaySession();
        session.setSessionId(sessionId);
        session.setTotalTraces(traceIds.size());
        session.setCompletedTraces(0);
        session.setFailedTraces(0);
        session.setStatus("RUNNING");
        session.setStartTime(System.currentTimeMillis());
        session.setResults(new ArrayList<>());
        
        activeSessions.put(sessionId, session);

        // Execute asynchronously
        CompletableFuture.runAsync(() -> {
            for (Long traceId : traceIds) {
                try {
                    ReplayResult result = executeReplay(traceId, instanceId, options);
                    session.getResults().add(result);
                    
                    if (result.isSuccess()) {
                        session.setCompletedTraces(session.getCompletedTraces() + 1);
                    } else {
                        session.setFailedTraces(session.getFailedTraces() + 1);
                    }
                } catch (Exception e) {
                    log.error("Batch replay failed for trace: {}", traceId, e);
                    session.setFailedTraces(session.getFailedTraces() + 1);
                }
            }
            
            session.setStatus("COMPLETED");
            session.setEndTime(System.currentTimeMillis());
        });

        return sessionId;
    }

    /**
     * Get batch replay status.
     */
    public ReplaySession getBatchStatus(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * Cancel batch replay.
     */
    public boolean cancelBatch(String sessionId) {
        ReplaySession session = activeSessions.get(sessionId);
        if (session != null && "RUNNING".equals(session.getStatus())) {
            session.setStatus("CANCELLED");
            session.setEndTime(System.currentTimeMillis());
            return true;
        }
        return false;
    }

    /**
     * Compare original and replayed responses.
     */
    private ResponseComparison compareResponses(ReplayableRequest original, ReplayResult replayed) {
        ResponseComparison comparison = new ResponseComparison();
        
        // Status code comparison
        comparison.setOriginalStatus(original.getOriginalStatusCode());
        comparison.setReplayedStatus(replayed.getStatusCode());
        comparison.setStatusMatch(original.getOriginalStatusCode() != null && 
                original.getOriginalStatusCode().equals(replayed.getStatusCode()));

        // Latency comparison
        comparison.setOriginalLatencyMs(original.getOriginalLatencyMs());
        comparison.setReplayedLatencyMs(replayed.getLatencyMs());
        if (original.getOriginalLatencyMs() != null && replayed.getLatencyMs() != 0) {
            comparison.setLatencyDiffMs(replayed.getLatencyMs() - original.getOriginalLatencyMs());
        }

        // Body comparison
        comparison.setOriginalBody(original.getOriginalResponseBody());
        comparison.setReplayedBody(replayed.getResponseBody());
        comparison.setBodyMatch(Objects.equals(
                normalizeBody(original.getOriginalResponseBody()),
                normalizeBody(replayed.getResponseBody())
        ));

        if (!comparison.isBodyMatch()) {
            comparison.setBodyDiff(computeBodyDiff(
                    original.getOriginalResponseBody(),
                    replayed.getResponseBody()
            ));
        }

        return comparison;
    }

    /**
     * Normalize body for comparison.
     */
    private String normalizeBody(String body) {
        if (body == null) return "";
        try {
            // Try to parse as JSON and normalize
            Object json = objectMapper.readValue(body, Object.class);
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            // Not JSON, return as-is
            return body.trim();
        }
    }

    /**
     * Compute body diff.
     */
    private List<BodyDiff> computeBodyDiff(String original, String replayed) {
        List<BodyDiff> diffs = new ArrayList<>();
        
        try {
            // Try JSON diff
            if (original != null && original.startsWith("{") && replayed != null && replayed.startsWith("{")) {
                Map<String, Object> originalMap = objectMapper.readValue(original, Map.class);
                Map<String, Object> replayedMap = objectMapper.readValue(replayed, Map.class);
                
                Set<String> allKeys = new TreeSet<>();
                allKeys.addAll(originalMap.keySet());
                allKeys.addAll(replayedMap.keySet());
                
                for (String key : allKeys) {
                    Object origVal = originalMap.get(key);
                    Object repVal = replayedMap.get(key);
                    
                    if (!Objects.equals(origVal, repVal)) {
                        BodyDiff diff = new BodyDiff();
                        diff.setField(key);
                        diff.setOriginalValue(origVal != null ? String.valueOf(origVal) : null);
                        diff.setReplayedValue(repVal != null ? String.valueOf(repVal) : null);
                        diff.setType(origVal == null ? "ADDED" : repVal == null ? "REMOVED" : "CHANGED");
                        diffs.add(diff);
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to simple comparison
            if (!Objects.equals(original, replayed)) {
                BodyDiff diff = new BodyDiff();
                diff.setField("body");
                diff.setOriginalValue(original);
                diff.setReplayedValue(replayed);
                diff.setType("CHANGED");
                diffs.add(diff);
            }
        }
        
        return diffs;
    }

    /**
     * Build user-friendly connection error message.
     */
    private String buildConnectionErrorMessage(Exception e, String targetUrl) {
        String message = e.getMessage();
        if (message.contains("Connection refused")) {
            return String.format("Connection refused to [%s] - target service may not be running", targetUrl);
        } else if (message.contains("timeout") || message.contains("Timeout")) {
            return String.format("Connection timeout [%s] - target service is slow or unreachable", targetUrl);
        } else if (message.contains("Unknown host")) {
            return String.format("Unknown host - DNS resolution failed for [%s]", targetUrl);
        } else {
            return String.format("Network error [%s]: %s", targetUrl, message);
        }
    }

    /**
     * Truncate body if too large.
     */
    private String truncateBody(String body) {
        if (body == null) return null;
        if (body.length() > MAX_BODY_SIZE) {
            return body.substring(0, MAX_BODY_SIZE) + "...[TRUNCATED]";
        }
        return body;
    }

    /**
     * Check if header is sensitive.
     */
    private boolean isSensitiveHeader(String header) {
        String lower = header.toLowerCase();
        return lower.contains("authorization") ||
                lower.contains("cookie") ||
                lower.contains("set-cookie") ||
                lower.contains("proxy-authorization");
    }

    // ============== Data Classes ==============

    public static class ReplayableRequest {
        private Long traceId;
        private String traceUuid;
        private String method;
        private String path;
        private String queryString;
        private Map<String, String> originalHeaders;
        private Map<String, String> headers;
        private String originalRequestBody;
        private String requestBody;
        private String routeId;
        private String clientIp;
        private Integer originalStatusCode;
        private String originalResponseBody;
        private Long originalLatencyMs;

        // Getters and setters
        public Long getTraceId() { return traceId; }
        public void setTraceId(Long traceId) { this.traceId = traceId; }
        public String getTraceUuid() { return traceUuid; }
        public void setTraceUuid(String traceUuid) { this.traceUuid = traceUuid; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getQueryString() { return queryString; }
        public void setQueryString(String queryString) { this.queryString = queryString; }
        public Map<String, String> getOriginalHeaders() { return originalHeaders; }
        public void setOriginalHeaders(Map<String, String> originalHeaders) { this.originalHeaders = originalHeaders; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public String getOriginalRequestBody() { return originalRequestBody; }
        public void setOriginalRequestBody(String originalRequestBody) { this.originalRequestBody = originalRequestBody; }
        public String getRequestBody() { return requestBody; }
        public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
        public String getRouteId() { return routeId; }
        public void setRouteId(String routeId) { this.routeId = routeId; }
        public String getClientIp() { return clientIp; }
        public void setClientIp(String clientIp) { this.clientIp = clientIp; }
        public Integer getOriginalStatusCode() { return originalStatusCode; }
        public void setOriginalStatusCode(Integer originalStatusCode) { this.originalStatusCode = originalStatusCode; }
        public String getOriginalResponseBody() { return originalResponseBody; }
        public void setOriginalResponseBody(String originalResponseBody) { this.originalResponseBody = originalResponseBody; }
        public Long getOriginalLatencyMs() { return originalLatencyMs; }
        public void setOriginalLatencyMs(Long originalLatencyMs) { this.originalLatencyMs = originalLatencyMs; }
    }

    public static class ReplayOptions {
        private String modifiedPath;
        private String modifiedQueryString;
        private Map<String, String> modifiedHeaders;
        private List<String> removedHeaders;
        private String modifiedBody;
        private String customTargetUrl;
        private boolean compareWithOriginal = true;

        // Getters and setters
        public String getModifiedPath() { return modifiedPath; }
        public void setModifiedPath(String modifiedPath) { this.modifiedPath = modifiedPath; }
        public String getModifiedQueryString() { return modifiedQueryString; }
        public void setModifiedQueryString(String modifiedQueryString) { this.modifiedQueryString = modifiedQueryString; }
        public Map<String, String> getModifiedHeaders() { return modifiedHeaders; }
        public void setModifiedHeaders(Map<String, String> modifiedHeaders) { this.modifiedHeaders = modifiedHeaders; }
        public List<String> getRemovedHeaders() { return removedHeaders; }
        public void setRemovedHeaders(List<String> removedHeaders) { this.removedHeaders = removedHeaders; }
        public String getModifiedBody() { return modifiedBody; }
        public void setModifiedBody(String modifiedBody) { this.modifiedBody = modifiedBody; }
        public String getCustomTargetUrl() { return customTargetUrl; }
        public void setCustomTargetUrl(String customTargetUrl) { this.customTargetUrl = customTargetUrl; }
        public boolean isCompareWithOriginal() { return compareWithOriginal; }
        public void setCompareWithOriginal(boolean compareWithOriginal) { this.compareWithOriginal = compareWithOriginal; }
    }

    public static class ReplayResult {
        private boolean success;
        private Long traceId;
        private String traceUuid;
        private String method;
        private String requestUrl;
        private int statusCode;
        private String responseBody;
        private Map<String, String> responseHeaders;
        private long latencyMs;
        private String error;
        private String errorType;
        private ResponseComparison comparison;

        public static ReplayResult error(String message) {
            ReplayResult result = new ReplayResult();
            result.setSuccess(false);
            result.setError(message);
            return result;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            map.put("traceId", traceId);
            map.put("traceUuid", traceUuid);
            map.put("method", method);
            map.put("requestUrl", requestUrl);
            map.put("statusCode", statusCode);
            map.put("latencyMs", latencyMs);
            if (responseBody != null) map.put("responseBody", responseBody);
            if (responseHeaders != null) map.put("responseHeaders", responseHeaders);
            if (error != null) map.put("error", error);
            if (errorType != null) map.put("errorType", errorType);
            if (comparison != null) map.put("comparison", comparison.toMap());
            return map;
        }

        // Getters and setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Long getTraceId() { return traceId; }
        public void setTraceId(Long traceId) { this.traceId = traceId; }
        public String getTraceUuid() { return traceUuid; }
        public void setTraceUuid(String traceUuid) { this.traceUuid = traceUuid; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getRequestUrl() { return requestUrl; }
        public void setRequestUrl(String requestUrl) { this.requestUrl = requestUrl; }
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        public String getResponseBody() { return responseBody; }
        public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
        public Map<String, String> getResponseHeaders() { return responseHeaders; }
        public void setResponseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }
        public ResponseComparison getComparison() { return comparison; }
        public void setComparison(ResponseComparison comparison) { this.comparison = comparison; }
    }

    public static class ResponseComparison {
        private Integer originalStatus;
        private Integer replayedStatus;
        private boolean statusMatch;
        private Long originalLatencyMs;
        private Long replayedLatencyMs;
        private Long latencyDiffMs;
        private String originalBody;
        private String replayedBody;
        private boolean bodyMatch;
        private List<BodyDiff> bodyDiff;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("originalStatus", originalStatus);
            map.put("replayedStatus", replayedStatus);
            map.put("statusMatch", statusMatch);
            map.put("originalLatencyMs", originalLatencyMs);
            map.put("replayedLatencyMs", replayedLatencyMs);
            map.put("latencyDiffMs", latencyDiffMs);
            map.put("bodyMatch", bodyMatch);
            if (bodyDiff != null && !bodyDiff.isEmpty()) {
                map.put("bodyDiff", bodyDiff.stream().map(BodyDiff::toMap).toList());
            }
            return map;
        }

        // Getters and setters
        public Integer getOriginalStatus() { return originalStatus; }
        public void setOriginalStatus(Integer originalStatus) { this.originalStatus = originalStatus; }
        public Integer getReplayedStatus() { return replayedStatus; }
        public void setReplayedStatus(Integer replayedStatus) { this.replayedStatus = replayedStatus; }
        public boolean isStatusMatch() { return statusMatch; }
        public void setStatusMatch(boolean statusMatch) { this.statusMatch = statusMatch; }
        public Long getOriginalLatencyMs() { return originalLatencyMs; }
        public void setOriginalLatencyMs(Long originalLatencyMs) { this.originalLatencyMs = originalLatencyMs; }
        public Long getReplayedLatencyMs() { return replayedLatencyMs; }
        public void setReplayedLatencyMs(Long replayedLatencyMs) { this.replayedLatencyMs = replayedLatencyMs; }
        public Long getLatencyDiffMs() { return latencyDiffMs; }
        public void setLatencyDiffMs(Long latencyDiffMs) { this.latencyDiffMs = latencyDiffMs; }
        public String getOriginalBody() { return originalBody; }
        public void setOriginalBody(String originalBody) { this.originalBody = originalBody; }
        public String getReplayedBody() { return replayedBody; }
        public void setReplayedBody(String replayedBody) { this.replayedBody = replayedBody; }
        public boolean isBodyMatch() { return bodyMatch; }
        public void setBodyMatch(boolean bodyMatch) { this.bodyMatch = bodyMatch; }
        public List<BodyDiff> getBodyDiff() { return bodyDiff; }
        public void setBodyDiff(List<BodyDiff> bodyDiff) { this.bodyDiff = bodyDiff; }
    }

    public static class BodyDiff {
        private String field;
        private String originalValue;
        private String replayedValue;
        private String type; // ADDED, REMOVED, CHANGED

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("field", field);
            map.put("originalValue", originalValue);
            map.put("replayedValue", replayedValue);
            map.put("type", type);
            return map;
        }

        // Getters and setters
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getOriginalValue() { return originalValue; }
        public void setOriginalValue(String originalValue) { this.originalValue = originalValue; }
        public String getReplayedValue() { return replayedValue; }
        public void setReplayedValue(String replayedValue) { this.replayedValue = replayedValue; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public static class ReplaySession {
        private String sessionId;
        private int totalTraces;
        private int completedTraces;
        private int failedTraces;
        private String status;
        private long startTime;
        private Long endTime;
        private List<ReplayResult> results;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sessionId", sessionId);
            map.put("totalTraces", totalTraces);
            map.put("completedTraces", completedTraces);
            map.put("failedTraces", failedTraces);
            map.put("status", status);
            map.put("startTime", startTime);
            if (endTime != null) map.put("endTime", endTime);
            if (results != null) {
                map.put("results", results.stream().map(ReplayResult::toMap).toList());
            }
            return map;
        }

        // Getters and setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public int getTotalTraces() { return totalTraces; }
        public void setTotalTraces(int totalTraces) { this.totalTraces = totalTraces; }
        public int getCompletedTraces() { return completedTraces; }
        public void setCompletedTraces(int completedTraces) { this.completedTraces = completedTraces; }
        public int getFailedTraces() { return failedTraces; }
        public void setFailedTraces(int failedTraces) { this.failedTraces = failedTraces; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public Long getEndTime() { return endTime; }
        public void setEndTime(Long endTime) { this.endTime = endTime; }
        public List<ReplayResult> getResults() { return results; }
        public void setResults(List<ReplayResult> results) { this.results = results; }
    }
}