package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.PluginEntity;
import com.leoli.gateway.admin.model.PluginJarEntity;
import com.leoli.gateway.admin.repository.PluginBindingRepository;
import com.leoli.gateway.admin.repository.PluginJarRepository;
import com.leoli.gateway.admin.repository.PluginRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Plugin Service.
 * Manages custom route plugins:
 * - JAR file upload and storage
 * - Plugin metadata extraction and storage
 * - CRUD operations for plugins
 * - Publishing plugin config to Nacos for gateway hot-loading
 * <p>
 * Note: This service handles metadata extraction from JAR files.
 * The actual ClassLoader loading and Bean registration happens in my-gateway module.
 *
 * @author leoli
 */
@Service
@Slf4j
public class PluginService {

    @Autowired
    private PluginRepository pluginRepository;

    @Autowired
    private PluginJarRepository pluginJarRepository;

    @Autowired
    private PluginBindingRepository pluginBindingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigCenterService configCenterService;

    // Default JAR storage directory
    private static final String DEFAULT_JAR_DIR = "./plugins";
    private String jarStorageDir = DEFAULT_JAR_DIR;

    // Nacos config keys
    private static final String PLUGINS_INDEX = "config.gateway.metadata.plugins-index";
    private static final String PLUGIN_PREFIX = "config.gateway.plugin-";

    /**
     * Upload and register a plugin from JAR file.
     *
     * @param file       the uploaded JAR file
     * @param instanceId the gateway instance ID
     * @return the created PluginEntity
     */
    @Transactional
    public PluginEntity uploadPlugin(MultipartFile file, String instanceId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("JAR 文件为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.endsWith(".jar")) {
            throw new IllegalArgumentException("文件必须是 JAR 格式（.jar 后缀）");
        }

        Path jarPath = null;
        try {
            // Save JAR to disk
            String jarId = UUID.randomUUID().toString();
            Path jarDir = Paths.get(jarStorageDir);
            if (!Files.exists(jarDir)) {
                Files.createDirectories(jarDir);
            }

            jarPath = jarDir.resolve(jarId + ".jar");
            file.transferTo(jarPath.toFile());

            // Calculate SHA256 hash
            String sha256Hash = calculateHash(jarPath.toFile());

            // Check for duplicate
            if (pluginJarRepository.existsBySha256Hash(sha256Hash)) {
                Files.deleteIfExists(jarPath);
                throw new IllegalArgumentException("JAR 文件已存在（重复的文件哈希值）");
            }

            // Analyze JAR to find GatewayFilterFactory classes (throws IllegalArgumentException on invalid JAR)
            File jarFile = jarPath.toFile();
            List<PluginClassInfo> discoveredClasses = analyzeJarForFactoryClasses(jarFile);

            // Use first discovered class as the plugin
            PluginClassInfo classInfo = discoveredClasses.get(0);
            String pluginId = UUID.randomUUID().toString();
            String pluginName = classInfo.extractPluginName();

            // Check if plugin name already exists
            if (instanceId != null && pluginRepository.existsByPluginNameAndInstanceId(pluginName, instanceId)) {
                Files.deleteIfExists(jarPath);
                throw new IllegalArgumentException("插件名称已存在: " + pluginName);
            }

            // Generate config schema (throws IllegalArgumentException on invalid schema)
            String configSchema = generateConfigSchema(jarFile, classInfo);

            // Create plugin entity
            PluginEntity plugin = new PluginEntity();
            plugin.setPluginId(pluginId);
            plugin.setPluginName(pluginName);
            plugin.setPluginClass(classInfo.getFactoryClassName());
            plugin.setConfigClass(classInfo.getConfigClassName());
            plugin.setVersion("1.0.0");
            plugin.setDescription("自定义插件: " + pluginName);
            plugin.setCategory("custom");
            plugin.setConfigSchema(configSchema);
            plugin.setJarPath(jarPath.toString());
            plugin.setJarHash(sha256Hash);
            plugin.setEnabled(true);
            plugin.setLoaded(false);
            plugin.setRegistered(false);
            plugin.setInstanceId(instanceId);

            plugin = pluginRepository.save(plugin);

            // Create JAR entity
            PluginJarEntity jarEntity = new PluginJarEntity();
            jarEntity.setJarId(jarId);
            jarEntity.setPluginId(pluginId);
            jarEntity.setOriginalFilename(originalFilename);
            jarEntity.setStoredPath(jarPath.toString());
            jarEntity.setFileSize(file.getSize());
            jarEntity.setSha256Hash(sha256Hash);

            pluginJarRepository.save(jarEntity);

            // Publish plugin config to Nacos for gateway hot-loading
            publishPluginConfig(plugin);

            // Rebuild plugins index
            rebuildPluginsIndex(instanceId);

            log.info("Plugin uploaded successfully: pluginId={}, name={}, class={}",
                    pluginId, pluginName, classInfo.getFactoryClassName());

            return plugin;

        } catch (IllegalArgumentException e) {
            // User-facing validation errors - clean up and rethrow
            safeDeleteFile(jarPath);
            throw e;
        } catch (Exception e) {
            // Unexpected errors - clean up and wrap
            safeDeleteFile(jarPath);
            log.error("Failed to upload plugin: {}", originalFilename, e);
            throw new RuntimeException("插件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * Safely delete a file without throwing exception.
     */
    private void safeDeleteFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception e) {
                log.warn("Failed to delete file: {}", path, e);
            }
        }
    }

