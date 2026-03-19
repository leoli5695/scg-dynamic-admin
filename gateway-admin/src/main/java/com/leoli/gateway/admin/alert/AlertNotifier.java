package com.leoli.gateway.admin.alert;

/**
 * Alert notifier interface.
 *
 * @author leoli
 */
public interface AlertNotifier {

    /**
     * Send alert.
     *
     * @param title   Alert title
     * @param content Alert content
     * @param level   Alert level
     */
    void sendAlert(String title, String content, AlertLevel level);

    /**
     * Check if this notifier is supported/enabled.
     */
    boolean isSupported();
}