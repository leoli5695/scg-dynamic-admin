package com.leoli.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * IP filter configuration for a route.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IPFilterConfig {

    /**
     * Filter mode: "blacklist" or "whitelist".
     */
    private String mode = "blacklist";

    /**
     * List of IP addresses to filter.
     */
    private List<String> ipList = new ArrayList<>();

    /**
     * Whether this configuration is enabled.
     */
    private boolean enabled = true;
}
