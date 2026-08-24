package com.leoli.gateway.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shared cache of compiled regex patterns.
 * <p>
 * FIX (performance): dynamic patterns (e.g. data-masking CUSTOM rules) were
 * compiled with {@link Pattern#compile(String)} on every request, which is
 * expensive and repeatable for the same configured pattern. Compiled
 * patterns are cached by their regex string; invalid regexes are cached as
 * {@link Optional#empty()} (negative cache) so a bad configuration cannot
 * trigger repeated compile attempts either.
 *
 * @author leoli
 */
public final class PatternCache {

    private static final Cache<String, Optional<Pattern>> PATTERN_CACHE = Caffeine.newBuilder()
            .maximumSize(1024)
            .build();

    private PatternCache() {
    }

    /**
     * Get a compiled pattern for the given regex.
     *
     * @param regex regular expression
     * @return compiled pattern, or empty if the regex is null/blank/invalid
     */
    public static Optional<Pattern> compile(String regex) {
        if (regex == null || regex.isBlank()) {
            return Optional.empty();
        }
        return PATTERN_CACHE.get(regex, key -> {
            try {
                return Optional.of(Pattern.compile(key));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
