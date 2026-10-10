package com.leoli.gateway.ai.security;

import com.leoli.gateway.model.AiSecurityConfig;
import com.leoli.gateway.util.PatternCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in rule-based {@link ContentModerator}: sensitive-word dictionary, regex
 * patterns and PII masking, all evaluated locally (no external call, sub-ms).
 *
 * @author leoli
 */
@Slf4j
@Component
public class RuleBasedModerator implements ContentModerator {

    private static final String MASK = "***";

    /** Built-in PII detectors (CN-oriented). Extend via config.piiTypes. */
    private static final Map<String, Pattern> PII_PATTERNS = new LinkedHashMap<>();

    static {
        PII_PATTERNS.put("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        PII_PATTERNS.put("PHONE", Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"));
        PII_PATTERNS.put("ID_CARD", Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"));
    }

    @Override
    public String type() {
        return "RULE";
    }

    @Override
    public ModerationResult moderate(String text, AiSecurityConfig config) {
        if (text == null || text.isEmpty()) {
            return ModerationResult.clean(text);
        }
        ModerationResult result = new ModerationResult();
        String sanitized = text;

        if (config.getBannedWords() != null) {
            for (String word : config.getBannedWords()) {
                if (word == null || word.isBlank()) {
                    continue;
                }
                if (containsIgnoreCase(sanitized, word)) {
                    result.setFlagged(true);
                    result.getCategories().add("BANNED_WORD");
                    sanitized = replaceIgnoreCase(sanitized, word, MASK);
                }
            }
        }

        if (config.getBannedPatterns() != null) {
            for (String p : config.getBannedPatterns()) {
                Pattern pattern = PatternCache.compile(p).orElse(null);
                if (pattern == null) {
                    continue;
                }
                if (pattern.matcher(sanitized).find()) {
                    result.setFlagged(true);
                    result.getCategories().add("BANNED_PATTERN");
                    sanitized = pattern.matcher(sanitized).replaceAll(MASK);
                }
            }
        }

        if (config.isEnablePiiMasking() && config.getPiiTypes() != null) {
            for (String t : config.getPiiTypes()) {
                Pattern pattern = t == null ? null : PII_PATTERNS.get(t.trim().toUpperCase());
                if (pattern == null) {
                    continue;
                }
                if (pattern.matcher(sanitized).find()) {
                    result.setFlagged(true);
                    result.getCategories().add("PII:" + t.trim().toUpperCase());
                    sanitized = pattern.matcher(sanitized).replaceAll(MASK);
                }
            }
        }

        result.setSanitizedText(sanitized);
        return result;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static String replaceIgnoreCase(String haystack, String needle, String replacement) {
        Pattern p = Pattern.compile(Pattern.quote(needle), Pattern.CASE_INSENSITIVE);
        return p.matcher(haystack).replaceAll(Matcher.quoteReplacement(replacement));
    }
}
