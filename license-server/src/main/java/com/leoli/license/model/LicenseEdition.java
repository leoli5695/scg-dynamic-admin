package com.leoli.license.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * License Edition Enumeration
 * <p>
 * Defines three commercial tiers for Gateway:
 * - BASIC: Free tier, basic routing and local rate limiting
 * - ADVANCED: Paid tier (¥9,999/year), gray release and distributed features
 * - ENTERPRISE: Enterprise tier (¥49,999 permanent), all features included
 */
@Getter
@AllArgsConstructor
public enum LicenseEdition {

    BASIC(
        "BASIC",
        "基础版",
        "免费",
        null,  // null means permanent (no expiration)
        "单服务路由、本地限流(100 QPS)、基础认证、Docker部署"
    ),

    ADVANCED(
        "ADVANCED",
        "高级版",
        "¥9,999/年",
        365,  // 1 year validity
        "多服务加权路由、分布式限流、熔断器、K8s部署、最多3实例"
    ),

    ENTERPRISE(
        "ENTERPRISE",
        "企业版",
        "¥49,999永久",
        null,  // permanent
        "全部功能、无实例限制、企业级支持、永久授权"
    );

    /**
     * Edition code (stored in database)
     */
    private final String code;

    /**
     * Edition display name (Chinese)
     */
    private final String displayName;

    /**
     * Price description
     */
    private final String price;

    /**
     * Default validity days (null = permanent)
     */
    private final Integer defaultValidityDays;

    /**
     * Feature summary description
     */
    private final String featureSummary;

    /**
     * Parse edition from code string
     */
    public static LicenseEdition fromCode(String code) {
        for (LicenseEdition edition : values()) {
            if (edition.getCode().equalsIgnoreCase(code)) {
                return edition;
            }
        }
        return BASIC; // Default to BASIC
    }

    /**
     * Check if this edition is permanent (no expiration)
     */
    public boolean isPermanent() {
        return defaultValidityDays == null;
    }

    /**
     * Get display label for UI
     */
    public String getDisplayLabel() {
        return displayName + " (" + price + ")";
    }
}