package com.leoli.gateway.plugin;

/**
 * Exception thrown when plugin loading fails.
 *
 * @author leoli
 */
public class PluginLoadException extends RuntimeException {

    public PluginLoadException(String message) {
        super(message);
    }

    public PluginLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}