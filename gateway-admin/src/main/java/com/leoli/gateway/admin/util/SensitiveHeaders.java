package com.leoli.gateway.admin.util;

import java.util.List;

/**
 * Credential-bearing header names. Mirror of {@code com.leoli.gateway.util.SensitiveHeaders} in
 * my-gateway; the two modules share no dependency, so the contract is pinned by identical tests
 * ({@code SensitiveHeadersTest} in both) rather than by a shared artifact.
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