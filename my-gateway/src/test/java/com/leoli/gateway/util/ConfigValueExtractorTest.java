package com.leoli.gateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ConfigValueExtractor}, focused on rate-limit window size
 * extraction semantics (windowSizeMs vs timeUnit).
 */
@DisplayName("ConfigValueExtractor")
class ConfigValueExtractorTest {

    private static final long DEFAULT_MS = 1000L;

    @Test
    @DisplayName("getWindowSizeMs: explicit windowSizeMs takes precedence over timeUnit")
    void getWindowSizeMs_explicitMsTakesPrecedence() {
        Map<String, Object> config = new HashMap<>();
        config.put("windowSizeMs", 60000);
        config.put("timeUnit", "second");

        assertEquals(60000L, ConfigValueExtractor.getWindowSizeMs(config, "timeUnit", DEFAULT_MS));
    }

    @Test
    @DisplayName("getWindowSizeMs: string-typed windowSizeMs is honored")
    void getWindowSizeMs_stringMsHonored() {
        Map<String, Object> config = new HashMap<>();
        config.put("windowSizeMs", "5000");

        assertEquals(5000L, ConfigValueExtractor.getWindowSizeMs(config, "timeUnit", DEFAULT_MS));
    }

    @Test
    @DisplayName("getWindowSizeMs: non-positive windowSizeMs falls back to timeUnit")
    void getWindowSizeMs_nonPositiveFallsBackToTimeUnit() {
        Map<String, Object> config = new HashMap<>();
        config.put("windowSizeMs", 0);
        config.put("timeUnit", "minute");

        assertEquals(60000L, ConfigValueExtractor.getWindowSizeMs(config, "timeUnit", DEFAULT_MS));
    }

    @Test
    @DisplayName("getWindowSizeMs: timeUnit drives window when windowSizeMs absent")
    void getWindowSizeMs_timeUnitFallback() {
        Map<String, Object> config = new HashMap<>();
        config.put("timeUnit", "hour");

        assertEquals(3600000L, ConfigValueExtractor.getWindowSizeMs(config, "timeUnit", DEFAULT_MS));
    }

    @Test
    @DisplayName("getWindowSizeMs: missing keys use the default window")
    void getWindowSizeMs_defaultWhenNothingConfigured() {
        assertEquals(DEFAULT_MS, ConfigValueExtractor.getWindowSizeMs(new HashMap<>(), "timeUnit", DEFAULT_MS));
        assertEquals(DEFAULT_MS, ConfigValueExtractor.getWindowSizeMs(null, "timeUnit", DEFAULT_MS));
    }

    @Test
    @DisplayName("getWindowSizeMs: unknown timeUnit uses the default window")
    void getWindowSizeMs_unknownTimeUnitUsesDefault() {
        Map<String, Object> config = new HashMap<>();
        config.put("timeUnit", "fortnight");

        assertEquals(DEFAULT_MS, ConfigValueExtractor.getWindowSizeMs(config, "timeUnit", DEFAULT_MS));
    }
}
