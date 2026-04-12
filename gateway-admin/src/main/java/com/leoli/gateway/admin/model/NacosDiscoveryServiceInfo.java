package com.leoli.gateway.admin.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nacos discovery service info with namespace and group.
 * Used for service selection dropdown in route creation.
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NacosDiscoveryServiceInfo {

    /**
     * Service name registered in Nacos.
     */
    private String serviceName;

    /**
     * Nacos namespace where the service is registered.
     */
    private String namespace;

    /**
     * Nacos group where the service is registered.
     * Default: DEFAULT_GROUP
     */
    private String group;

    /**
     * Number of healthy instances.
     */
    private int instanceCount;

    /**
     * Display name for UI: serviceName (namespace/group)
     */
    public String getDisplayName() {
        String ns = namespace == null || namespace.isEmpty() ? "public" : namespace;
        String grp = group == null || group.isEmpty() ? "DEFAULT_GROUP" : group;
        return serviceName + " (" + ns + "/" + grp + ")";
    }
}