package com.leoli.gateway.admin.alert;

/**
 * Alert level enum.
 *
 * @author leoli
 */
public enum AlertLevel {
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
    CRITICAL("Critical");

    private final String description;

    AlertLevel(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}