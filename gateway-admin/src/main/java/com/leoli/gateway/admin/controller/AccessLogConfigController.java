package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AccessLogGlobalConfig;
import com.leoli.gateway.admin.service.AccessLogConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Access log configuration controller.
 * Provides API for managing global access log settings.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/access-log")
@RequiredArgsConstructor
public class AccessLogConfigController {

    private final AccessLogConfigService accessLogConfigService;

    /**
     * Get current access log configuration.
     * @param instanceId Optional instance ID for instance-specific config
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestParam(required = false) String instanceId) {
        Map<String, Object> result = new HashMap<>();

        try {
            AccessLogGlobalConfig config = accessLogConfigService.getConfig(instanceId);

            Map<String, Object> configMap = new HashMap<>();
            configMap.put("enabled", config.isEnabled());
            configMap.put("deployMode", config.getDeployMode().name());
            configMap.put("logDirectory", config.getLogDirectory());
            configMap.put("fileNamePattern", config.getFileNamePattern());
            configMap.put("logFormat", config.getLogFormat().name());
            configMap.put("logLevel", config.getLogLevel().name());
            configMap.put("logRequestHeaders", config.isLogRequestHeaders());
            configMap.put("logResponseHeaders", config.isLogResponseHeaders());
            configMap.put("logRequestBody", config.isLogRequestBody());
            configMap.put("logResponseBody", config.isLogResponseBody());
            configMap.put("maxBodyLength", config.getMaxBodyLength());
            configMap.put("samplingRate", config.getSamplingRate());
            configMap.put("sensitiveFields", config.getSensitiveFields());
            configMap.put("maxFileSizeMb", config.getMaxFileSizeMb());
            configMap.put("maxBackupFiles", config.getMaxBackupFiles());
            configMap.put("logToConsole", config.isLogToConsole());
            configMap.put("includeAuthInfo", config.isIncludeAuthInfo());

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", configMap);

        } catch (Exception e) {
            log.error("Failed to get access log config", e);
            result.put("code", 500);
            result.put("message", "Failed to get config: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Save access log configuration.
     * @param request Configuration map, must include instanceId
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Extract instanceId from request (required)
            String instanceId = (String) request.get("instanceId");
            if (instanceId == null || instanceId.isEmpty()) {
                result.put("code", 400);
                result.put("message", "instanceId is required");
                return ResponseEntity.ok(result);
            }

            // Get existing config or create default
            AccessLogGlobalConfig config = accessLogConfigService.getConfig(instanceId);

            // Update config from request
            if (request.containsKey("enabled")) {
                config.setEnabled((Boolean) request.get("enabled"));
            }
            if (request.containsKey("deployMode")) {
                config.setDeployMode(AccessLogGlobalConfig.DeployMode.valueOf((String) request.get("deployMode")));
            }
            if (request.containsKey("logDirectory")) {
                config.setLogDirectory((String) request.get("logDirectory"));
            }
            if (request.containsKey("fileNamePattern")) {
                config.setFileNamePattern((String) request.get("fileNamePattern"));
            }
            if (request.containsKey("logFormat")) {
                config.setLogFormat(AccessLogGlobalConfig.LogFormat.valueOf((String) request.get("logFormat")));
            }
            if (request.containsKey("logLevel")) {
                config.setLogLevel(AccessLogGlobalConfig.LogLevel.valueOf((String) request.get("logLevel")));
            }
            if (request.containsKey("logRequestHeaders")) {
                config.setLogRequestHeaders((Boolean) request.get("logRequestHeaders"));
            }
            if (request.containsKey("logResponseHeaders")) {
                config.setLogResponseHeaders((Boolean) request.get("logResponseHeaders"));
            }
            if (request.containsKey("logRequestBody")) {
                config.setLogRequestBody((Boolean) request.get("logRequestBody"));
            }
            if (request.containsKey("logResponseBody")) {
                config.setLogResponseBody((Boolean) request.get("logResponseBody"));
            }
            if (request.containsKey("maxBodyLength")) {
                config.setMaxBodyLength(((Number) request.get("maxBodyLength")).intValue());
            }
            if (request.containsKey("samplingRate")) {
                config.setSamplingRate(((Number) request.get("samplingRate")).intValue());
            }
            if (request.containsKey("sensitiveFields")) {
                @SuppressWarnings("unchecked")
                List<String> fields = (List<String>) request.get("sensitiveFields");
                config.setSensitiveFields(fields);
            }
            if (request.containsKey("maxFileSizeMb")) {
                config.setMaxFileSizeMb(((Number) request.get("maxFileSizeMb")).intValue());
            }
            if (request.containsKey("maxBackupFiles")) {
                config.setMaxBackupFiles(((Number) request.get("maxBackupFiles")).intValue());
            }
            if (request.containsKey("logToConsole")) {
                config.setLogToConsole((Boolean) request.get("logToConsole"));
            }
            if (request.containsKey("includeAuthInfo")) {
                config.setIncludeAuthInfo((Boolean) request.get("includeAuthInfo"));
            }

            // Only use default directory if user didn't provide one
            if (config.getLogDirectory() == null || config.getLogDirectory().isEmpty()) {
                config.setLogDirectory(AccessLogGlobalConfig.getDefaultLogDirectory(config.getDeployMode()));
            }

            boolean saved = accessLogConfigService.saveConfig(config, instanceId);

            if (saved) {
                result.put("code", 200);
                result.put("message", "Configuration saved successfully");
                log.info("Access log config saved for instance {}: enabled={}, mode={}",
                        instanceId, config.isEnabled(), config.getDeployMode());
            } else {
                result.put("code", 500);
                result.put("message", "Failed to save configuration");
            }

        } catch (IllegalArgumentException e) {
            log.warn("Invalid config: {}", e.getMessage());
            result.put("code", 400);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save access log config", e);
            result.put("code", 500);
            result.put("message", "Failed to save config: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get deployment mode options.
     */
    @GetMapping("/deploy-modes")
    public ResponseEntity<Map<String, Object>> getDeployModes() {
        Map<String, Object> result = new HashMap<>();

        List<AccessLogConfigService.DeployModeOption> options = accessLogConfigService.getDeployModeOptions();

        List<Map<String, Object>> modeList = options.stream()
                .map(opt -> {
                    Map<String, Object> modeMap = new HashMap<>();
                    modeMap.put("mode", opt.mode().name());
                    modeMap.put("description", opt.description());
                    modeMap.put("defaultPath", opt.defaultPath());
                    return modeMap;
                })
                .toList();

        result.put("code", 200);
        result.put("message", "success");
        result.put("data", modeList);

        return ResponseEntity.ok(result);
    }

