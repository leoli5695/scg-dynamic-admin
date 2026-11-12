package com.leoli.gateway.util;

import java.util.List;

/**
 * The one CORS combination that must never reach a browser.
 * <p>
 * {@code Access-Control-Allow-Origin: *} with {@code Access-Control-Allow-Credentials: true} is
 * rejected by browsers, so a gateway that wants to honour both ends up reflecting the caller's
 * Origin instead - which lets any website make credentialed cross-origin requests and read the
 * response. Callers pass their own effective origin list, because the two CORS paths disagree on
 * what an empty list means (the filter allows nothing, CorsConfig defaults to {@code *}).
 */
public final class CorsSafety {

    private CorsSafety() {
    }

    public static boolean isWildcardWithCredentials(List<String> effectiveAllowedOrigins, boolean allowCredentials) {
        return allowCredentials && effectiveAllowedOrigins != null && effectiveAllowedOrigins.contains("*");
    }
}