package com.leoli.gateway.util;

import java.util.List;

/**
 * Credential-bearing header names, shared by every place that logs, traces or replays a request.
 * <p>
 * Matching runs on a separator-stripped lowercase form so {@code X-API-Key}, {@code Api-Key} and
 * {@code ApiKey} classify identically. Exact-match lists previously let API keys reach admin traces
 * and error reports in plaintext while {@code CacheGlobalFilter} already treated them as credentials.
 */
public final class SensitiveHeaders {

    /** Substring markers; deliberately broad because a leaked credential cannot be un-leaked. */
    private static final List<String> MARKERS = List.of(
            "authorization", "cookie", "apikey", "accesskey", "secret", "password",
            "credential", "signature", "privatekey", "token");

    private SensitiveHeaders() {
    }

    public static boolean isSensitive(String headerName) {
        if (headerName == null || headerName.isEmpty()) {
            return false;
        }
        StringBuilder normalized = new StringBuilder(headerName.length());
        for (int i = 0; i < headerName.length(); i++) {
            char c = headerName.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toLowerCase(c));
            }
        }
        String name = normalized.toString();
        for (String marker : MARKERS) {
            if (name.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}