package com.leoli.gateway.util;

import java.util.Map;

/**
 * Utility class for extracting configuration values from Map.
 * <p>
 * Provides type-safe extraction with default values,
 * eliminating repetitive null-checks and type conversion code.
 *
 * @author leoli
 */
public final class ConfigValueExtractor {

    private ConfigValueExtractor() {
        // Utility class - prevent instantiation
    }

    // ============================================================
    // Basic Type Extraction
    // ============================================================

    /**
     * Get boolean value from config map.
     *
     * @param map         Configuration map
     * @param key         Configuration key
     * @param defaultValue Default value if key not found or invalid
     * @return Boolean value
     */
    public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Get integer value from config map.
     *
     * @param map         Configuration map
     * @param key         Configuration key
     * @param defaultValue Default value if key not found or invalid
     * @return Integer value
     */
    public static int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get long value from config map.
     *
     * @param map         Configuration map
     * @param key         Configuration key
     * @param defaultValue Default value if key not found or invalid
     * @return Long value
     */
    public static long getLong(Map<String, Object> map, String key, long defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get float value from config map.
     *
     * @param map         Configuration map
     * @param key         Configuration key
     * @param defaultValue Default value if key not found or invalid
     * @return Float value
     */
    public static float getFloat(Map<String, Object> map, String key, float defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).floatValue();
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get double value from config map.
     *
     * @param map         Configuration map
     * @param key         Configuration key
     * @param defaultValue Default value if key not found or invalid
     * @return Double value
     */
    public static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get string value from config map.
     *
     * @param map         Configuration map
     * @param key         Configuration key
     * @param defaultValue Default value if key not found
     * @return String value
     */
    public static String getString(Map<String, Object> map, String key, String defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }

    // ============================================================
    // Complex Type Extraction
    // ============================================================

    /**
     * Get nested map value from config map.
     *
     * @param map Configuration map
     * @param key Configuration key
     * @return Nested map, or null if not found or not a map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    /**
     * Get list value from config map.
     *
     * @param map Configuration map
     * @param key Configuration key
     * @return List, or null if not found or not a list
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<Object> getList(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof java.util.List) {
            return (java.util.List<Object>) value;
        }
        return null;
    }

    /**
     * Get string list value from config map.
     *
     * @param map Configuration map
     * @param key Configuration key
     * @return String list, or empty list if not found
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> getStringList(Map<String, Object> map, String key) {
        if (map == null) return java.util.Collections.emptyList();
        Object value = map.get(key);
        if (value == null) return java.util.Collections.emptyList();
        if (value instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) value;
            java.util.List<String> result = new java.util.ArrayList<>();
            for (Object item : list) {
                result.add(item != null ? String.valueOf(item) : null);
            }
            return result;
        }
        return java.util.Collections.emptyList();
    }

    // ============================================================
    // Window Size Conversion
    // ============================================================

    /**
     * Get window size in milliseconds based on time unit.
     *
     * @param configMap Configuration map
     * @param timeUnitKey Key for time unit configuration
     * @param defaultMs Default window size in milliseconds
     * @return Window size in milliseconds
     */
    public static long getWindowSizeMs(Map<String, Object> configMap, String timeUnitKey, long defaultMs) {
        String timeUnit = getString(configMap, timeUnitKey, "second");
        return convertTimeUnitToMs(timeUnit, defaultMs);
    }

    /**
     * Convert time unit string to milliseconds.
     *
     * @param timeUnit Time unit string (second, minute, hour)
     * @param defaultMs Default value if unit is unknown
     * @return Milliseconds equivalent
     */
    public static long convertTimeUnitToMs(String timeUnit, long defaultMs) {
        if (timeUnit == null) return defaultMs;
        switch (timeUnit.toLowerCase()) {
            case "second":
                return 1000L;
            case "minute":
                return 60000L;
            case "hour":
                return 3600000L;
            default:
                return defaultMs;
        }
    }
}