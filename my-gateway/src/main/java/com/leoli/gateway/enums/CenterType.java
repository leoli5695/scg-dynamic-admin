package com.leoli.gateway.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Configuration center type enumeration.
 */
@Getter
@AllArgsConstructor
public enum CenterType {
    NACOS("nacos", "Alibaba Nacos"),
    CONSUL("consul", "HashiCorp Consul");
    private final String code;
    private final String displayName;
}
