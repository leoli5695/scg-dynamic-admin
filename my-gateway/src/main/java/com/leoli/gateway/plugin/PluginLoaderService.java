package com.leoli.gateway.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Plugin Loader Service.
 * Responsible for:
 * - Loading JAR files using URLClassLoader
 * - Discovering AbstractGatewayFilterFactory implementations
 * - Extracting Config inner classes
 * - Generating JSON Schema from Config class fields
 *
 * @author leoli
 */
@Service
@Slf4j
public class PluginLoaderService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * ClassLoader cache: pluginId -> ClassLoader
     */
    private final ConcurrentHashMap<String, URLClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();

    /**
     * Loaded class cache: pluginId -> Factory Class
     */
    private final ConcurrentHashMap<String, Class<?>> loadedFactoryClasses = new ConcurrentHashMap<>();

    /**
     * Discover plugin classes from a JAR file.
     *
     * @param jarFile the JAR file to analyze
     * @return discovery result containing found classes and ClassLoader
     */
    public PluginDiscoveryResult discoverPluginClasses(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return new PluginDiscoveryResult("JAR file does not exist");
        }

        try {
            // Create URLClassLoader with the JAR
            URL jarUrl = jarFile.toURI().toURL();
            URLClassLoader loader = new URLClassLoader(
                new URL[]{jarUrl},
                Thread.currentThread().getContextClassLoader()
            );

            PluginDiscoveryResult result = new PluginDiscoveryResult(loader, jarFile.getAbsolutePath());

            // Scan JAR for classes
            List<String> classNames = scanJarForClassNames(jarFile);

            // Try to load each class and check if it's a GatewayFilterFactory
            for (String className : classNames) {
                try {
                    Class<?> clazz = loader.loadClass(className);

                    // Check if extends AbstractGatewayFilterFactory
                    if (isGatewayFilterFactory(clazz)) {
                        PluginDiscoveryResult.ClassInfo classInfo = createClassInfo(clazz, loader);
                        result.getFactoryClasses().add(classInfo);
                        log.info("Discovered GatewayFilterFactory: {}", className);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    log.debug("Could not load class: {} - {}", className, e.getMessage());
                } catch (Exception e) {
                    log.warn("Error loading class {}: {}", className, e.getMessage());
                }
            }

            if (!result.hasDiscoveredClasses()) {
                result.setSuccess(false);
                result.setErrorMessage("No AbstractGatewayFilterFactory implementations found in JAR");
                // Close the loader since we won't use it
                closeClassLoader(loader);
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to discover plugin classes from JAR: {}", jarFile.getAbsolutePath(), e);
            return new PluginDiscoveryResult("Failed to analyze JAR: " + e.getMessage());
        }
    }

    /**
     * Load a specific plugin class and cache it.
     *
     * @param pluginId the plugin ID for caching
     * @param jarFile the JAR file
     * @param factoryClassName the factory class to load
     * @return the loaded Class
     */
    public Class<?> loadPluginClass(String pluginId, File jarFile, String factoryClassName) {
        try {
            // Check if already loaded
            Class<?> cachedClass = loadedFactoryClasses.get(pluginId);
            if (cachedClass != null) {
                return cachedClass;
            }

            // Create or reuse ClassLoader
            URLClassLoader loader = pluginClassLoaders.computeIfAbsent(pluginId, id -> {
                try {
                    URL jarUrl = jarFile.toURI().toURL();
                    return new URLClassLoader(
                        new URL[]{jarUrl},
                        Thread.currentThread().getContextClassLoader()
                    );
                } catch (Exception e) {
                    log.error("Failed to create ClassLoader for plugin: {}", pluginId, e);
                    return null;
                }
            });

            if (loader == null) {
                throw new PluginLoadException("Failed to create ClassLoader for plugin: " + pluginId);
            }

            // Load the factory class
            Class<?> factoryClass = loader.loadClass(factoryClassName);

            // Validate it's a GatewayFilterFactory
            if (!isGatewayFilterFactory(factoryClass)) {
                throw new PluginLoadException("Class does not extend AbstractGatewayFilterFactory: " + factoryClassName);
            }

            // Cache the loaded class
            loadedFactoryClasses.put(pluginId, factoryClass);

            log.info("Loaded plugin class: {} for plugin: {}", factoryClassName, pluginId);
            return factoryClass;

        } catch (Exception e) {
            log.error("Failed to load plugin class: {} for plugin: {}", factoryClassName, pluginId, e);
            throw new PluginLoadException("Failed to load plugin class: " + e.getMessage(), e);
        }
    }

    /**
     * Get the cached ClassLoader for a plugin.
     */
    public URLClassLoader getClassLoader(String pluginId) {
        return pluginClassLoaders.get(pluginId);
    }

    /**
     * Get the cached factory class for a plugin.
     */
    public Class<?> getLoadedFactoryClass(String pluginId) {
        return loadedFactoryClasses.get(pluginId);
    }

    /**
     * Unload a plugin - close ClassLoader and remove from cache.
     */
    public void unloadPlugin(String pluginId) {
        URLClassLoader loader = pluginClassLoaders.remove(pluginId);
        if (loader != null) {
            closeClassLoader(loader);
        }
        loadedFactoryClasses.remove(pluginId);
        log.info("Unloaded plugin: {}", pluginId);
    }

    /**
     * Calculate SHA256 hash of a file.
     */
    public String calculateHash(File file) {
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
            log.error("Failed to calculate hash for file: {}", file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Read META-INF/plugin-schema.json from JAR if present.
     */
    public String readPluginSchemaFromJar(File jarFile) {
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
            log.debug("No plugin-schema.json found in JAR: {}", jarFile.getName());
        }
        return null;
    }

    /**
     * Cleanup on destroy.
     */
    @PreDestroy
    public void destroy() {
        log.info("Cleaning up plugin ClassLoaders...");
        pluginClassLoaders.forEach((pluginId, loader) -> {
            closeClassLoader(loader);
        });
        pluginClassLoaders.clear();
        loadedFactoryClasses.clear();
    }

    // ========== Private Helper Methods ==========

    /**
     * Scan JAR file and extract all class names.
     */
    private List<String> scanJarForClassNames(File jarFile) {
        List<String> classNames = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    // Convert path to class name
                    String className = entry.getName()
                        .replace('/', '.')
                        .substring(0, entry.getName().length() - 6);
                    classNames.add(className);
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan JAR file: {}", jarFile.getAbsolutePath(), e);
        }
        return classNames;
    }

    /**
     * Check if a class extends AbstractGatewayFilterFactory.
     */
    private boolean isGatewayFilterFactory(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }

        // Check superclass hierarchy
        Class<?> superClass = clazz.getSuperclass();
        while (superClass != null) {
            if (superClass.equals(AbstractGatewayFilterFactory.class)) {
                return true;
            }
            superClass = superClass.getSuperclass();
        }

        return false;
    }

    /**
     * Create ClassInfo from a loaded factory class.
     */
    private PluginDiscoveryResult.ClassInfo createClassInfo(Class<?> factoryClass, URLClassLoader loader) {
        PluginDiscoveryResult.ClassInfo info = new PluginDiscoveryResult.ClassInfo();
        info.setFactoryClassName(factoryClass.getName());
        info.setSimpleName(factoryClass.getSimpleName());
        info.setFactoryClass(factoryClass);

        // Find Config inner class
        Class<?> configClass = findConfigClass(factoryClass);
        if (configClass != null) {
            info.setConfigClassName(configClass.getName());
            info.setConfigClass(configClass);
            info.setHasConfig(true);
        } else {
            info.setHasConfig(false);
            log.warn("No Config inner class found for factory: {}", factoryClass.getName());
        }

        return info;
    }

    /**
     * Find the Config inner class in a factory class.
     */
    private Class<?> findConfigClass(Class<?> factoryClass) {
        // Look for public static inner class named "Config"
        for (Class<?> innerClass : factoryClass.getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals("Config")) {
                return innerClass;
            }
        }

        // Try to load from class name convention: FactoryClass$Config
        try {
            String configClassName = factoryClass.getName() + "$Config";
            return factoryClass.getClassLoader().loadClass(configClassName);
        } catch (ClassNotFoundException e) {
            log.debug("Config class not found by name convention for: {}", factoryClass.getName());
        }

        return null;
    }

    /**
     * Close a ClassLoader safely.
     */
    private void closeClassLoader(URLClassLoader loader) {
        if (loader != null) {
            try {
                loader.close();
                log.debug("ClassLoader closed successfully");
            } catch (IOException e) {
                log.warn("Failed to close ClassLoader: {}", e.getMessage());
            }
        }
    }
}