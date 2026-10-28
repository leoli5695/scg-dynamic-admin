package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.model.RequestTrace;
import java.util.*;

/** Validates only the observed graph. Absence of evidence is never labelled a complete distributed trace. */
final class TraceGraphAnalysis {
    static Map<String, Object> analyze(RequestTrace gateway, List<DistributedTraceEntity> services, ObjectMapper mapper) {
        Map<String, String> parents = new HashMap<>(); Set<String> duplicates = new TreeSet<>(), missing = new TreeSet<>();
        List<String> reasons = new ArrayList<>(); int open = 0, dropped = 0, summaries = 0, unidentified = 0;
        String gatewaySpan = null;
        try {
            if (gateway != null && gateway.getResponseHeaders() != null) {
                var headers = mapper.readTree(gateway.getResponseHeaders());
                var fields = headers.fields(); while (fields.hasNext()) { var entry = fields.next();
                    if (entry.getKey().equalsIgnoreCase("X-Gateway-Span-Id")) gatewaySpan = entry.getValue().asText(); }
            }
        } catch (Exception ignored) { reasons.add("INVALID_GATEWAY_HEADERS"); }
        for (var service : services) {
            open += service.getOpenSpanCount() == null ? 0 : service.getOpenSpanCount();
            dropped += service.getDroppedSpanCount() == null ? 0 : service.getDroppedSpanCount();
            if (!"SAMPLED".equals(service.getCaptureMode())) summaries++;
            try {
                List<Map<String, Object>> spans = service.getSpans() == null ? List.of()
                        : mapper.readValue(service.getSpans(), new TypeReference<>() { });
                if (spans.isEmpty()) reasons.add("NO_SPANS");
                for (var span : spans) {
                    if (parents.size() >= 10000) { reasons.add("GRAPH_LIMIT"); break; }
                    if (!(span.get("spanId") instanceof String id) || id.isBlank()) { unidentified++; continue; }
                    String parent = span.get("parentSpanId") instanceof String p ? p : "";
                    if (parents.putIfAbsent(id, parent) != null) duplicates.add(id);
                }
            } catch (Exception error) { reasons.add("INVALID_SPANS"); }
        }
        for (String parent : parents.values()) if (!parent.isBlank() && !parents.containsKey(parent) && !parent.equals(gatewaySpan)) missing.add(parent);
        Set<String> done = new HashSet<>(); boolean cyclic = false;
        for (String id : parents.keySet()) {
            Set<String> path = new HashSet<>(); String current = id;
            while (parents.containsKey(current) && !done.contains(current)) {
                if (!path.add(current)) { cyclic = true; break; }
                current = parents.get(current);
            }
            done.addAll(path);
        }
        if (!missing.isEmpty()) reasons.add("MISSING_PARENTS");
        if (!duplicates.isEmpty()) reasons.add("DUPLICATE_SPANS");
        if (cyclic) reasons.add("CYCLE");
        if (summaries > 0) reasons.add("SUMMARY_OR_UNKNOWN_CAPTURE");
        if (open > 0) reasons.add("UNFINISHED_ASYNC_SPANS");
        if (dropped > 0) reasons.add("SPAN_LIMIT_REACHED");
        if (unidentified > 0) reasons.add("MISSING_SPAN_IDS");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completenessVerified", false); result.put("observedGraphValid", reasons.isEmpty());
        result.put("missingParentSpanIds", missing); result.put("duplicateSpanIds", duplicates);
        result.put("openSpanCount", open); result.put("droppedSpanCount", dropped); result.put("summaryServiceCount", summaries);
        result.put("reasons", reasons.stream().distinct().toList());
        return result;
    }
}
