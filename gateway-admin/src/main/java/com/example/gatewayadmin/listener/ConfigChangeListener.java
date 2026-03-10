package com.example.gatewayadmin.listener;

/**
 * Configuration change listener callback.
 *
 * @author leoli
 */
@FunctionalInterface
public interface ConfigChangeListener {
    /**
     * Called when configuration changes.
     *
     * @param newConfig new configuration content (JSON string)
     */
    void onChanged(String newConfig);
}
