package com.leoli.gateway.plugin;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of plugin discovery from a JAR file.
 * Contains discovered factory classes and the ClassLoader used.
 *
 * @author leoli
 */
@Data
@Slf4j
public class PluginDiscoveryResult {

    /**
     * List of discovered factory class info.
     */
    private List<ClassInfo> factoryClasses = new ArrayList<>();

    /**
     * ClassLoader used to load the JAR.
     */
    private URLClassLoader classLoader;

    /**
     * Original JAR file path.
     */
    private String jarPath;

    /**
     * Whether discovery was successful.
     */
    private boolean success = false;

    /**
     * Error message if discovery failed.
     */
    private String errorMessage;

    public PluginDiscoveryResult() {
    }

    public PluginDiscoveryResult(URLClassLoader classLoader, String jarPath) {
        this.classLoader = classLoader;
        this.jarPath = jarPath;
        this.success = true;
    }

    public PluginDiscoveryResult(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
    }

    /**
     * Info about a discovered factory class.
     */
    @Data
    public static class ClassInfo {
        /**
         * Full class name of the factory.
         */
        private String factoryClassName;

        /**
         * Full class name of the Config inner class.
         */
        private String configClassName;

        /**
         * Simple name (without package) of the factory.
         * This will be used as the plugin name (filter name).
         */
        private String simpleName;

        /**
         * The loaded Class object.
         */
        private Class<?> factoryClass;

        /**
         * The loaded Config Class object.
         */
        private Class<?> configClass;

        /**
         * Whether this class has a valid Config inner class.
         */
        private boolean hasConfig;

        /**
         * Extract plugin name from factory class name.
         * Convention: XGatewayFilterFactory -> X (used as filter name)
         */
        public String extractPluginName() {
            String name = simpleName;
            // Remove "GatewayFilterFactory" suffix if present
            if (name.endsWith("GatewayFilterFactory")) {
                name = name.substring(0, name.length() - "GatewayFilterFactory".length());
            }
            return name;
        }
    }

    /**
     * Check if there are any discovered classes.
     */
    public boolean hasDiscoveredClasses() {
        return factoryClasses != null && !factoryClasses.isEmpty();
    }

    /**
     * Get the first discovered class (for single-plugin JARs).
     */
    public ClassInfo getFirstClass() {
        if (hasDiscoveredClasses()) {
            return factoryClasses.get(0);
        }
        return null;
    }
}