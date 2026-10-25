package com.leoli.gateway.admin.tool.handler;

/**
 * Maps a gateway filter name to the coarse category the AI filter-analysis tools reason about.
 *
 * <p>Lives here rather than on either handler because two of the extracted tool groups need it -
 * the performance-analysis tools (which suggest reordering filters) and the AI filter-analysis tools
 * (which explain anomalies and predict impact) - and it was a single private method on ToolExecutor
 * called from both segments. Keeping it shared is what lets those two segments become independent
 * handlers instead of one having to reach into the other.
 *
 * <p>Substring matching on the lowercased name, first match wins, so the order of the checks is the
 * behaviour: "auth" is tested before "rate-limit", a name containing both is classified as auth.
 *
 * @author leoli
 */
public final class FilterTypeClassifier {

    private FilterTypeClassifier() {
    }

    public static String classify(String filterName) {
        if (filterName == null) return "unknown";
        String lower = filterName.toLowerCase();
        if (lower.contains("auth") || lower.contains("token") || lower.contains("jwt")) {
            return "auth";
        }
        if (lower.contains("rate") || lower.contains("limit")) {
            return "rate-limit";
        }
        if (lower.contains("log") || lower.contains("trace")) {
            return "logging";
        }
        if (lower.contains("cache")) {
            return "cache";
        }
        if (lower.contains("rewrite") || lower.contains("redirect")) {
            return "rewrite";
        }
        if (lower.contains("loadbalance") || lower.contains("lb")) {
            return "loadbalancer";
        }
        if (lower.contains("circuit") || lower.contains("breaker")) {
            return "circuit-breaker";
        }
        if (lower.contains("retry")) {
            return "retry";
        }
        if (lower.contains("header") || lower.contains("request") || lower.contains("response")) {
            return "modify";
        }
        return "other";
    }
}
