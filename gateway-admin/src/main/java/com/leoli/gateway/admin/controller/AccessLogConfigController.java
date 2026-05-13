package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.model.AccessLogGlobalConfig;
import com.leoli.gateway.admin.model.AccessLogEntryEntity;
import com.leoli.gateway.admin.repository.AccessLogEntryRepository;
import com.leoli.gateway.admin.service.AccessLogConfigService;
import com.leoli.gateway.admin.service.KubernetesResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Access log configuration controller.
 * Provides API for managing global access log settings.
 * Uses ApiResponse for standardized response format.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/access-log")
@RequiredArgsConstructor
public class AccessLogConfigController extends BaseController {

    private final AccessLogConfigService accessLogConfigService;
    private final KubernetesResourceService kubernetesResourceService;
    private final AccessLogEntryRepository accessLogEntryRepository;
    private final ObjectMapper objectMapper;

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
     * Get current access log configuration.
     * @param instanceId Optional instance ID for instance-specific config
     */
    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfig(
            @RequestParam(required = false) String instanceId) {
        try {
            AccessLogGlobalConfig config = accessLogConfigService.getConfig(instanceId);

            Map<String, Object> configMap = new LinkedHashMap<>();
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

            return ResponseEntity.ok(ApiResponse.success(configMap));
        } catch (Exception e) {
            log.error("Failed to get access log config", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get config: " + e.getMessage()));
        }
    }

    /**
     * Save access log configuration.
     * @param request Configuration map, must include instanceId
     */
    @PostMapping("/config")
    public ResponseEntity<ApiResponse<Void>> saveConfig(@RequestBody Map<String, Object> request) {
        try {
            // Extract instanceId from request (required)
            String instanceId = (String) request.get("instanceId");
            if (instanceId == null || instanceId.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.badRequest("instanceId is required"));
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
                log.info("Access log config saved for instance {}: enabled={}, mode={}",
                        instanceId, config.isEnabled(), config.getDeployMode());
                return ResponseEntity.ok(ApiResponse.success("Configuration saved successfully"));
            } else {
                return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to save configuration"));
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid config: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to save access log config", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to save config: " + e.getMessage()));
        }
    }

    /**
     * Get deployment mode options.
     */
    @GetMapping("/deploy-modes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDeployModes() {
        List<AccessLogConfigService.DeployModeOption> options = accessLogConfigService.getDeployModeOptions();

        List<Map<String, Object>> modeList = options.stream()
                .map(opt -> {
                    Map<String, Object> modeMap = new LinkedHashMap<>();
                    modeMap.put("mode", opt.mode().name());
                    modeMap.put("description", opt.description());
                    modeMap.put("defaultPath", opt.defaultPath());
                    return modeMap;
                })
                .toList();

        return ResponseEntity.ok(ApiResponse.success(modeList));
    }

    /**
     * Get log level options.
     */
    @GetMapping("/log-levels")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLogLevelOptions() {
        List<Map<String, Object>> levelList = List.of(
                Map.of("level", "MINIMAL", "description", "Minimal: request line, status, duration"),
                Map.of("level", "NORMAL", "description", "Normal: + headers, auth info"),
                Map.of("level", "VERBOSE", "description", "Verbose: + request/response body")
        );
        return ResponseEntity.ok(ApiResponse.success(levelList));
    }

    /**
     * Get log format options.
     */
    @GetMapping("/log-formats")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLogFormatOptions() {
        List<Map<String, Object>> formatList = List.of(
                Map.of("format", "JSON", "description", "JSON format (recommended for log aggregation)"),
                Map.of("format", "TEXT", "description", "Human-readable text format")
        );
        return ResponseEntity.ok(ApiResponse.success(formatList));
    }

    /**
     * Reset to default configuration.
     * @param instanceId Optional instance ID for instance-specific config
     */
    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<Void>> resetConfig(
            @RequestParam(required = false) String instanceId) {
        try {
            AccessLogGlobalConfig defaultConfig = accessLogConfigService.createDefaultConfig();
            boolean saved = accessLogConfigService.saveConfig(defaultConfig, instanceId);

            if (saved) {
                return ResponseEntity.ok(ApiResponse.success("Configuration reset to default"));
            } else {
                return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to reset configuration"));
            }
        } catch (Exception e) {
            log.error("Failed to reset access log config", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to reset: " + e.getMessage()));
        }
    }

    /**
     * Get Fluent Bit configuration templates for different deployment modes.
     */
    @GetMapping("/fluent-bit-templates")
    public ResponseEntity<ApiResponse<Map<String, Map<String, String>>>> getFluentBitTemplates() {
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

            return ResponseEntity.ok(ApiResponse.success(templates));
        } catch (Exception e) {
            log.error("Failed to get Fluent Bit templates", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get templates: " + e.getMessage()));
        }
    }

    /**
     * Get output target recommendation based on deployment mode.
     */
    @GetMapping("/output-target-recommendation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOutputTargetRecommendation(
            @RequestParam String deployMode) {
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

            return ResponseEntity.ok(ApiResponse.success(recommendation));
        } catch (Exception e) {
            log.error("Failed to get output target recommendation", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get recommendation: " + e.getMessage()));
        }
    }

    /**
     * Get list of log files.
     */
    @GetMapping("/files")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLogFiles() {
        try {
            AccessLogGlobalConfig config = accessLogConfigService.getConfig();
            Path dirPath = resolveLogDirectory(config.getLogDirectory());

            if (dirPath == null || !Files.exists(dirPath)) {
                return ResponseEntity.ok(ApiResponse.success(Collections.emptyList(), "Log directory not found: " + config.getLogDirectory()));
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

            return ResponseEntity.ok(ApiResponse.success(files));
        } catch (Exception e) {
            log.error("Failed to list log files", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to list files: " + e.getMessage()));
        }
    }

    /**
     * Get log entries from a file.
     */
    @GetMapping("/entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLogEntries(
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String traceId) {
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
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("entries", Collections.emptyList());
                data.put("total", 0);
                data.put("page", page);
                data.put("size", size);
                data.put("file", fileName);
                return ResponseEntity.ok(ApiResponse.success(data));
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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entries", pagedEntries);
            data.put("total", total);
            data.put("page", page);
            data.put("size", size);
            data.put("file", fileName);

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to read log entries", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to read logs: " + e.getMessage()));
        }
    }

    /**
     * Get log statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLogStats(
            @RequestParam(required = false) String date) {
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
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("totalRequests", 0);
                data.put("avgDuration", 0.0);
                data.put("statusCodes", Collections.emptyMap());
                data.put("topPaths", Collections.emptyList());
                data.put("methods", Collections.emptyMap());
                return ResponseEntity.ok(ApiResponse.success(data));
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
                .collect(Collectors.toList());

            double avgDuration = totalRequests > 0 ? (double) totalDuration / totalRequests : 0;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalRequests", totalRequests);
            data.put("avgDuration", Math.round(avgDuration * 100.0) / 100.0);
            data.put("statusCodes", statusCodes);
            data.put("topPaths", topPaths);
            data.put("methods", methods);
            data.put("file", fileName);

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get log stats", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get stats: " + e.getMessage()));
        }
    }

    // ============================================================
    // Kubernetes Pod Access Log APIs (Real-time from stdout)
    // ============================================================

    /**
     * Get namespaces for K8S mode.
     */
    @GetMapping("/k8s/namespaces")
    public ResponseEntity<ApiResponse<List<String>>> getK8sNamespaces(@RequestParam Long clusterId) {
        try {
            List<String> namespaces = kubernetesResourceService.getClusterNamespaces(clusterId);
            return ResponseEntity.ok(ApiResponse.success(namespaces));
        } catch (Exception e) {
            log.error("Failed to get K8s namespaces", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get namespaces: " + e.getMessage()));
        }
    }

    /**
     * Get gateway pods for K8S mode.
     */
    @GetMapping("/k8s/pods")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getK8sGatewayPods(
            @RequestParam Long clusterId,
            @RequestParam(required = false) String namespace) {
        try {
            List<Map<String, Object>> allPods = kubernetesResourceService.getPods(clusterId, namespace);

            List<Map<String, Object>> gatewayPods = allPods.stream()
                    .filter(pod -> {
                        Map<String, String> labels = (Map<String, String>) pod.get("labels");
                        if (labels != null) {
                            String app = labels.get("app");
                            String appName = labels.get("app-name");
                            if (app != null && app.contains("gateway")) return true;
                            if (appName != null && appName.contains("gateway")) return true;
                        }
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

            return ResponseEntity.ok(ApiResponse.success(gatewayPods));
        } catch (Exception e) {
            log.error("Failed to get K8s gateway pods", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get pods: " + e.getMessage()));
        }
    }

    /**
     * Get access log entries from K8s Pod stdout.
     */
    @GetMapping("/k8s/entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getK8sLogEntries(
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
        try {
            String rawLogs = kubernetesResourceService.getPodLogs(clusterId, namespace, podName, null,
                sinceSeconds != null ? null : tailLines, sinceSeconds);

            List<Map<String, Object>> allEntries = new ArrayList<>();
            if (rawLogs != null && !rawLogs.isEmpty()) {
                for (String line : rawLogs.split("\n")) {
                    if (line == null || line.trim().isEmpty()) continue;
                    if (!line.trim().startsWith("{")) continue;

                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = objectMapper.readValue(line, Map.class);

                        if (entry.get("method") == null || entry.get("path") == null) continue;

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
                return tsB.compareTo(tsA);
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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entries", pagedEntries);
            data.put("total", total);
            data.put("page", page);
            data.put("size", size);
            data.put("podName", podName);
            data.put("namespace", namespace);
            data.put("realtime", true);

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get K8s log entries", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to read logs from Pod: " + e.getMessage()));
        }
    }

    /**
     * Get access log statistics from K8s Pod stdout.
     */
    @GetMapping("/k8s/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getK8sLogStats(
            @RequestParam Long clusterId,
            @RequestParam String namespace,
            @RequestParam String podName,
            @RequestParam(defaultValue = "500") int tailLines,
            @RequestParam(required = false) Integer sinceSeconds) {
        try {
            String rawLogs = kubernetesResourceService.getPodLogs(clusterId, namespace, podName, null,
                sinceSeconds != null ? null : tailLines, sinceSeconds);

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

            List<Map<String, Object>> topPaths = paths.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("path", e.getKey(), "count", e.getValue()))
                .toList();

            double avgDuration = totalRequests > 0 ? (double) totalDuration / totalRequests : 0;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalRequests", totalRequests);
            data.put("avgDuration", Math.round(avgDuration * 100.0) / 100.0);
            data.put("statusCodes", statusCodes);
            data.put("topPaths", topPaths);
            data.put("methods", methods);
            data.put("podName", podName);
            data.put("realtime", true);

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get K8s log stats", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get stats: " + e.getMessage()));
        }
    }

    @PostMapping("/collect")
    public ResponseEntity<ApiResponse<Map<String, Object>>> collectLogs(@RequestBody List<Map<String, Object>> logs) {
        try {
            if (logs == null || logs.isEmpty()) {
                Map<String, Object> data = new HashMap<>();
                data.put("count", 0);
                return ResponseEntity.ok(ApiResponse.success(data, "No logs to collect"));
            }

            String instanceId = null;
            List<AccessLogEntryEntity> entries = new ArrayList<>();

            for (Map<String, Object> logEntry : logs) {
                try {
                    AccessLogEntryEntity entity = new AccessLogEntryEntity();

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
                            entity.setLogTimestamp(LocalDateTime.parse(timestampStr.replace("Z", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        } catch (Exception e) {
                            entity.setLogTimestamp(LocalDateTime.now());
                        }
                    } else {
                        entity.setLogTimestamp(LocalDateTime.now());
                    }

                    entries.add(entity);
                } catch (Exception e) {
                    log.warn("Failed to parse log entry: {}", e.getMessage());
                }
            }

            if (!entries.isEmpty()) {
                accessLogEntryRepository.saveAll(entries);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("count", entries.size());

            return ResponseEntity.ok(ApiResponse.success(data, "Logs collected successfully"));
        } catch (Exception e) {
            log.error("Failed to collect logs", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to collect logs: " + e.getMessage()));
        }
    }

    @GetMapping("/history/entries")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHistoryEntries(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";

            LocalDateTime start = startTime != null
                ? LocalDateTime.parse(startTime.replace("Z", "").substring(0, 19))
                : LocalDateTime.now().minusHours(1);
            LocalDateTime end = endTime != null
                ? LocalDateTime.parse(endTime.replace("Z", "").substring(0, 19))
                : LocalDateTime.now();

            Pageable pageable = PageRequest.of(page, size, Sort.by("logTimestamp").descending());

            Page<AccessLogEntryEntity> logPage;

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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("entries", entries);
            data.put("total", logPage.getTotalElements());
            data.put("page", page);
            data.put("size", size);
            data.put("totalPages", logPage.getTotalPages());
            data.put("history", true);

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get history log entries", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get history logs: " + e.getMessage()));
        }
    }

    @GetMapping("/history/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHistoryStats(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";

            LocalDateTime start = startTime != null
                ? LocalDateTime.parse(startTime.replace("Z", "").substring(0, 19))
                : LocalDateTime.now().minusHours(1);
            LocalDateTime end = endTime != null
                ? LocalDateTime.parse(endTime.replace("Z", "").substring(0, 19))
                : LocalDateTime.now();

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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalRequests", totalRequests);
            data.put("avgDuration", avgDuration != null ? Math.round(avgDuration * 100.0) / 100.0 : 0.0);
            data.put("statusCodes", statusCodes);
            data.put("topPaths", topPaths);
            data.put("methods", methods);
            data.put("history", true);
            data.put("startTime", start.toString());
            data.put("endTime", end.toString());

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get history log stats", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get history stats: " + e.getMessage()));
        }
    }

    @GetMapping("/history/cleanup/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCleanupStats(
            @RequestParam(required = false) String instanceId,
            @RequestParam(defaultValue = "7") int retentionDays) {
        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";
            LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);

            long oldLogsCount = accessLogEntryRepository.countByInstanceIdAndLogTimestampBefore(effectiveInstanceId, beforeTime);
            long totalLogs = accessLogEntryRepository.count();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("oldLogsCount", oldLogsCount);
            data.put("totalLogs", totalLogs);
            data.put("retentionDays", retentionDays);
            data.put("beforeTime", beforeTime.toString());

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to get cleanup stats", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to get cleanup stats: " + e.getMessage()));
        }
    }

    @PostMapping("/history/cleanup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cleanupOldLogs(
            @RequestParam(required = false) String instanceId,
            @RequestParam(defaultValue = "7") int retentionDays) {
        try {
            String effectiveInstanceId = instanceId != null ? instanceId : "default";
            LocalDateTime beforeTime = LocalDateTime.now().minusDays(retentionDays);

            int deleted = accessLogEntryRepository.deleteOldLogsByInstanceId(effectiveInstanceId, beforeTime);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deletedCount", deleted);
            data.put("retentionDays", retentionDays);

            return ResponseEntity.ok(ApiResponse.success(data, "Cleanup completed"));
        } catch (Exception e) {
            log.error("Failed to cleanup old logs", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to cleanup logs: " + e.getMessage()));
        }
    }
}