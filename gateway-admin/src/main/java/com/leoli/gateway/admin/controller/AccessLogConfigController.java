package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AccessLogGlobalConfig;
import com.leoli.gateway.admin.service.AccessLogConfigService;
import com.leoli.gateway.admin.service.KubernetesResourceService;
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
    private final KubernetesResourceService kubernetesResourceService;
    private final com.leoli.gateway.admin.repository.AccessLogEntryRepository accessLogEntryRepository;

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

    /**
     * Get Fluent Bit configuration templates for different deployment modes.
     * Provides ready-to-use configuration snippets for log collection.
     */
    @GetMapping("/fluent-bit-templates")
    public ResponseEntity<Map<String, Object>> getFluentBitTemplates() {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Map<String, String>> templates = new LinkedHashMap<>();

            // K8S DaemonSet template (stdout mode)
            Map<String, String> k8sTemplate = new LinkedHashMap<>();
            k8sTemplate.put("name", "Kubernetes DaemonSet (stdout)");
            k8sTemplate.put("description", "Cloud-native best practice: stdout + Fluent Bit DaemonSet");
            k8sTemplate.put("config", """
# Fluent Bit DaemonSet 配置示例
[SERVICE]
    Flush         5
    Log_Level     info
    Parsers_File  parsers.conf

[INPUT]
    Name              tail
    Path              /var/log/containers/*gateway*.log
    Parser            json
    Tag               gateway.access
    Mem_Buf_Limit     50MB

[FILTER]
    Name              kubernetes
    Match             gateway.*
    Kube_URL          https://kubernetes.default.svc:443
    Kube_Tag_Prefix   gateway.access.

[OUTPUT]
    Name              http
    Match             gateway.*
    Host              gateway-admin-service
    Port              8080
    URI               /api/access-log/collect
    Format            json
""");
            k8sTemplate.put("configMap", """
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
  namespace: gateway
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush         5
        Log_Level     info
        Parsers_File  parsers.conf

    [INPUT]
        Name              tail
        Path              /var/log/containers/*gateway*.log
        Parser            json
        Tag               gateway.access
        Mem_Buf_Limit     50MB

    [FILTER]
        Name              kubernetes
        Match             gateway.*
        Kube_URL          https://kubernetes.default.svc:443
        Kube_Tag_Prefix   gateway.access.

    [OUTPUT]
        Name              http
        Match             gateway.*
        Host              gateway-admin-service
        Port              8080
        URI               /api/access-log/collect
        Format            json

  parsers.conf: |
    [PARSER]
        Name   json
        Format json
""");
            templates.put("K8S", k8sTemplate);

            // Docker sidecar template
            Map<String, String> dockerTemplate = new LinkedHashMap<>();
            dockerTemplate.put("name", "Docker Sidecar / Host Agent");
            dockerTemplate.put("description", "Two options: sidecar container or host agent");
            dockerTemplate.put("config", """
# Docker Compose sidecar 示例
services:
  gateway:
    volumes:
      - access-logs:/app/logs/access

  fluent-bit:
    image: fluent/fluent-bit:latest
    volumes:
      - access-logs:/app/logs/access
    config:
      [INPUT]
        Name    tail
        Path    /app/logs/access/access-*.log
        Parser  json

      [OUTPUT]
        Name    http
        Host    gateway-admin
        Port    8080
""");
            templates.put("DOCKER", dockerTemplate);

            // Local file tailing template
            Map<String, String> localTemplate = new LinkedHashMap<>();
            localTemplate.put("name", "Local File Tailing");
            localTemplate.put("description", "Direct file tailing on host machine");
            localTemplate.put("config", """
# Fluent Bit 本地采集配置
[INPUT]
    Name    tail
    Path    ./logs/access/access-*.log
    Parser  json
    Tag     gateway.access

[OUTPUT]
    Name    http
    Host    localhost
    Port    8080
    URI     /api/access-log/collect
""");
            templates.put("LOCAL", localTemplate);

            // Custom path template
            Map<String, String> customTemplate = new LinkedHashMap<>();
            customTemplate.put("name", "Custom Path (注意权限)");
            customTemplate.put("description", "User-defined path with wildcard support");
            customTemplate.put("config", """
# Fluent Bit 自定义路径配置
[INPUT]
    Name    tail
    Path    /your/custom/path/access-*.log
    Parser  json

[OUTPUT]
    Name    http
    Host    gateway-admin
    Port    8080
""");
            templates.put("CUSTOM", customTemplate);

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", templates);

        } catch (Exception e) {
            log.error("Failed to get Fluent Bit templates", e);
            result.put("code", 500);
            result.put("message", "Failed to get templates: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get output target recommendation based on deployment mode.
     * Helps frontend determine the best output mode for each deployment.
     */
    @GetMapping("/output-target-recommendation")
    public ResponseEntity<Map<String, Object>> getOutputTargetRecommendation(
            @RequestParam String deployMode) {
        Map<String, Object> result = new HashMap<>();

        try {
            String recommendedTarget;
            String reason;
            boolean disableFileOptions = false;

            switch (deployMode) {
                case "K8S":
                    recommendedTarget = "stdout";
                    reason = "Kubernetes automatically collects stdout logs to /var/log/containers/*.log. Fluent Bit DaemonSet can directly collect without file I/O overhead.";
                    disableFileOptions = true;
                    break;
                case "DOCKER":
                    recommendedTarget = "both";
                    reason = "Docker environment benefits from both stdout (for collection) and file (for backup). Ensure volume mount for file persistence.";
                    disableFileOptions = false;
                    break;
                case "LOCAL":
                    recommendedTarget = "file";
                    reason = "Local deployment writes logs to file. Fluent Bit or Filebeat can tail the file directory.";
                    disableFileOptions = false;
                    break;
                case "CUSTOM":
                    recommendedTarget = "file";
                    reason = "Custom path requires file output. Ensure the path exists and Gateway has write permissions.";
                    disableFileOptions = false;
                    break;
                default:
                    recommendedTarget = "file";
                    reason = "Default recommendation for unknown deployment mode.";
                    disableFileOptions = false;
            }

            Map<String, Object> recommendation = new LinkedHashMap<>();
            recommendation.put("target", recommendedTarget);
            recommendation.put("reason", reason);
            recommendation.put("disableFileOptions", disableFileOptions);
            recommendation.put("deployMode", deployMode);

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", recommendation);

        } catch (Exception e) {
            log.error("Failed to get output target recommendation", e);
            result.put("code", 500);
            result.put("message", "Failed to get recommendation: " + e.getMessage());
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

    // ============================================================
    // Kubernetes Pod Access Log APIs (Real-time from stdout)
    // ============================================================

    /**
     * Get namespaces for K8S mode.
     * Returns list of namespaces in the cluster.
     */
    @GetMapping("/k8s/namespaces")
    public ResponseEntity<Map<String, Object>> getK8sNamespaces(@RequestParam Long clusterId) {
        Map<String, Object> result = new HashMap<>();

        try {
            List<String> namespaces = kubernetesResourceService.getClusterNamespaces(clusterId);
            result.put("code", 200);
            result.put("data", namespaces);
        } catch (Exception e) {
            log.error("Failed to get K8s namespaces", e);
            result.put("code", 500);
            result.put("message", "Failed to get namespaces: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get gateway pods for K8S mode.
     * Returns list of pods that can be selected for log viewing.
     */
    @GetMapping("/k8s/pods")
    public ResponseEntity<Map<String, Object>> getK8sGatewayPods(
            @RequestParam Long clusterId,
            @RequestParam(required = false) String namespace) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get all pods and filter by gateway label
            List<Map<String, Object>> allPods = kubernetesResourceService.getPods(clusterId, namespace);
            
            // Filter pods that match gateway (by label app=my-gateway or name contains gateway)
            List<Map<String, Object>> gatewayPods = allPods.stream()
                    .filter(pod -> {
                        Map<String, String> labels = (Map<String, String>) pod.get("labels");
                        if (labels != null) {
                            // Check common gateway labels
                            String app = labels.get("app");
                            String appName = labels.get("app-name");
                            if (app != null && app.contains("gateway")) return true;
                            if (appName != null && appName.contains("gateway")) return true;
                        }
                        // Also check pod name
                        String name = (String) pod.get("name");
                        if (name != null && name.contains("gateway")) return true;
                        return false;
                    })
                    .map(pod -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", pod.get("name"));
                        info.put("namespace", pod.get("namespace"));
                        info.put("phase", pod.get("phase"));
                        info.put("podIP", pod.get("podIP"));
                        return info;
                    })
                    .toList();

            result.put("code", 200);
            result.put("data", gatewayPods);

        } catch (Exception e) {
            log.error("Failed to get K8s gateway pods", e);
            result.put("code", 500);
            result.put("message", "Failed to get pods: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get access log entries from K8s Pod stdout.
     * Supports both real-time (tailLines) and history (sinceSeconds) modes.
     */
    @GetMapping("/k8s/entries")
    public ResponseEntity<Map<String, Object>> getK8sLogEntries(
            @RequestParam Long clusterId,
            @RequestParam String namespace,
            @RequestParam String podName,
            @RequestParam(defaultValue = "500") int tailLines,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Integer sinceSeconds) {

        Map<String, Object> result = new HashMap<>();

        try {
            // Get raw logs from Pod stdout
            // If sinceSeconds is provided, use it for history query; otherwise use tailLines for real-time
            String rawLogs = kubernetesResourceService.getPodLogs(clusterId, namespace, podName, null, 
                sinceSeconds != null ? null : tailLines, sinceSeconds);

            // Parse JSON log entries
            List<Map<String, Object>> allEntries = new ArrayList<>();
            if (rawLogs != null && !rawLogs.isEmpty()) {
                for (String line : rawLogs.split("\n")) {
                    if (line == null || line.trim().isEmpty()) continue;
                    
                    // Skip non-JSON lines (like startup logs, errors, etc.)
                    if (!line.trim().startsWith("{")) continue;
                    
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = objectMapper.readValue(line, Map.class);

                        // Check if this is an access log entry (has required fields)
                        if (entry.get("method") == null || entry.get("path") == null) continue;

                        // Apply filters
                        if (method != null && !method.isEmpty()) {
                            if (!method.equalsIgnoreCase((String) entry.get("method"))) continue;
                        }
                        if (statusCode != null) {
                            Object code = entry.get("statusCode");
                            if (code == null || ((Number) code).intValue() != statusCode) continue;
                        }
                        if (path != null && !path.isEmpty()) {
                            String entryPath = (String) entry.get("path");
                            if (entryPath == null || !entryPath.contains(path)) continue;
                        }
                        if (traceId != null && !traceId.isEmpty()) {
                            if (!traceId.equals(entry.get("traceId"))) continue;
                        }

                        allEntries.add(entry);
                    } catch (Exception e) {
                        // Skip malformed JSON lines
                    }
                }
            }

            // Sort by timestamp (newest first)
            allEntries.sort((a, b) -> {
                String tsA = (String) a.get("@timestamp");
                String tsB = (String) b.get("@timestamp");
                if (tsA == null || tsB == null) return 0;
                return tsB.compareTo(tsA);  // Descending
            });

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
                "podName", podName,
                "namespace", namespace,
                "realtime", true  // Mark as realtime
            ));

        } catch (Exception e) {
            log.error("Failed to get K8s log entries", e);
            result.put("code", 500);
            result.put("message", "Failed to read logs from Pod: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get access log statistics from K8s Pod stdout.
     * Supports both real-time (tailLines) and history (sinceSeconds) modes.
     */
    @GetMapping("/k8s/stats")
    public ResponseEntity<Map<String, Object>> getK8sLogStats(
            @RequestParam Long clusterId,
            @RequestParam String namespace,
            @RequestParam String podName,
            @RequestParam(defaultValue = "500") int tailLines,
            @RequestParam(required = false) Integer sinceSeconds) {

        Map<String, Object> result = new HashMap<>();

        try {
            // If sinceSeconds is provided, use it for history query; otherwise use tailLines for real-time
            String rawLogs = kubernetesResourceService.getPodLogs(clusterId, namespace, podName, null, 
                sinceSeconds != null ? null : tailLines, sinceSeconds);

            // Calculate statistics
            long totalRequests = 0;
            long totalDuration = 0;
            Map<Integer, Integer> statusCodes = new HashMap<>();
            Map<String, Integer> paths = new HashMap<>();
            Map<String, Integer> methods = new HashMap<>();

            if (rawLogs != null && !rawLogs.isEmpty()) {
                for (String line : rawLogs.split("\n")) {
                    if (line == null || line.trim().isEmpty()) continue;
                    if (!line.trim().startsWith("{")) continue;

                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = objectMapper.readValue(line, Map.class);

                        // Only count access log entries
                        if (entry.get("method") == null || entry.get("path") == null) continue;

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

                        String pathStr = (String) entry.get("path");
                        if (pathStr != null) {
                            paths.merge(pathStr, 1, Integer::sum);
                        }

                        String methodStr = (String) entry.get("method");
                        if (methodStr != null) {
                            methods.merge(methodStr, 1, Integer::sum);
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
                .toList();

            double avgDuration = totalRequests > 0 ? (double) totalDuration / totalRequests : 0;

            result.put("code", 200);
            result.put("data", Map.of(
                "totalRequests", totalRequests,
                "avgDuration", Math.round(avgDuration * 100.0) / 100.0,
                "statusCodes", statusCodes,
                "topPaths", topPaths,
                "methods", methods,
                "podName", podName,
                "realtime", true
            ));

        } catch (Exception e) {
            log.error("Failed to get K8s log stats", e);
            result.put("code", 500);
            result.put("message", "Failed to get stats: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collectLogs(@RequestBody List<Map<String, Object>> logs) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (logs == null || logs.isEmpty()) {
                result.put("code", 200);
                result.put("message", "No logs to collect");
                result.put("count", 0);
                return ResponseEntity.ok(result);
            }

            String instanceId = null;
            List<com.leoli.gateway.admin.model.AccessLogEntryEntity> entries = new ArrayList<>();

            for (Map<String, Object> logEntry : logs) {
                try {
                    com.leoli.gateway.admin.model.AccessLogEntryEntity entity = new com.leoli.gateway.admin.model.AccessLogEntryEntity();

                    if (instanceId == null && logEntry.containsKey("instanceId")) {
                        instanceId = (String) logEntry.get("instanceId");
                    }
                    entity.setInstanceId(instanceId != null ? instanceId : "default");

                    entity.setTraceId((String) logEntry.get("traceId"));
                    entity.setRequestId((String) logEntry.get("requestId"));
                    entity.setRouteId((String) logEntry.get("routeId"));
                    entity.setServiceId((String) logEntry.get("serviceId"));

                    String method = (String) logEntry.get("method");
                    entity.setMethod(method != null ? method : "UNKNOWN");

                    String path = (String) logEntry.get("path");
                    entity.setPath(path != null ? path : "/");

                    entity.setQueryString((String) logEntry.get("query"));
                    entity.setClientIp((String) logEntry.get("clientIp"));
                    entity.setUserAgent((String) logEntry.get("userAgent"));

                    Object statusCode = logEntry.get("statusCode");
                    entity.setStatusCode(statusCode != null ? ((Number) statusCode).intValue() : 0);

                    Object duration = logEntry.get("durationMs");
                    entity.setDurationMs(duration != null ? ((Number) duration).longValue() : 0L);

                    entity.setAuthType((String) logEntry.get("authType"));
                    entity.setAuthPolicy((String) logEntry.get("authPolicy"));
                    entity.setAuthUser((String) logEntry.get("authUser"));
                    entity.setErrorMessage((String) logEntry.get("errorMessage"));

                    String timestampStr = (String) logEntry.get("@timestamp");
                    if (timestampStr != null) {
                        try {
                            entity.setLogTimestamp(java.time.LocalDateTime.parse(timestampStr.replace("Z", ""), java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        } catch (Exception e) {
                            entity.setLogTimestamp(java.time.LocalDateTime.now());
                        }
                    } else {
                        entity.setLogTimestamp(java.time.LocalDateTime.now());
                    }

                    entries.add(entity);
                } catch (Exception e) {
                    log.warn("Failed to parse log entry: {}", e.getMessage());
                }
            }

            if (!entries.isEmpty()) {
                accessLogEntryRepository.saveAll(entries);
            }

            result.put("code", 200);
            result.put("message", "Logs collected successfully");
            result.put("count", entries.size());

        } catch (Exception e) {
            log.error("Failed to collect logs", e);
            result.put("code", 500);
            result.put("message", "Failed to collect logs: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/history/entries")
    public ResponseEntity<Map<String, Object>> getHistoryEntries(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Map<String, Object> result = new HashMap<>();

        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";

            java.time.LocalDateTime start = startTime != null
                ? java.time.LocalDateTime.parse(startTime.replace("Z", "").substring(0, 19))
                : java.time.LocalDateTime.now().minusHours(1);
            java.time.LocalDateTime end = endTime != null
                ? java.time.LocalDateTime.parse(endTime.replace("Z", "").substring(0, 19))
                : java.time.LocalDateTime.now();

            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                page, size, org.springframework.data.domain.Sort.by("logTimestamp").descending()
            );

            org.springframework.data.domain.Page<com.leoli.gateway.admin.model.AccessLogEntryEntity> logPage;

            if (traceId != null && !traceId.isEmpty()) {
                logPage = accessLogEntryRepository.findByInstanceIdAndTraceId(effectiveInstanceId, traceId, pageable);
            } else if (method != null && statusCode != null && path != null) {
                logPage = accessLogEntryRepository.findByInstanceIdAndMethodAndTimeRange(
                    effectiveInstanceId, method, start, end, pageable);
            } else if (statusCode != null) {
                logPage = accessLogEntryRepository.findByInstanceIdAndStatusCodeAndTimeRange(
                    effectiveInstanceId, statusCode, start, end, pageable);
            } else if (path != null && !path.isEmpty()) {
                logPage = accessLogEntryRepository.findByInstanceIdAndPathLikeAndTimeRange(
                    effectiveInstanceId, "%" + path + "%", start, end, pageable);
            } else {
                logPage = accessLogEntryRepository.findByInstanceIdAndTimeRange(
                    effectiveInstanceId, start, end, pageable);
            }

            List<Map<String, Object>> entries = logPage.getContent().stream()
                .map(entity -> {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", entity.getId());
                    entry.put("@timestamp", entity.getLogTimestamp().toString());
                    entry.put("traceId", entity.getTraceId());
                    entry.put("requestId", entity.getRequestId());
                    entry.put("routeId", entity.getRouteId());
                    entry.put("serviceId", entity.getServiceId());
                    entry.put("method", entity.getMethod());
                    entry.put("path", entity.getPath());
                    entry.put("query", entity.getQueryString());
                    entry.put("clientIp", entity.getClientIp());
                    entry.put("userAgent", entity.getUserAgent());
                    entry.put("statusCode", entity.getStatusCode());
                    entry.put("durationMs", entity.getDurationMs());
                    entry.put("authType", entity.getAuthType());
                    entry.put("authPolicy", entity.getAuthPolicy());
                    entry.put("authUser", entity.getAuthUser());
                    entry.put("errorMessage", entity.getErrorMessage());
                    return entry;
                })
                .toList();

            result.put("code", 200);
            result.put("data", Map.of(
                "entries", entries,
                "total", logPage.getTotalElements(),
                "page", page,
                "size", size,
                "totalPages", logPage.getTotalPages(),
                "history", true
            ));

        } catch (Exception e) {
            log.error("Failed to get history log entries", e);
            result.put("code", 500);
            result.put("message", "Failed to get history logs: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/history/stats")
    public ResponseEntity<Map<String, Object>> getHistoryStats(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        Map<String, Object> result = new HashMap<>();

        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";

            java.time.LocalDateTime start = startTime != null
                ? java.time.LocalDateTime.parse(startTime.replace("Z", "").substring(0, 19))
                : java.time.LocalDateTime.now().minusHours(1);
            java.time.LocalDateTime end = endTime != null
                ? java.time.LocalDateTime.parse(endTime.replace("Z", "").substring(0, 19))
                : java.time.LocalDateTime.now();

            long totalRequests = accessLogEntryRepository.countByInstanceIdAndTimeRange(effectiveInstanceId, start, end);
            Double avgDuration = accessLogEntryRepository.avgDurationByInstanceIdAndTimeRange(effectiveInstanceId, start, end);

            List<Object[]> statusCodeCounts = accessLogEntryRepository.countByStatusCodeGroupByInstanceIdAndTimeRange(effectiveInstanceId, start, end);
            Map<String, Integer> statusCodes = new HashMap<>();
            for (Object[] row : statusCodeCounts) {
                statusCodes.put(String.valueOf(row[0]), ((Number) row[1]).intValue());
            }

            List<Object[]> methodCounts = accessLogEntryRepository.countByMethodGroupByInstanceIdAndTimeRange(effectiveInstanceId, start, end);
            Map<String, Integer> methods = new HashMap<>();
            for (Object[] row : methodCounts) {
                methods.put(String.valueOf(row[0]), ((Number) row[1]).intValue());
            }

            List<Object[]> topPathsRaw = accessLogEntryRepository.topPathsByInstanceIdAndTimeRange(effectiveInstanceId, start, end, 10);
            List<Map<String, Object>> topPaths = new ArrayList<>();
            for (Object[] row : topPathsRaw) {
                Map<String, Object> pathInfo = new HashMap<>();
                pathInfo.put("path", row[0]);
                pathInfo.put("count", ((Number) row[1]).intValue());
                topPaths.add(pathInfo);
            }

            result.put("code", 200);
            result.put("data", Map.of(
                "totalRequests", totalRequests,
                "avgDuration", avgDuration != null ? Math.round(avgDuration * 100.0) / 100.0 : 0.0,
                "statusCodes", statusCodes,
                "topPaths", topPaths,
                "methods", methods,
                "history", true,
                "startTime", start.toString(),
                "endTime", end.toString()
            ));

        } catch (Exception e) {
            log.error("Failed to get history log stats", e);
            result.put("code", 500);
            result.put("message", "Failed to get history stats: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/history/cleanup/stats")
    public ResponseEntity<Map<String, Object>> getCleanupStats(
            @RequestParam(required = false) String instanceId,
            @RequestParam(defaultValue = "7") int retentionDays) {
        Map<String, Object> result = new HashMap<>();

        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";
            java.time.LocalDateTime beforeTime = java.time.LocalDateTime.now().minusDays(retentionDays);

            long oldLogsCount = accessLogEntryRepository.countByInstanceIdAndLogTimestampBefore(effectiveInstanceId, beforeTime);
            long totalLogs = accessLogEntryRepository.count();

            result.put("code", 200);
            result.put("data", Map.of(
                "oldLogsCount", oldLogsCount,
                "totalLogs", totalLogs,
                "retentionDays", retentionDays,
                "beforeTime", beforeTime.toString()
            ));

        } catch (Exception e) {
            log.error("Failed to get cleanup stats", e);
            result.put("code", 500);
            result.put("message", "Failed to get cleanup stats: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/history/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupOldLogs(
            @RequestParam(required = false) String instanceId,
            @RequestParam(defaultValue = "7") int retentionDays) {
        Map<String, Object> result = new HashMap<>();

        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";
            java.time.LocalDateTime beforeTime = java.time.LocalDateTime.now().minusDays(retentionDays);

            int deleted = accessLogEntryRepository.deleteOldLogsByInstanceId(effectiveInstanceId, beforeTime);

            result.put("code", 200);
            result.put("message", "Cleanup completed");
            result.put("data", Map.of(
                "deletedCount", deleted,
                "retentionDays", retentionDays
            ));

        } catch (Exception e) {
            log.error("Failed to cleanup old logs", e);
            result.put("code", 500);
            result.put("message", "Failed to cleanup logs: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}