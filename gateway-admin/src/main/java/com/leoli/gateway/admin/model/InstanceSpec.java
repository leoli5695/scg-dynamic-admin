package com.leoli.gateway.admin.model;

import lombok.Getter;

/**
 * Instance specification types.
 * Pre-defined resource configurations for gateway instances.
 *
 * @author leoli
 */
@Getter
public enum InstanceSpec {

    SMALL("small", 0.5, 512, "小型实例 (0.5C 512MB)"),
    MEDIUM("medium", 1.0, 1024, "标准实例 (1C 1GB)"),
    LARGE("large", 2.0, 2048, "大型实例 (2C 2GB)"),
    XLARGE("xlarge", 4.0, 4096, "超大型实例 (4C 4GB)"),
    CUSTOM("custom", null, null, "自定义规格");

    private final String type;
    private final Double cpuCores;
    private final Integer memoryMB;
    private final String description;

    InstanceSpec(String type, Double cpuCores, Integer memoryMB, String description) {
        this.type = type;
        this.cpuCores = cpuCores;
        this.memoryMB = memoryMB;
        this.description = description;
    }

    /**
     * Get spec by type string.
     */
    public static InstanceSpec fromType(String type) {
        for (InstanceSpec spec : values()) {
            if (spec.getType().equalsIgnoreCase(type)) {
                return spec;
            }
        }
        return MEDIUM; // Default
    }

    /**
     * Check if this is a custom spec.
     */
    public boolean isCustom() {
        return this == CUSTOM;
    }
}