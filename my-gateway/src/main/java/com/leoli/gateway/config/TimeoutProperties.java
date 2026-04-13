package com.leoli.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timeout default configuration properties.
 * <p>
 * Allows external configuration of timeout default values via application.yml:
 * <pre>
 * gateway:
 *   timeout:
 *     default-connect-timeout: 5000
 *     default-response-timeout: 30000
 * </pre>
 *
 * @author leoli
 */
@ConfigurationProperties(prefix = "gateway.timeout")
public class TimeoutProperties {

    /**
     * Default connect timeout in milliseconds.
     */
    private int defaultConnectTimeout = 5000;

    /**
     * Default response timeout in milliseconds.
     */
    private int defaultResponseTimeout = 30000;

    public int getDefaultConnectTimeout() {
        return defaultConnectTimeout;
    }

    public void setDefaultConnectTimeout(int defaultConnectTimeout) {
        this.defaultConnectTimeout = defaultConnectTimeout;
    }

    public int getDefaultResponseTimeout() {
        return defaultResponseTimeout;
    }

    public void setDefaultResponseTimeout(int defaultResponseTimeout) {
        this.defaultResponseTimeout = defaultResponseTimeout;
    }
}