    /**
     * Get all plugins.
     */
    public List<PluginEntity> getAllPlugins() {
        return pluginRepository.findAll();
    }

    /**
     * Get all plugins by instance ID (includes built-in plugins visible to all instances).
     */
    public List<PluginEntity> getPluginsByInstanceId(String instanceId) {
        // Built-in plugins (jarPath = 'BUILTIN') are visible to all instances
        if (instanceId == null || instanceId.isEmpty()) {
            return getAllPlugins();
        }
        return pluginRepository.findVisiblePluginsForInstance(instanceId);
    }

    /**
     * Get plugin by plugin ID.
     */
    public Optional<PluginEntity> getPluginById(String pluginId) {
        return pluginRepository.findByPluginId(pluginId);
    }

    /**
     * Get plugin by name.
     */
    public Optional<PluginEntity> getPluginByName(String pluginName) {
        return pluginRepository.findByPluginName(pluginName);
    }

    /**
     * Enable a plugin.
     */
    @Transactional
    public PluginEntity enablePlugin(String pluginId) {
        Optional<PluginEntity> pluginOpt = pluginRepository.findByPluginId(pluginId);
        if (pluginOpt.isEmpty()) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }

        PluginEntity plugin = pluginOpt.get();
        plugin.setEnabled(true);
        plugin = pluginRepository.save(plugin);

        // Publish to Nacos
        publishPluginConfig(plugin);
        rebuildPluginsIndex(plugin.getInstanceId());

