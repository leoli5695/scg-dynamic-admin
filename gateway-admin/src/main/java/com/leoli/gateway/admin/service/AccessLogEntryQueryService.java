package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AccessLogGlobalConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AccessLogEntryQueryService {
    private static final int MAX_TAIL_LINES = 10_000;
    private static final Pattern LOG_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final AccessLogConfigService accessLogConfigService;
    private final KubernetesResourceService kubernetesResourceService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> getLogEntries(
            String date,
            int page,
            int size,
            String method,
            Integer statusCode,
            String path,
            String traceId) {
        AccessLogGlobalConfig config = accessLogConfigService.getConfig();
        Path logDirPath = resolveLogDirectory(config.getLogDirectory());
        Path filePath = resolveLogFile(logDirPath, date);
        String fileName = filePath.getFileName().toString();

        if (!Files.exists(filePath)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entries", Collections.emptyList());
            data.put("total", 0);
            data.put("page", page);
            data.put("size", size);
            data.put("file", fileName);
            return data;
        }

        List<Map<String, Object>> entries = filterEntries(
                readFileEntries(filePath), method, statusCode, path, traceId);
        List<Map<String, Object>> pagedEntries = paginate(entries, page, size);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entries", pagedEntries);
        data.put("total", entries.size());
        data.put("page", page);
        data.put("size", size);
        data.put("file", fileName);
        return data;
    }

    public Map<String, Object> getLogStats(String date) {
        AccessLogGlobalConfig config = accessLogConfigService.getConfig();
        Path logDirPath = resolveLogDirectory(config.getLogDirectory());
        Path filePath = resolveLogFile(logDirPath, date);
        String fileName = filePath.getFileName().toString();

        if (!Files.exists(filePath)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalRequests", 0);
            data.put("avgDuration", 0.0);
            data.put("statusCodes", Collections.emptyMap());
            data.put("topPaths", Collections.emptyList());
            data.put("methods", Collections.emptyMap());
            return data;
        }

        Map<String, Object> data = buildStats(readFileEntries(filePath));
        data.put("file", fileName);
        return data;
    }

    public Map<String, Object> getK8sLogEntries(
            Long clusterId,
            String namespace,
            String podName,
            int tailLines,
            int page,
            int size,
            String method,
            Integer statusCode,
            String path,
            String traceId,
            Integer sinceSeconds) {
        String rawLogs = kubernetesResourceService.getPodLogs(clusterId, namespace, podName, null,
                sinceSeconds != null ? null : clampTailLines(tailLines), sinceSeconds);

        List<Map<String, Object>> entries = filterEntries(
                readK8sEntries(rawLogs), method, statusCode, path, traceId);
        entries.sort((a, b) -> {
            String tsA = (String) a.get("@timestamp");
            String tsB = (String) b.get("@timestamp");
            if (tsA == null || tsB == null) return 0;
            return tsB.compareTo(tsA);
        });
        List<Map<String, Object>> pagedEntries = paginate(entries, page, size);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entries", pagedEntries);
        data.put("total", entries.size());
        data.put("page", page);
        data.put("size", size);
        data.put("podName", podName);
        data.put("namespace", namespace);
        data.put("realtime", true);
        return data;
    }

    public Map<String, Object> getK8sLogStats(
            Long clusterId,
            String namespace,
            String podName,
            int tailLines,
            Integer sinceSeconds) {
        String rawLogs = kubernetesResourceService.getPodLogs(clusterId, namespace, podName, null,
                sinceSeconds != null ? null : clampTailLines(tailLines), sinceSeconds);

        Map<String, Object> data = buildStats(readK8sEntries(rawLogs));
        data.put("podName", podName);
        data.put("realtime", true);
        return data;
    }

    public Path resolveLogDirectory(String logDir) {
        if (logDir == null || logDir.isEmpty()) {
            logDir = "./logs/access";
        }

        Path dirPath = Paths.get(logDir);
        if (Files.exists(dirPath)) {
            return dirPath;
        }

        if (Paths.get(logDir).isAbsolute()) {
            return dirPath;
        }

        String userDir = System.getProperty("user.dir");
        dirPath = Paths.get(userDir, logDir);
        if (Files.exists(dirPath)) {
            return dirPath;
        }

        Path gatewayPath = Paths.get(userDir).getParent();
        if (gatewayPath != null) {
            Path myGatewayPath = gatewayPath.resolve("my-gateway");
            if (Files.exists(myGatewayPath)) {
                dirPath = myGatewayPath.resolve(logDir.replace("./", ""));
                if (Files.exists(dirPath)) {
                    return dirPath;
                }
            }
            dirPath = gatewayPath.resolve("my-gateway/logs/access");
            if (Files.exists(dirPath)) {
                return dirPath;
            }
        }

        return Paths.get(logDir);
    }

    private Path resolveLogFile(Path logDirPath, String date) {
        String effectiveDate = (date != null && !date.isEmpty())
                ? date
                : LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (!LOG_DATE.matcher(effectiveDate).matches()) {
            throw new InvalidLogDateException("Invalid date parameter, expected yyyy-MM-dd");
        }
        Path base = logDirPath.toAbsolutePath().normalize();
        Path filePath = base.resolve("access-" + effectiveDate + ".log").normalize();
        if (!filePath.startsWith(base)) {
            throw new InvalidLogDateException("Resolved log path escapes the configured log directory");
        }
        return filePath;
    }

    private static int clampTailLines(int tailLines) {
        return Math.min(Math.max(tailLines, 1), MAX_TAIL_LINES);
    }

    private List<Map<String, Object>> readFileEntries(Path filePath) {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<String> lines = Files.lines(filePath)) {
            lines.forEach(line -> {
                if (line != null && !line.trim().isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = objectMapper.readValue(line, Map.class);
                        entries.add(entry);
                    } catch (Exception e) {
                        // Malformed log lines do not invalidate the rest of the file.
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read access log file " + filePath.getFileName(), e);
        }
        return entries;
    }

    private List<Map<String, Object>> readK8sEntries(String rawLogs) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (rawLogs == null || rawLogs.isEmpty()) {
            return entries;
        }
        for (String line : rawLogs.split("\n")) {
            if (line == null || line.trim().isEmpty()) continue;
            if (!line.trim().startsWith("{")) continue;

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = objectMapper.readValue(line, Map.class);
                if (entry.get("method") == null || entry.get("path") == null) continue;
                entries.add(entry);
            } catch (Exception e) {
                // Skip malformed JSON lines from pod stdout.
            }
        }
        return entries;
    }

    private List<Map<String, Object>> filterEntries(
            List<Map<String, Object>> entries,
            String method,
            Integer statusCode,
            String path,
            String traceId) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            try {
                if (method != null && !method.isEmpty()
                        && !method.equalsIgnoreCase((String) entry.get("method"))) {
                    continue;
                }
                if (statusCode != null) {
                    Object code = entry.get("statusCode");
                    if (code == null || ((Number) code).intValue() != statusCode) {
                        continue;
                    }
                }
                if (path != null && !path.isEmpty()) {
                    String entryPath = (String) entry.get("path");
                    if (entryPath == null || !entryPath.contains(path)) {
                        continue;
                    }
                }
                if (traceId != null && !traceId.isEmpty()
                        && !traceId.equals(entry.get("traceId"))) {
                    continue;
                }
                filtered.add(entry);
            } catch (Exception e) {
                // A line with an unexpected field type is skipped like an unparsable line.
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> paginate(List<Map<String, Object>> entries, int page, int size) {
        int total = entries.size();
        int fromIndex = page * size;
        if (fromIndex >= total) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(fromIndex + size, total);
        return entries.subList(fromIndex, toIndex);
    }

    private Map<String, Object> buildStats(List<Map<String, Object>> entries) {
        long totalRequests = 0;
        long totalDuration = 0;
        Map<Integer, Integer> statusCodes = new HashMap<>();
        Map<String, Integer> paths = new HashMap<>();
        Map<String, Integer> methods = new HashMap<>();

        for (Map<String, Object> entry : entries) {
            try {
                totalRequests++;

                Object duration = entry.get("durationMs");
                if (duration != null) {
                    totalDuration += ((Number) duration).longValue();
                }

                Object status = entry.get("statusCode");
                if (status != null) {
                    statusCodes.merge(((Number) status).intValue(), 1, Integer::sum);
                }

                String path = (String) entry.get("path");
                if (path != null) {
                    paths.merge(path, 1, Integer::sum);
                }

                String method = (String) entry.get("method");
                if (method != null) {
                    methods.merge(method, 1, Integer::sum);
                }
            } catch (Exception e) {
                // Keep the counters already observed for this line, as the original endpoints did.
            }
        }

        List<Map<String, Object>> topPaths = paths.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("path", e.getKey(), "count", e.getValue()))
                .collect(Collectors.toList());

        double avgDuration = totalRequests > 0 ? (double) totalDuration / totalRequests : 0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRequests", totalRequests);
        data.put("avgDuration", Math.round(avgDuration * 100.0) / 100.0);
        data.put("statusCodes", statusCodes);
        data.put("topPaths", topPaths);
        data.put("methods", methods);
        return data;
    }

    public static class InvalidLogDateException extends IllegalArgumentException {
        public InvalidLogDateException(String message) {
            super(message);
        }
    }
}