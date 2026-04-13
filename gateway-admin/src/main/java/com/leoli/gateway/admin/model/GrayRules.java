package com.leoli.gateway.admin.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Gray release rules for multi-service routing.
 * Supports routing based on header, cookie, query parameter, or weight percentage.
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrayRules implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Whether gray rules are enabled.
     */
    private boolean enabled = true;

    /**
     * List of gray release rules.
     * Rules are evaluated in order, first match wins.
     */
    private List<GrayRule> rules = new ArrayList<>();

    /**
     * Gray release rule definition.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GrayRule implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * Rule type: HEADER, COOKIE, QUERY, WEIGHT.
         */
        private RuleType type;

        /**
         * Name of the header/cookie/query parameter.
         * Not used for WEIGHT type.
         */
        private String name;

        /**
         * Expected value to match.
         * For WEIGHT type, this is the percentage (e.g., "10" means 10%).
         */
        private String value;

        /**
         * Target version to route to when rule matches.
         */
        private String targetVersion;

        /**
         * Description of this rule.
         */
        private String description;

        /**
         * Create a header-based rule.
         */
        public static GrayRule header(String headerName, String headerValue, String targetVersion) {
            return new GrayRule(RuleType.HEADER, headerName, headerValue, targetVersion, null);
        }

        /**
         * Create a cookie-based rule.
         */
        public static GrayRule cookie(String cookieName, String cookieValue, String targetVersion) {
            return new GrayRule(RuleType.COOKIE, cookieName, cookieValue, targetVersion, null);
        }

        /**
         * Create a query parameter-based rule.
         */
        public static GrayRule query(String paramName, String paramValue, String targetVersion) {
            return new GrayRule(RuleType.QUERY, paramName, paramValue, targetVersion, null);
        }

        /**
         * Create a weight-based rule.
         * @param percentage Percentage of traffic (1-100)
         * @param targetVersion Target version to route to
         */
        public static GrayRule weight(int percentage, String targetVersion) {
            return new GrayRule(RuleType.WEIGHT, null, String.valueOf(percentage), targetVersion, null);
        }
    }

    /**
     * Rule type enumeration.
     */
    public enum RuleType {
        /**
         * Route based on HTTP header.
         */
        HEADER,

        /**
         * Route based on cookie value.
         */
        COOKIE,

        /**
         * Route based on query parameter.
         */
        QUERY,

        /**
         * Route based on weight percentage.
         */
        WEIGHT
    }

    /**
     * Create empty gray rules (disabled).
     */
    public static GrayRules disabled() {
        return new GrayRules(false, new ArrayList<>());
    }

    /**
     * Create enabled gray rules with specified rules.
     */
    public static GrayRules of(GrayRule... rules) {
        GrayRules grayRules = new GrayRules();
        grayRules.setEnabled(true);
        for (GrayRule rule : rules) {
            grayRules.getRules().add(rule);
        }
        return grayRules;
    }
}