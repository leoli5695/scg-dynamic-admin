package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

/**
 * Service to query Jaeger for distributed tracing data.
 *
 * @author leoli
 */
@Service
@Slf4j
public class JaegerService {

    @Value("${gateway.jaeger.url:http://localhost:16686}")
    private String jaegerUrl;

    @Value("${gateway.jaeger.enabled:true}")
    private boolean jaegerEnabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public JaegerService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Check if Jaeger is available.
     */
    public boolean isAvailable() {
        if (!jaegerEnabled) {
            return false;
        }
        try {
            String result = restTemplate.getForObject(jaegerUrl + "/api/services", String.class);
            return result != null && result.contains("\"data\"");
        } catch (Exception e) {
            log.debug("Jaeger not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get list of services from Jaeger.
     */
    public List<String> getServices() {
        try {
            String response = restTemplate.getForObject(jaegerUrl + "/api/services", String.class);
            if (response == null) return Collections.emptyList();

            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            
            List<String> services = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode service : data) {
                    services.add(service.asText());
                }
            }
            return services;
        } catch (Exception e) {
            log.warn("Failed to get Jaeger services: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get operations (endpoints) for a specific service.
     */
    public List<String> getOperations(String service) {
        try {
            String url = jaegerUrl + "/api/services/" + service + "/operations";
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return Collections.emptyList();

            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            
            List<String> operations = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode op : data) {
                    operations.add(op.asText());
                }
            }
            return operations;
        } catch (Exception e) {
            log.warn("Failed to get operations for service {}: {}", service, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Search traces from Jaeger.
     * @param service Service name (required)
     * @param operation Operation name (optional)
     * @param limit Maximum number of traces to return
     * @param lookback Lookback duration in minutes (default 60)
     * @param start Start timestamp in microseconds (optional)
     * @param end End timestamp in microseconds (optional)
     * @param traceId Specific trace ID to search (optional)
     * @return List of trace summaries
     */
    public List<Map<String, Object>> searchTraces(String service, String operation, 
            Integer limit, Integer lookback, Long start, Long end, String traceId) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromHttpUrl(jaegerUrl + "/api/traces")
                    .queryParam("service", service);
            
            if (operation != null && !operation.isEmpty()) {
                builder.queryParam("operation", operation);
            }
            if (limit != null) {
                builder.queryParam("limit", limit);
            }
            if (lookback != null) {
                builder.queryParam("lookback", lookback + "m");
            }
            if (start != null) {
                builder.queryParam("start", start);
            }
            if (end != null) {
                builder.queryParam("end", end);
            }
            if (traceId != null && !traceId.isEmpty()) {
                builder.queryParam("traceID", traceId.replace("-", "").toLowerCase());
            }

            String url = builder.build().toUri().toString();
            log.debug("Searching Jaeger traces: {}", url);
            
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return Collections.emptyList();

            return parseTracesResponse(response);
        } catch (Exception e) {
            log.warn("Failed to search Jaeger traces: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get a specific trace by trace ID.
     * @param traceId The trace ID (UUID format or hex format)
     */
    public Map<String, Object> getTrace(String traceId) {
        try {
            // Jaeger uses hex format trace ID, convert UUID to hex if needed
            String jaegerTraceId = traceId.replace("-", "").toLowerCase();
            String url = jaegerUrl + "/api/traces/" + jaegerTraceId;
            
            log.debug("Getting Jaeger trace: {}", url);
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return Collections.emptyMap();

            List<Map<String, Object>> traces = parseTracesResponse(response);
            return traces.isEmpty() ? Collections.emptyMap() : traces.get(0);
        } catch (Exception e) {
            log.warn("Failed to get Jaeger trace {}: {}", traceId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Parse Jaeger traces API response.
     */
    private List<Map<String, Object>> parseTracesResponse(String response) {
        List<Map<String, Object>> traces = new ArrayList<>();
        
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            
            if (data.isArray()) {
                for (JsonNode traceNode : data) {
                    Map<String, Object> trace = parseTrace(traceNode);
                    traces.add(trace);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Jaeger response: {}", e.getMessage());
        }
        
        return traces;
    }

    /**
     * Parse a single trace from Jaeger response.
     */
    private Map<String, Object> parseTrace(JsonNode traceNode) {
        Map<String, Object> trace = new LinkedHashMap<>();
        
        // Trace ID
        String traceId = traceNode.path("traceID").asText("");
        trace.put("traceId", traceId);
        
        // Spans
        JsonNode spansNode = traceNode.path("spans");
        List<Map<String, Object>> spans = new ArrayList<>();
        if (spansNode.isArray()) {
            for (JsonNode spanNode : spansNode) {
                spans.add(parseSpan(spanNode, traceNode.path("processes")));
            }
        }
        trace.put("spans", spans);
        trace.put("spanCount", spans.size());
        
        // Calculate total duration (max span endTime - min span startTime)
        long minStart = Long.MAX_VALUE;
        long maxEnd = 0;
        for (Map<String, Object> span : spans) {
            long startTime = (Long) span.get("startTime");
            long duration = (Long) span.get("duration");
            long endTime = startTime + duration;
            minStart = Math.min(minStart, startTime);
            maxEnd = Math.max(maxEnd, endTime);
        }
        long totalDuration = maxEnd > minStart ? maxEnd - minStart : 0;
        trace.put("duration", totalDuration);
        trace.put("durationMs", totalDuration / 1000); // Convert microseconds to milliseconds
        
        // Processes info
        JsonNode processesNode = traceNode.path("processes");
        Map<String, Map<String, Object>> processes = new HashMap<>();
        if (processesNode.isObject()) {
            processesNode.fields().forEachRemaining(entry -> {
                Map<String, Object> process = new LinkedHashMap<>();
                process.put("serviceName", entry.getValue().path("serviceName").asText(""));
                
                // Parse tags
                List<Map<String, Object>> tags = new ArrayList<>();
                JsonNode tagsNode = entry.getValue().path("tags");
                if (tagsNode.isArray()) {
                    for (JsonNode tag : tagsNode) {
                        Map<String, Object> tagMap = new HashMap<>();
                        tagMap.put("key", tag.path("key").asText(""));
                        tagMap.put("value", tag.path("value").asText(""));
                        tags.add(tagMap);
                    }
                }
                process.put("tags", tags);
                processes.put(entry.getKey(), process);
            });
        }
        trace.put("processes", processes);
        
        // Warnings
        JsonNode warningsNode = traceNode.path("warnings");
        List<String> warnings = new ArrayList<>();
        if (warningsNode.isArray()) {
            for (JsonNode warning : warningsNode) {
                warnings.add(warning.asText());
            }
        }
        trace.put("warnings", warnings);
        
        return trace;
    }

    /**
     * Parse a single span from Jaeger response.
     */
    private Map<String, Object> parseSpan(JsonNode spanNode, JsonNode processesNode) {
        Map<String, Object> span = new LinkedHashMap<>();
        
        span.put("spanId", spanNode.path("spanID").asText(""));
        span.put("operationName", spanNode.path("operationName").asText(""));
        span.put("startTime", spanNode.path("startTime").asLong(0));
        span.put("duration", spanNode.path("duration").asLong(0));
        span.put("durationMs", spanNode.path("duration").asLong(0) / 1000);
        
        // References (parent span)
        JsonNode referencesNode = spanNode.path("references");
        String parentSpanId = "";
        if (referencesNode.isArray()) {
            for (JsonNode ref : referencesNode) {
                if ("CHILD_OF".equals(ref.path("refType").asText("")) || 
                    "FOLLOWS_FROM".equals(ref.path("refType").asText(""))) {
                    parentSpanId = ref.path("spanID").asText("");
                    break;
                }
            }
        }
        span.put("parentSpanId", parentSpanId);
        
        // Process ID to get service name
        String processId = spanNode.path("processID").asText("");
        JsonNode processNode = processesNode.path(processId);
        span.put("serviceName", processNode.path("serviceName").asText(""));
        
        // Tags
        List<Map<String, Object>> tags = new ArrayList<>();
        JsonNode tagsNode = spanNode.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                Map<String, Object> tagMap = new HashMap<>();
                tagMap.put("key", tag.path("key").asText(""));
                tagMap.put("value", tag.path("value").asText(""));
                tagMap.put("type", tag.path("type").asText(""));
                tags.add(tagMap);
            }
        }
        span.put("tags", tags);
        
        // Logs
        List<Map<String, Object>> logs = new ArrayList<>();
        JsonNode logsNode = spanNode.path("logs");
        if (logsNode.isArray()) {
            for (JsonNode log : logsNode) {
                Map<String, Object> logMap = new HashMap<>();
                logMap.put("timestamp", log.path("timestamp").asLong(0));
                
                List<Map<String, Object>> logFields = new ArrayList<>();
                JsonNode fieldsNode = log.path("fields");
                if (fieldsNode.isArray()) {
                    for (JsonNode field : fieldsNode) {
                        Map<String, Object> fieldMap = new HashMap<>();
                        fieldMap.put("key", field.path("key").asText(""));
                        fieldMap.put("value", field.path("value").asText(""));
                        logFields.add(fieldMap);
                    }
                }
                logMap.put("fields", logFields);
                logs.add(logMap);
            }
        }
        span.put("logs", logs);
        
        // Kind (client/server/internal)
        String kind = "internal";
        for (Map<String, Object> tag : tags) {
            if ("span.kind".equals(tag.get("key"))) {
                kind = (String) tag.get("value");
                break;
            }
        }
        span.put("kind", kind);
        
        // Status (from tags)
        String status = "success";
        Integer statusCode = null;
        for (Map<String, Object> tag : tags) {
            if ("status.code".equals(tag.get("key")) || "http.status_code".equals(tag.get("key"))) {
                try {
                    statusCode = Integer.parseInt((String) tag.get("value"));
                    if (statusCode >= 400) {
                        status = statusCode >= 500 ? "error" : "warning";
                    }
                } catch (NumberFormatException ignored) {}
                break;
            }
        }
        span.put("status", status);
        span.put("statusCode", statusCode);
        
        // Errors
        boolean hasErrors = false;
        String errorMessage = "";
        for (Map<String, Object> tag : tags) {
            if ("error".equals(tag.get("key"))) {
                hasErrors = true;
                errorMessage = (String) tag.get("value");
                break;
            }
        }
        span.put("hasErrors", hasErrors);
        span.put("errorMessage", errorMessage);
        
        return span;
    }

    /**
     * Search traces for a specific service with default parameters.
     */
    public List<Map<String, Object>> searchTraces(String service) {
        return searchTraces(service, null, 20, 60, null, null, null);
    }

    /**
     * Search trace by trace ID (for linking from RequestTrace).
     * Converts UUID format traceId to Jaeger hex format.
     */
    public Map<String, Object> searchByTraceId(String uuidTraceId) {
        try {
            List<Map<String, Object>> traces = searchTraces(
                null, null, 1, null, null, null, uuidTraceId
            );
            return traces.isEmpty() ? Collections.emptyMap() : traces.get(0);
        } catch (Exception e) {
            log.warn("Failed to search trace by ID {}: {}", uuidTraceId, e.getMessage());
            return Collections.emptyMap();
        }
    }
}