package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSON Diff Service for deep comparison of JSON objects.
 * <p>
 * Features:
 * - Recursive comparison of nested objects
 * - Array comparison by index
 * - Full path tracking (e.g., data.users[0].name)
 * - Type change detection
 * <p>
 * Used by RequestReplayService for response body comparison.
 *
 * @author leoli
 */
@Slf4j
@Service
public class JsonDiffService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Compare two JSON strings and return the differences.
     *
     * @param original  Original JSON string
     * @param replayed  Replayed JSON string
     * @return List of differences with full paths
     */
    public List<JsonDiff> compare(String original, String replayed) {
        List<JsonDiff> diffs = new ArrayList<>();

        if (original == null && replayed == null) {
            return diffs;
        }

        if (original == null) {
            diffs.add(new JsonDiff("root", null, replayed, "ADDED", "VALUE"));
            return diffs;
        }

        if (replayed == null) {
            diffs.add(new JsonDiff("root", original, null, "REMOVED", "VALUE"));
            return diffs;
        }

        try {
            JsonNode originalNode = objectMapper.readTree(original);
            JsonNode replayedNode = objectMapper.readTree(replayed);
            diffJson(originalNode, replayedNode, "", diffs);
        } catch (Exception e) {
            // Not valid JSON, compare as text
            if (!original.equals(replayed)) {
                diffs.add(new JsonDiff("body", original, replayed, "CHANGED", "TEXT"));
            }
        }

        return diffs;
    }

    /**
     * Recursively compare JSON nodes.
     *
     * @param original  Original JSON node
     * @param replayed  Replayed JSON node
     * @param path      Current path prefix
     * @param diffs     List to collect differences
     */
    private void diffJson(JsonNode original, JsonNode replayed, String path, List<JsonDiff> diffs) {
        // Check for null
        if (original == null || original.isNull()) {
            if (replayed != null && !replayed.isNull()) {
                diffs.add(new JsonDiff(path, null, replayed.toString(), "ADDED", "VALUE"));
            }
            return;
        }

        if (replayed == null || replayed.isNull()) {
            diffs.add(new JsonDiff(path, original.toString(), null, "REMOVED", "VALUE"));
            return;
        }

        // Check type mismatch
        if (original.getNodeType() != replayed.getNodeType()) {
            diffs.add(new JsonDiff(path, original.toString(), replayed.toString(), "TYPE_CHANGED", "TYPE"));
            return;
        }

        // Handle different node types
        switch (original.getNodeType()) {
            case OBJECT:
                diffObject(original, replayed, path, diffs);
                break;
            case ARRAY:
                diffArray(original, replayed, path, diffs);
                break;
            case STRING:
            case NUMBER:
            case BOOLEAN:
                diffValue(original, replayed, path, diffs);
                break;
            default:
                // Null and other types handled above
                break;
        }
    }

    /**
     * Compare JSON objects.
     */
    private void diffObject(JsonNode original, JsonNode replayed, String path, List<JsonDiff> diffs) {
        // Collect all keys from both objects
        Set<String> allKeys = new TreeSet<>();
        original.fieldNames().forEachRemaining(allKeys::add);
        replayed.fieldNames().forEachRemaining(allKeys::add);

        for (String key : allKeys) {
            String newPath = path.isEmpty() ? key : path + "." + key;
            JsonNode origVal = original.get(key);
            JsonNode repVal = replayed.get(key);

            if (origVal == null || origVal.isNull()) {
                // Key added in replayed
                diffs.add(new JsonDiff(newPath, null, repVal.toString(), "ADDED", "OBJECT_KEY"));
            } else if (repVal == null || repVal.isNull()) {
                // Key removed in replayed
                diffs.add(new JsonDiff(newPath, origVal.toString(), null, "REMOVED", "OBJECT_KEY"));
            } else {
                // Recursively compare
                diffJson(origVal, repVal, newPath, diffs);
            }
        }
    }

    /**
     * Compare JSON arrays by index.
     */
    private void diffArray(JsonNode original, JsonNode replayed, String path, List<JsonDiff> diffs) {
        int origSize = original.size();
        int repSize = replayed.size();

        // Check array length difference
        if (origSize != repSize) {
            diffs.add(new JsonDiff(path + ".length",
                    String.valueOf(origSize), String.valueOf(repSize), "CHANGED", "ARRAY_LENGTH"));
        }

        // Compare elements by index
        int maxSize = Math.max(origSize, repSize);
        for (int i = 0; i < maxSize; i++) {
            String newPath = path + "[" + i + "]";

            if (i >= origSize) {
                // Element added in replayed array
                diffs.add(new JsonDiff(newPath, null, replayed.get(i).toString(), "ADDED", "ARRAY_INDEX"));
            } else if (i >= repSize) {
                // Element removed from replayed array
                diffs.add(new JsonDiff(newPath, original.get(i).toString(), null, "REMOVED", "ARRAY_INDEX"));
            } else {
                // Compare elements
                diffJson(original.get(i), replayed.get(i), newPath, diffs);
            }
        }
    }

    /**
     * Compare primitive values.
     */
    private void diffValue(JsonNode original, JsonNode replayed, String path, List<JsonDiff> diffs) {
        if (!original.equals(replayed)) {
            diffs.add(new JsonDiff(path, original.asText(), replayed.asText(), "CHANGED", "VALUE"));
        }
    }

    /**
     * JSON difference record.
     */
    public static class JsonDiff {
        private final String path;         // Full path: data.users[0].name
        private final String originalValue;
        private final String replayedValue;
        private final String type;         // ADDED, REMOVED, CHANGED, TYPE_CHANGED
        private final String pathType;     // OBJECT_KEY, ARRAY_INDEX, VALUE, TYPE, TEXT, ARRAY_LENGTH

        public JsonDiff(String path, String originalValue, String replayedValue, String type, String pathType) {
            this.path = path;
            this.originalValue = truncate(originalValue, 200);
            this.replayedValue = truncate(replayedValue, 200);
            this.type = type;
            this.pathType = pathType;
        }

        private static String truncate(String value, int maxLen) {
            if (value == null) return null;
            if (value.length() <= maxLen) return value;
            return value.substring(0, maxLen) + "...(truncated)";
        }

        // Getters
        public String getPath() { return path; }
        public String getOriginalValue() { return originalValue; }
        public String getReplayedValue() { return replayedValue; }
        public String getType() { return type; }
        public String getPathType() { return pathType; }

        /**
         * Convert to map for JSON serialization.
         */
        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("field", path);
            map.put("originalValue", originalValue);
            map.put("replayedValue", replayedValue);
            map.put("type", type);
            map.put("pathType", pathType);
            return map;
        }
    }
}