    /**
     * Get log level options.
     */
    @GetMapping("/log-levels")
    public ResponseEntity<Map<String, Object>> getLogLevelOptions() {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> levelList = List.of(
                Map.of("level", "MINIMAL", "description", "Minimal: request line, status, duration"),
                Map.of("level", "NORMAL", "description", "Normal: + headers, auth info"),
                Map.of("level", "VERBOSE", "description", "Verbose: + request/response body")
        );

        result.put("code", 200);
        result.put("message", "success");
        result.put("data", levelList);

        return ResponseEntity.ok(result);
    }

    /**
     * Get log format options.
     */
    @GetMapping("/log-formats")
    public ResponseEntity<Map<String, Object>> getLogFormatOptions() {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> formatList = List.of(
                Map.of("format", "JSON", "description", "JSON format (recommended for log aggregation)"),
                Map.of("format", "TEXT", "description", "Human-readable text format")
        );

        result.put("code", 200);
        result.put("message", "success");
        result.put("data", formatList);

        return ResponseEntity.ok(result);
    }

    /**
     * Reset to default configuration.
     * @param instanceId Optional instance ID for instance-specific config
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetConfig(
            @RequestParam(required = false) String instanceId) {
        Map<String, Object> result = new HashMap<>();

        try {
            AccessLogGlobalConfig defaultConfig = accessLogConfigService.createDefaultConfig();
            boolean saved = accessLogConfigService.saveConfig(defaultConfig, instanceId);

            if (saved) {
                result.put("code", 200);
                result.put("message", "Configuration reset to default");
            } else {
                result.put("code", 500);
                result.put("message", "Failed to reset configuration");
            }

        } catch (Exception e) {
            log.error("Failed to reset access log config", e);
            result.put("code", 500);
            result.put("message", "Failed to reset: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Resolve log directory - try multiple possible locations.
     */
    private Path resolveLogDirectory(String logDir) {
        if (logDir == null || logDir.isEmpty()) {
            logDir = "./logs/access";
        }

        // Try the configured path first
        Path dirPath = Paths.get(logDir);
        if (Files.exists(dirPath)) {
            return dirPath;
        }

        // Try absolute path
        if (Paths.get(logDir).isAbsolute()) {
            return dirPath;
        }

        // Try relative to user.dir (project root)
        String userDir = System.getProperty("user.dir");
        dirPath = Paths.get(userDir, logDir);
        if (Files.exists(dirPath)) {
            return dirPath;
        }

        // Try sibling my-gateway directory (common dev setup)
        Path gatewayPath = Paths.get(userDir).getParent();
        if (gatewayPath != null) {
            Path myGatewayPath = gatewayPath.resolve("my-gateway");
            if (Files.exists(myGatewayPath)) {
                dirPath = myGatewayPath.resolve(logDir.replace("./", ""));
                if (Files.exists(dirPath)) {
                    return dirPath;
                }
            }
            // Also try gatewayPath directly
            dirPath = gatewayPath.resolve("my-gateway/logs/access");
            if (Files.exists(dirPath)) {
                return dirPath;
            }
        }

        // Return original path
        return Paths.get(logDir);
    }

