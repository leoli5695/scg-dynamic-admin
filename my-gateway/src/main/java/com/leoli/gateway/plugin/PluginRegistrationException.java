package com.leoli.gateway.plugin;

/**
 * Exception thrown when plugin registration fails.
 *
 * @author leoli
 */
public class PluginRegistrationException extends RuntimeException {

    public PluginRegistrationException(String message) {
        super(message);
    }

    public PluginRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}