        log.info("Plugin enabled: {}", pluginId);
        return plugin;
    }

    /**
     * Disable a plugin.
     */
    @Transactional
    public PluginEntity disablePlugin(String pluginId) {
        Optional<PluginEntity> pluginOpt = pluginRepository.findByPluginId(pluginId);
        if (pluginOpt.isEmpty()) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }

        PluginEntity plugin = pluginOpt.get();
        plugin.setEnabled(false);
        plugin = pluginRepository.save(plugin);

        // Remove from Nacos
        removePluginConfig(plugin);
        rebuildPluginsIndex(plugin.getInstanceId());

        log.info("Plugin disabled: {}", pluginId);
        return plugin;
    }

    /**
     * Delete a plugin.
     */
    @Transactional
    public void deletePlugin(String pluginId) {
        Optional<PluginEntity> pluginOpt = pluginRepository.findByPluginId(pluginId);
        if (pluginOpt.isEmpty()) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }

        PluginEntity plugin = pluginOpt.get();

        // Delete all bindings for this plugin
        pluginBindingRepository.deleteByPluginId(pluginId);

        // Delete JAR record and file
        Optional<PluginJarEntity> jarOpt = pluginJarRepository.findByPluginId(pluginId);
        if (jarOpt.isPresent()) {
            PluginJarEntity jar = jarOpt.get();
            try {
                Files.deleteIfExists(Paths.get(jar.getStoredPath()));
            } catch (Exception e) {
                log.warn("Failed to delete JAR file: {}", jar.getStoredPath(), e);
            }
            pluginJarRepository.delete(jar);
        }

        // Delete plugin entity
        pluginRepository.delete(plugin);

        // Remove from Nacos
        removePluginConfig(plugin);
        rebuildPluginsIndex(plugin.getInstanceId());

        log.info("Plugin deleted: {}", pluginId);
    }

    /**
     * Update plugin metadata.
     */
    @Transactional
    public PluginEntity updatePlugin(String pluginId, String description, String descriptionEn, String category, String icon, String color) {
        Optional<PluginEntity> pluginOpt = pluginRepository.findByPluginId(pluginId);
        if (pluginOpt.isEmpty()) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }

        PluginEntity plugin = pluginOpt.get();
        if (description != null) plugin.setDescription(description);
        if (descriptionEn != null) plugin.setDescriptionEn(descriptionEn);
        if (category != null) plugin.setCategory(category);
        if (icon != null) plugin.setIcon(icon);
        if (color != null) plugin.setColor(color);

        plugin = pluginRepository.save(plugin);
        log.info("Plugin metadata updated: {}", pluginId);
        return plugin;
    }

    /**
     * Get plugin statistics.
     */
    public PluginStats getPluginStats(String instanceId) {
        List<PluginEntity> plugins = instanceId != null
                ? pluginRepository.findByInstanceId(instanceId)
                : pluginRepository.findAll();

        PluginStats stats = new PluginStats();
        stats.setTotal(plugins.size());
        stats.setEnabled(plugins.stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())).count());
        stats.setDisabled(plugins.stream().filter(p -> Boolean.FALSE.equals(p.getEnabled())).count());
        stats.setLoaded(plugins.stream().filter(p -> Boolean.TRUE.equals(p.getLoaded())).count());
        stats.setRegistered(plugins.stream().filter(p -> Boolean.TRUE.equals(p.getRegistered())).count());

        return stats;
    }

    /**
     * Get config schema for a plugin.
     */
    public String getConfigSchema(String pluginId) {
        Optional<PluginEntity> pluginOpt = pluginRepository.findByPluginId(pluginId);
        if (pluginOpt.isEmpty()) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }
        return pluginOpt.get().getConfigSchema();
    }

    // ========== Private Helper Methods ==========

    /**
     * Calculate SHA256 hash of a file.
     */
    private String calculateHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Failed to calculate hash", e);
            return "";
        }
    }

    /**
     * Analyze JAR file for AbstractGatewayFilterFactory implementations.
     * Validates JAR structure and class naming conventions.
     *
     * @throws IllegalArgumentException if JAR is invalid or no valid factory classes found
     */
    private List<PluginClassInfo> analyzeJarForFactoryClasses(File jarFile) throws IllegalArgumentException {
        List<PluginClassInfo> classes = new ArrayList<>();
        List<String> classNames = new ArrayList<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    String className = entry.getName()
                            .replace('/', '.')
                            .substring(0, entry.getName().length() - 6);
                    classNames.add(className);
                }
            }

            // Validate: JAR must contain at least one class file
            if (classNames.isEmpty()) {
                throw new IllegalArgumentException("JAR 文件不包含任何 Java 类文件");
            }

            // Filter for valid GatewayFilterFactory implementations
            for (String className : classNames) {
                // Skip inner classes (except $Config which we handle separately)
                if (className.contains("$") && !className.endsWith("$Config")) {
                    continue;
                }

                // Must end with GatewayFilterFactory (exact naming convention)
                if (className.endsWith("GatewayFilterFactory")) {
                    // Validate: class name must have a proper package structure
                    int lastDot = className.lastIndexOf('.');
                    if (lastDot <= 0) {
                        throw new IllegalArgumentException("类 '" + className + "' 缺少包名，GatewayFilterFactory 必须在有效的包中");
                    }

                    PluginClassInfo info = new PluginClassInfo();
                    info.setFactoryClassName(className);
                    info.setSimpleName(className.substring(lastDot + 1));

                    // Check for Config inner class
                    String configClassName = className + "$Config";
                    if (classNames.contains(configClassName)) {
                        info.setConfigClassName(configClassName);
                        info.setHasConfig(true);
                    } else {
                        info.setHasConfig(false);
                        log.warn("GatewayFilterFactory '{}' 没有 Config 内部类，将使用默认空配置", className);
                    }

                    classes.add(info);
                    log.debug("Found valid GatewayFilterFactory: {}", className);
                }
            }

            // Validate: must find at least one valid factory class
            if (classes.isEmpty()) {
                throw new IllegalArgumentException(
                    "JAR 中未找到有效的 GatewayFilterFactory 实现。\n" +
                    "请确保：\n" +
                    "1. 类名以 'GatewayFilterFactory' 结尾（如: MyFilterGatewayFilterFactory）\n" +
                    "2. 类继承自 org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory\n" +
                    "3. 类在有效的包中（不能是默认包）\n" +
                    "发现的类数量: " + classNames.size() + "，均不符合命名规范");
            }

        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无法读取 JAR 文件: " + e.getMessage(), e);
        }

        return classes;
    }

    /**
     * Generate config schema from JAR.
     * First tries to read META-INF/plugin-schema.json, then generates from Config class fields.
     *
     * @throws IllegalArgumentException if plugin-schema.json is invalid
     */
    private String generateConfigSchema(File jarFile, PluginClassInfo classInfo) throws IllegalArgumentException {
        // Try to read custom schema from JAR
        String customSchema = readPluginSchemaFromJar(jarFile);
        if (customSchema != null && !customSchema.isEmpty()) {
            // Validate JSON format
            try {
                objectMapper.readTree(customSchema); // Validate it's valid JSON
                return customSchema;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    "META-INF/plugin-schema.json 格式无效: " + e.getMessage() + "\n" +
                    "请确保文件是有效的 JSON 格式");
            }
        }

        // Generate default schema
        if (classInfo.hasConfig()) {
            return generateDefaultSchema(classInfo);
        }

        return "{\"fields\": []}";
    }

    /**
     * Read META-INF/plugin-schema.json from JAR.
     */
    private String readPluginSchemaFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry schemaEntry = jar.getJarEntry("META-INF/plugin-schema.json");
            if (schemaEntry != null) {
                try (InputStream is = jar.getInputStream(schemaEntry)) {
                    return new BufferedReader(new InputStreamReader(is))
                            .lines()
                            .collect(java.util.stream.Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            log.debug("No plugin-schema.json found");
        }
        return null;
    }

    /**
     * Generate default schema based on class info.
     */
    private String generateDefaultSchema(PluginClassInfo classInfo) {
        try {
            Map<String, Object> schema = new LinkedHashMap<>();
            List<Map<String, Object>> fields = new ArrayList<>();

            // Add a placeholder field since we can't inspect actual fields without loading
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put("name", "config");
            placeholder.put("type", "textarea");
            placeholder.put("label", "Configuration JSON");
            placeholder.put("description", "Enter plugin configuration as JSON");
            fields.add(placeholder);

            schema.put("fields", fields);
            schema.put("hasSubSchemas", false);
            schema.put("note", "Schema auto-generated. Add META-INF/plugin-schema.json for custom schema.");

            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"fields\": []}";
        }
    }

    /**
     * Set custom JAR storage directory.
     */
    public void setJarStorageDir(String dir) {
        this.jarStorageDir = dir;
    }

    // ========== Nacos Configuration Publishing ==========

    /**
     * Publish plugin configuration to Nacos.
     */
    private void publishPluginConfig(PluginEntity plugin) {
        try {
            String pluginDataId = PLUGIN_PREFIX + plugin.getPluginId();

            // Create config object for gateway
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pluginId", plugin.getPluginId());
            config.put("pluginName", plugin.getPluginName());
            config.put("pluginClass", plugin.getPluginClass());
            config.put("configClass", plugin.getConfigClass());
            config.put("jarPath", plugin.getJarPath());
            config.put("version", plugin.getVersion());
            config.put("category", plugin.getCategory());
            config.put("enabled", plugin.getEnabled());
            config.put("loaded", plugin.getLoaded());
            config.put("registered", plugin.getRegistered());
            config.put("instanceId", plugin.getInstanceId());

            configCenterService.publishConfig(pluginDataId, null, config);
            log.info("Plugin config published to Nacos: {}", pluginDataId);
        } catch (Exception e) {
            log.error("Failed to publish plugin config to Nacos: {}", plugin.getPluginId(), e);
        }
    }

    /**
     * Remove plugin configuration from Nacos.
     */
    private void removePluginConfig(PluginEntity plugin) {
        try {
            String pluginDataId = PLUGIN_PREFIX + plugin.getPluginId();
            configCenterService.removeConfig(pluginDataId, null);
            log.info("Plugin config removed from Nacos: {}", pluginDataId);
        } catch (Exception e) {
            log.error("Failed to remove plugin config from Nacos: {}", plugin.getPluginId(), e);
        }
    }

    /**
     * Rebuild plugins index for gateway.
     */
    private void rebuildPluginsIndex(String instanceId) {
        try {
            // Get all enabled plugins for this instance
            List<PluginEntity> enabledPlugins = instanceId != null
                    ? pluginRepository.findByInstanceIdAndEnabledTrue(instanceId)
                    : pluginRepository.findByEnabledTrue();

            List<String> pluginIds = enabledPlugins.stream()
                    .map(PluginEntity::getPluginId)
                    .collect(Collectors.toList());

            configCenterService.publishConfig(PLUGINS_INDEX, null, pluginIds);
            log.info("Plugins index rebuilt: {} plugins", pluginIds.size());
        } catch (Exception e) {
            log.error("Failed to rebuild plugins index", e);
        }
    }

    // ========== Inner Classes ==========

    /**
     * Plugin class info from JAR analysis.
     */
    private static class PluginClassInfo {
        private String factoryClassName;
        private String configClassName;
        private String simpleName;
        private boolean hasConfig;

        public String extractPluginName() {
            String name = simpleName;
            if (name != null && name.endsWith("GatewayFilterFactory")) {
                name = name.substring(0, name.length() - "GatewayFilterFactory".length());
            }
            return name;
        }

        // Getters and setters
        public String getFactoryClassName() {
            return factoryClassName;
        }

        public void setFactoryClassName(String factoryClassName) {
            this.factoryClassName = factoryClassName;
        }

        public String getConfigClassName() {
            return configClassName;
        }

        public void setConfigClassName(String configClassName) {
            this.configClassName = configClassName;
        }

        public String getSimpleName() {
            return simpleName;
        }

        public void setSimpleName(String simpleName) {
            this.simpleName = simpleName;
        }

        public boolean hasConfig() {
            return hasConfig;
        }

        public void setHasConfig(boolean hasConfig) {
            this.hasConfig = hasConfig;
        }
    }

    /**
     * Plugin statistics.
     */
    public static class PluginStats {
        private long total;
        private long enabled;
        private long disabled;
        private long loaded;
        private long registered;

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }

        public long getEnabled() {
            return enabled;
        }

        public void setEnabled(long enabled) {
            this.enabled = enabled;
        }

        public long getDisabled() {
            return disabled;
        }

        public void setDisabled(long disabled) {
            this.disabled = disabled;
        }

        public long getLoaded() {
            return loaded;
        }

        public void setLoaded(long loaded) {
            this.loaded = loaded;
        }

        public long getRegistered() {
            return registered;
        }

        public void setRegistered(long registered) {
            this.registered = registered;
        }
    }
}