package com.leoli.gateway.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin Registry Service.
 * Responsible for:
 * - Dynamically registering GatewayFilterFactory beans
 * - Managing bean lifecycle (register/unregister)
 * - Providing bean lookup for SCG integration
 *
 * SCG Naming Convention:
 * - Filter name "CustomRateLimit" maps to bean "customRateLimitGatewayFilterFactory"
 * - Bean name follows: {pluginName}GatewayFilterFactory (lowerCamelCase)
 *
 * @author leoli
 */
@Service
@Slf4j
public class PluginRegistryService {

    private final ApplicationContext applicationContext;
    private DefaultListableBeanFactory beanFactory;

    /**
     * Registered plugin beans: pluginId -> beanName
     */
    private final ConcurrentHashMap<String, String> registeredBeans = new ConcurrentHashMap<>();

    /**
     * Plugin name to bean name mapping for SCG lookup.
     */
    private final ConcurrentHashMap<String, String> pluginNameToBeanName = new ConcurrentHashMap<>();

    public PluginRegistryService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        // Get the bean factory from configurable context
        if (applicationContext instanceof ConfigurableApplicationContext) {
            ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) applicationContext;
            this.beanFactory = (DefaultListableBeanFactory) configurableContext.getBeanFactory();
            log.info("PluginRegistryService initialized with DefaultListableBeanFactory");
        } else {
            log.warn("ApplicationContext is not ConfigurableApplicationContext, dynamic registration may not work");
        }
    }

    /**
     * Register a plugin as a Spring Bean.
     *
     * @param pluginId the plugin ID (UUID)
     * @param pluginName the plugin name (used as filter name)
     * @param factoryClass the GatewayFilterFactory class
     * @return the registered bean name
     */
    public String registerPlugin(String pluginId, String pluginName, Class<?> factoryClass) {
        if (beanFactory == null) {
            throw new PluginRegistrationException("BeanFactory not available for dynamic registration");
        }

        // Generate bean name following SCG convention
        String beanName = generateBeanName(pluginName);

        try {
            // Check if already registered
            if (registeredBeans.containsKey(pluginId)) {
                String existingBeanName = registeredBeans.get(pluginId);
                if (beanFactory.containsBeanDefinition(existingBeanName)) {
                    log.info("Plugin {} already registered as bean: {}", pluginId, existingBeanName);
                    return existingBeanName;
                }
            }

            // Create bean definition
            BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .rootBeanDefinition(factoryClass)
                .setScope("singleton");

            // Register the bean
            beanFactory.registerBeanDefinition(beanName, builder.getBeanDefinition());

            // Cache mappings
            registeredBeans.put(pluginId, beanName);
            pluginNameToBeanName.put(pluginName, beanName);

            log.info("Plugin registered as Spring Bean: pluginId={}, pluginName={}, beanName={}",
                pluginId, pluginName, beanName);

            return beanName;

        } catch (Exception e) {
            log.error("Failed to register plugin as bean: pluginId={}, pluginName={}", pluginId, pluginName, e);
            throw new PluginRegistrationException("Failed to register plugin: " + e.getMessage(), e);
        }
    }

    /**
     * Register a plugin using pre-loaded ClassLoader and factory class.
     *
     * @param pluginId the plugin ID
     * @param pluginName the plugin name
     * @param factoryClass the loaded factory class
     * @param classLoader the ClassLoader (for reference, not used in registration)
     */
    public String registerPlugin(String pluginId, String pluginName, Class<?> factoryClass, URLClassLoader classLoader) {
        return registerPlugin(pluginId, pluginName, factoryClass);
    }

    /**
     * Unregister a plugin bean.
     *
     * @param pluginId the plugin ID
     */
    public void unregisterPlugin(String pluginId) {
        if (beanFactory == null) {
            log.warn("BeanFactory not available, cannot unregister plugin: {}", pluginId);
            return;
        }

        String beanName = registeredBeans.remove(pluginId);
        if (beanName == null) {
            log.warn("No registered bean found for plugin: {}", pluginId);
            return;
        }

        try {
            if (beanFactory.containsBeanDefinition(beanName)) {
                beanFactory.removeBeanDefinition(beanName);
                log.info("Plugin bean unregistered: pluginId={}, beanName={}", pluginId, beanName);
            }

            // Remove from plugin name mapping
            pluginNameToBeanName.values().remove(beanName);

        } catch (Exception e) {
            log.error("Failed to unregister plugin bean: pluginId={}, beanName={}", pluginId, beanName, e);
        }
    }

    /**
     * Check if a plugin is registered.
     */
    public boolean isPluginRegistered(String pluginId) {
        return registeredBeans.containsKey(pluginId);
    }

    /**
     * Check if a plugin name has a registered bean.
     */
    public boolean isPluginNameRegistered(String pluginName) {
        String beanName = generateBeanName(pluginName);
        return beanFactory != null && beanFactory.containsBeanDefinition(beanName);
    }

    /**
     * Get the bean name for a plugin.
     */
    public String getBeanName(String pluginId) {
        return registeredBeans.get(pluginId);
    }

    /**
     * Get the bean name from plugin name (for SCG filter lookup).
     */
    public String getBeanNameByPluginName(String pluginName) {
        return pluginNameToBeanName.get(pluginName);
    }

    /**
     * Get all registered plugin IDs.
     */
    public Collection<String> getRegisteredPluginIds() {
        return registeredBeans.keySet();
    }

    /**
     * Get the actual GatewayFilterFactory bean instance.
     *
     * @param pluginName the plugin name (filter name)
     * @return the factory bean instance
     */
    public AbstractGatewayFilterFactory<?> getGatewayFilterFactory(String pluginName) {
        String beanName = generateBeanName(pluginName);
        try {
            if (beanFactory != null && beanFactory.containsBeanDefinition(beanName)) {
                return (AbstractGatewayFilterFactory<?>) beanFactory.getBean(beanName);
            }
        } catch (Exception e) {
            log.warn("Failed to get GatewayFilterFactory bean for plugin: {}", pluginName, e);
        }
        return null;
    }

    /**
     * Generate bean name from plugin name following SCG convention.
     * SCG convention: filter name "X" maps to bean "xGatewayFilterFactory"
     *
     * Example:
     * - "CustomRateLimit" -> "customRateLimitGatewayFilterFactory"
     * - "StripPrefix" -> "stripPrefixGatewayFilterFactory"
     */
    private String generateBeanName(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) {
            throw new IllegalArgumentException("Plugin name cannot be null or empty");
        }

        // Convert to lowerCamelCase if needed
        String lowerCamelCase;
        if (pluginName.length() > 1) {
            lowerCamelCase = Character.toLowerCase(pluginName.charAt(0)) + pluginName.substring(1);
        } else {
            lowerCamelCase = pluginName.toLowerCase();
        }

        // Append GatewayFilterFactory suffix
        return lowerCamelCase + "GatewayFilterFactory";
    }

    /**
     * Get all GatewayFilterFactory beans (including built-in and custom).
     * Useful for debugging or listing available filters.
     */
    public java.util.Map<String, AbstractGatewayFilterFactory<?>> getAllGatewayFilterFactories() {
        java.util.Map<String, AbstractGatewayFilterFactory<?>> factories = new java.util.HashMap<>();

        if (beanFactory != null) {
            String[] beanNames = beanFactory.getBeanNamesForType(AbstractGatewayFilterFactory.class);
            for (String beanName : beanNames) {
                try {
                    AbstractGatewayFilterFactory<?> factory = beanFactory.getBean(beanName, AbstractGatewayFilterFactory.class);
                    factories.put(beanName, factory);
                } catch (Exception e) {
                    log.warn("Failed to get factory bean: {}", beanName, e);
                }
            }
        }

        return factories;
    }
}