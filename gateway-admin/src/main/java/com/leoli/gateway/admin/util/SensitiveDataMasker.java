package com.leoli.gateway.admin.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for "which field names carry credentials" and how to mask them.
 *
 * <p>Why this exists as one class instead of per-call-site masking: the audit table stores
 * whole-object JSON snapshots ({@code StrategyController} writes
 * {@code objectMapper.writeValueAsString(strategy)} into {@code audit_logs.new_value}), and a
 * {@code StrategyConfig} embeds {@code AuthConfig} whose {@code secretKey} / {@code apiKey} /
 * {@code clientSecret} are plain fields. Every read path that serialises an audit row therefore
 * has to mask, and there are several of them (list, detail, diff, timeline, JSON export, the AI
 * copilot tool). Masking only one of them is how a secret ends up readable anyway.
 *
 * <p>Masking happens on the <b>read/serialisation</b> side only. Stored values are left intact
 * because {@code AuditLogService.rollback} restores configuration from {@code oldValue} - masking
 * at write time would push {@code "secretKey":"****"} back into Nacos and break gateway auth.
 *
 * <p>{@code AuthConfig} itself must NOT get {@code @JsonProperty(WRITE_ONLY)}: the same Jackson
 * serialisation publishes strategies to Nacos ({@code NacosConfigCenterService.publishConfig}),
 * where the gateway's auth processors read those secrets back.
 *
 * @author leoli
 */
public final class SensitiveDataMasker {

    /**
     * Key substrings whose values may carry credentials/PII. Matched case-insensitively after
     * normalising '-' to '_', so apiKey / api_key / api-key / X-Api-Key all hit.
     */
    private static final Set<String> SENSITIVE_KEY_SUBSTRINGS = Set.of(
            "apikey", "api_key", "token", "secret", "password", "passwd", "credential",
            "authorization", "privatekey", "private_key", "accesskey", "secretkey",
            "cookie", "session", "kubeconfig", "keystore");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Fallback for values that are not parseable JSON: mask the value of any
     * {@code "someSensitiveKey": "..." pair. Deliberately conservative - only double-quoted
     * string values are rewritten, so non-JSON prose passes through untouched.
     */
    private static final Pattern STRING_KV = Pattern.compile(
            "(\"[^\"\\n]{0,64}\"\\s*:\\s*\")([^\"]*)(\")");

    private SensitiveDataMasker() {
    }

    /** True when {@code key} looks like it names a credential. */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT).replace('-', '_');
        for (String s : SENSITIVE_KEY_SUBSTRINGS) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mask a credential-looking value, keeping the last 4 characters so operators can still tell
     * two keys apart in a diff. Same shape as {@code CredentialCryptoService.mask}.
     */
    public static String maskValue(Object value) {
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value);
        return str.length() <= 4 ? "****" : "****" + str.substring(str.length() - 4);
    }

    /**
     * Mask every credential-looking field in a JSON document. Non-JSON input falls back to
     * string-pair masking; null/blank input is returned unchanged. Never throws - an audit read
     * path must not 500 because a stored value is malformed.
     */
    public static String mask(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return rawJson;
        }
        try {
            return MAPPER.writeValueAsString(maskTree(MAPPER.readTree(rawJson)));
        } catch (Exception e) {
            return maskStringPairs(rawJson);
        }
    }

    /**
     * Mask credential-looking fields in a parsed tree, in place, and return it. The caller's tree
     * is mutated on purpose: diff builders want both sides masked identically so a rotated secret
     * does not show up as a spurious change.
     */
    public static JsonNode maskTree(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode child = entry.getValue();
                if (isSensitiveKey(entry.getKey()) && child != null && child.isValueNode()) {
                    obj.put(entry.getKey(), maskValue(child.asText()));
                } else {
                    maskTree(child);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                maskTree(child);
            }
        }
        return node;
    }

    private static String maskStringPairs(String raw) {
        Matcher m = STRING_KV.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length());
        while (m.find()) {
            String keyWithQuoteAndColon = m.group(1);
            String key = keyWithQuoteAndColon.substring(1, keyWithQuoteAndColon.indexOf('"', 1));
            String replacement = isSensitiveKey(key)
                    ? keyWithQuoteAndColon + maskValue(m.group(2)) + m.group(3)
                    : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