    /**
     * Get list of log files.
     */
    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> getLogFiles() {
        Map<String, Object> result = new HashMap<>();

        try {
            AccessLogGlobalConfig config = accessLogConfigService.getConfig();
            Path dirPath = resolveLogDirectory(config.getLogDirectory());

            if (dirPath == null || !Files.exists(dirPath)) {
                result.put("code", 200);
                result.put("data", Collections.emptyList());
                result.put("message", "Log directory not found: " + config.getLogDirectory());
                return ResponseEntity.ok(result);
            }

            List<Map<String, Object>> files;
            try (Stream<Path> stream = Files.list(dirPath)) {
                files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return -Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }))
                    .map(p -> {
                        Map<String, Object> file = new HashMap<>();
                        try {
                            file.put("name", p.getFileName().toString());
                            file.put("size", Files.size(p));
                            file.put("lastModified", Files.getLastModifiedTime(p).toMillis());
                        } catch (IOException e) {
                            log.warn("Failed to get file info: {}", p, e);
                        }
                        return file;
                    })
                    .collect(Collectors.toList());
            }

            result.put("code", 200);
            result.put("data", files);

        } catch (Exception e) {
            log.error("Failed to list log files", e);
            result.put("code", 500);
            result.put("message", "Failed to list files: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get log entries from a file.
     */
    @GetMapping("/entries")
    public ResponseEntity<Map<String, Object>> getLogEntries(
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String traceId) {

        Map<String, Object> result = new HashMap<>();

        try {
            AccessLogGlobalConfig config = accessLogConfigService.getConfig();
            Path logDirPath = resolveLogDirectory(config.getLogDirectory());

            // Determine file name
            String fileName;
            if (date != null && !date.isEmpty()) {
                fileName = "access-" + date + ".log";
            } else {
                fileName = "access-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".log";
            }

            Path filePath = logDirPath.resolve(fileName);
            if (!Files.exists(filePath)) {
                result.put("code", 200);
                result.put("data", Map.of(
                    "entries", Collections.emptyList(),
                    "total", 0,
                    "page", page,
                    "size", size,
                    "file", fileName
                ));
                return ResponseEntity.ok(result);
            }

            // Read and parse log entries
            List<Map<String, Object>> allEntries = new ArrayList<>();
            try (Stream<String> lines = Files.lines(filePath)) {
                lines.forEach(line -> {
                    if (line != null && !line.trim().isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> entry = objectMapper.readValue(line, Map.class);

                            // Apply filters
                            if (method != null && !method.isEmpty()) {
                                if (!method.equalsIgnoreCase((String) entry.get("method"))) {
                                    return;
                                }
                            }
                            if (statusCode != null) {
                                Object code = entry.get("statusCode");
                                if (code == null || ((Number) code).intValue() != statusCode) {
                                    return;
                                }
                            }
                            if (path != null && !path.isEmpty()) {
                                String entryPath = (String) entry.get("path");
                                if (entryPath == null || !entryPath.contains(path)) {
                                    return;
                                }
                            }
                            if (traceId != null && !traceId.isEmpty()) {
                                if (!traceId.equals(entry.get("traceId"))) {
                                    return;
                                }
                            }

                            allEntries.add(entry);
                        } catch (Exception e) {
                            // Skip malformed lines
                        }
                    }
                });
            }

            // Paginate
            int total = allEntries.size();
            int fromIndex = page * size;
            int toIndex = Math.min(fromIndex + size, total);

            List<Map<String, Object>> pagedEntries;
            if (fromIndex >= total) {
                pagedEntries = Collections.emptyList();
            } else {
                pagedEntries = allEntries.subList(fromIndex, toIndex);
            }

            result.put("code", 200);
            result.put("data", Map.of(
                "entries", pagedEntries,
                "total", total,
                "page", page,
                "size", size,
                "file", fileName
            ));

        } catch (Exception e) {
            log.error("Failed to read log entries", e);
            result.put("code", 500);
            result.put("message", "Failed to read logs: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get log statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getLogStats(
            @RequestParam(required = false) String date) {

        Map<String, Object> result = new HashMap<>();

        try {
            AccessLogGlobalConfig config = accessLogConfigService.getConfig();
            Path logDirPath = resolveLogDirectory(config.getLogDirectory());

            String fileName;
            if (date != null && !date.isEmpty()) {
                fileName = "access-" + date + ".log";
            } else {
                fileName = "access-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".log";
            }

            Path filePath = logDirPath.resolve(fileName);
            if (!Files.exists(filePath)) {
                result.put("code", 200);
                result.put("data", Map.of(
                    "totalRequests", 0,
                    "avgDuration", 0.0,
                    "statusCodes", Collections.emptyMap(),
                    "topPaths", Collections.emptyList(),
                    "methods", Collections.emptyMap()
                ));
                return ResponseEntity.ok(result);
            }

            // Calculate statistics
            long totalRequests = 0;
            long totalDuration = 0;
            Map<Integer, Integer> statusCodes = new HashMap<>();
            Map<String, Integer> paths = new HashMap<>();
            Map<String, Integer> methods = new HashMap<>();

            try (Stream<String> lines = Files.lines(filePath)) {
                for (String line : lines.collect(Collectors.toList())) {
                    if (line == null || line.trim().isEmpty()) continue;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = objectMapper.readValue(line, Map.class);
                        totalRequests++;

                        Object duration = entry.get("durationMs");
                        if (duration != null) {
                            totalDuration += ((Number) duration).longValue();
                        }

                        Object status = entry.get("statusCode");
                        if (status != null) {
                            int code = ((Number) status).intValue();
                            statusCodes.merge(code, 1, Integer::sum);
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
                        // Skip malformed lines
                    }
                }
            }

            // Get top 10 paths
            List<Map<String, Object>> topPaths = paths.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("path", e.getKey(), "count", e.getValue()))
                .collect(Collectors.toList());

            double avgDuration = totalRequests > 0 ? (double) totalDuration / totalRequests : 0;

            result.put("code", 200);
            result.put("data", Map.of(
                "totalRequests", totalRequests,
                "avgDuration", Math.round(avgDuration * 100.0) / 100.0,
                "statusCodes", statusCodes,
                "topPaths", topPaths,
                "methods", methods,
                "file", fileName
            ));

        } catch (Exception e) {
            log.error("Failed to get log stats", e);
            result.put("code", 500);
            result.put("message", "Failed to get stats: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}