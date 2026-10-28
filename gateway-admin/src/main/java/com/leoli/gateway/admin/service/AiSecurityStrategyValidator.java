package com.leoli.gateway.admin.service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation shared by strategy create/update through StrategyService. Never includes secret values in errors. */
public final class AiSecurityStrategyValidator {
    private AiSecurityStrategyValidator() {}

    public static void validate(Map<String, Object> config) {
        for (String key : List.of("inboundAction", "outboundAction", "piiAction")) choice(config, key, Set.of("BLOCK", "REPLACE", "DETECT"));
        choice(config, "streamingPolicy", Set.of("BUFFER", "BLOCK", "SKIP"));
        choice(config, "externalFailureAction", Set.of("BLOCK", "LOCAL"));
        number(config, "maxBodySize", 1024, 10485760);
        number(config, "externalTimeoutMs", 100, 30000);
        number(config, "responseTimeoutMs", 1000, 300000);
        for (String key : List.of("enabled", "scanRequest", "scanResponse", "enablePiiMasking")) {
            if (config.containsKey(key) && !(config.get(key) instanceof Boolean)) invalid(key);
        }
        for (String key : List.of("bannedWords", "prohibitedWords", "bannedPatterns", "piiTypes")) {
            if (!config.containsKey(key)) continue;
            if (!(config.get(key) instanceof List<?> list) || list.size() > 2048) invalid(key);
            for (Object value : (List<?>) config.get(key)) {
                if (!(value instanceof String text) || text.isBlank() || text.length() > 512) invalid(key);
                if ("bannedPatterns".equals(key)) {
                    try { Pattern.compile((String) value); } catch (Exception e) { invalid(key); }
                }
                if ("piiTypes".equals(key) && !Set.of("EMAIL", "PHONE", "ID_CARD").contains(value)) invalid(key);
            }
        }
        if (config.containsKey("blockMessage") && (!(config.get("blockMessage") instanceof String s) || s.isBlank() || s.length() > 2048)) invalid("blockMessage");
        if (config.containsKey("externalAdapter") && (!(config.get("externalAdapter") instanceof String s) || !s.matches("[A-Za-z0-9_]{1,64}"))) invalid("externalAdapter");
        if (config.containsKey("externalUrl")) {
            if (!(config.get("externalUrl") instanceof String)) invalid("externalUrl");
            String url = (String) config.get("externalUrl");
            if (!url.isBlank()) {
                try {
                    URI uri = URI.create(url);
                    if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) invalid("externalUrl");
                } catch (Exception e) { invalid("externalUrl"); }
            }
        }
        for (String key : List.of("externalHeaders", "externalOptions")) {
            if (!config.containsKey(key)) continue;
            if (!(config.get(key) instanceof Map<?, ?> map) || map.size() > 64) invalid(key);
            for (var entry : ((Map<?, ?>) config.get(key)).entrySet()) {
                if (!(entry.getKey() instanceof String s) || s.isBlank() || entry.getValue() == null) invalid(key);
                if ("externalHeaders".equals(key) && (!(entry.getValue() instanceof String s)
                        || s.contains("\r") || s.contains("\n") || !entry.getKey().toString().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) invalid(key);
            }
        }
    }

    private static void choice(Map<String, Object> config, String key, Set<String> values) {
        if (config.containsKey(key) && !values.contains(config.get(key))) invalid(key);
    }
    private static void number(Map<String, Object> config, String key, long min, long max) {
        if (!config.containsKey(key)) return;
        Object value = config.get(key);
        if (!(value instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < min || n.longValue() > max) invalid(key);
    }
    private static void invalid(String key) { throw new IllegalArgumentException("Invalid AI_SECURITY configuration field: " + key); }
}
