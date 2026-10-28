package com.leoli.gateway.ai.security;

import com.leoli.gateway.model.AiSecurityConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Pluggable content moderator. The built-in {@link RuleBasedModerator} does
 * dictionary / regex / PII checks locally. Provider protocol extensions implement
 * {@link ModerationAdapter}, executed by {@link ExternalContentModerator}.
 *
 * @author leoli
 */
public interface ContentModerator {

    /** Identifier matched against AiSecurityConfig.moderator (e.g. "RULE", "MODEL"). */
    String type();

    /**
     * Moderate a text fragment.
     *
     * @param text   the content to inspect
     * @param config the AI security configuration (rules, PII toggles)
     * @return the verdict plus a sanitized copy of the text (for REPLACE mode)
     */
    ModerationResult moderate(String text, AiSecurityConfig config);

    /** Moderation verdict. */
    class ModerationResult {
        private boolean flagged;
        private final List<String> categories = new ArrayList<>();
        private String sanitizedText;

        public static ModerationResult clean(String text) {
            ModerationResult r = new ModerationResult();
            r.flagged = false;
            r.sanitizedText = text;
            return r;
        }

        public boolean isFlagged() {
            return flagged;
        }

        public void setFlagged(boolean flagged) {
            this.flagged = flagged;
        }

        public List<String> getCategories() {
            return categories;
        }

        public String getSanitizedText() {
            return sanitizedText;
        }

        public void setSanitizedText(String sanitizedText) {
            this.sanitizedText = sanitizedText;
        }
    }
}
