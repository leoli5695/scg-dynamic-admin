package com.leoli.gateway.admin.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Argument extraction for AI tool calls.
 *
 * <p>Tool arguments arrive as a loosely-typed {@code Map<String, Object>} straight out of a parsed
 * LLM tool call, so every handler needs the same coercions: numbers may come as {@code Number} or as
 * a {@code String}, booleans as {@code Boolean} or {@code "true"}, lists as a {@code List} or as a
 * comma-separated string. These were nine private methods on {@code ToolExecutor}; they touch none of
 * its 20-odd injected services, so they live here instead and {@code ToolExecutor} pulls them in with
 * a static import (142 call sites, unchanged).
 *
 * <p>Two contracts callers depend on:
 * <ul>
 *   <li>The {@code getRequired*} methods throw {@code IllegalArgumentException} naming the missing
 *       key, which the dispatcher turns into a tool error the model can read and correct.</li>
 *   <li>The non-required getters return the supplied default (or {@code null}) rather than throwing,
 *       so an optional argument that the model omitted is not a failure.</li>
 * </ul>
 *
 * @author leoli
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static String getStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? value.toString() : null;
    }

    public static String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public static String getRequiredStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Required argument missing: " + key);
        }
        return value.toString();
    }

    public static int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return defaultValue;
    }

    public static long getLongArg(Map<String, Object> args, String key, long defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        return defaultValue;
    }

    public static boolean getBoolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    public static boolean getRequiredBoolArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Required argument missing: " + key);
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    public static Long getRequiredLongArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Required argument missing: " + key);
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    @SuppressWarnings("unchecked")
    public static List<String> getListArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List) {
            return (List<String>) value;
        }
        if (value instanceof String) {
            // 支持逗号分隔的字符串
            return Arrays.asList(((String) value).split(","));
        }
        return null;
    }
}
