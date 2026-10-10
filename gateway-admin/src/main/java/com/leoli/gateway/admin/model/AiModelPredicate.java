package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * AI model predicate - matches the requested {@code model} to select an AI route.
 * <p>
 * Mirrors Higress {@code AiModelPredicate}: only EXACT and PREFIX matching are allowed
 * (Higress explicitly rejects regular-expression model matching), so this predicate
 * deliberately exposes no regex mode.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiModelPredicate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Match type: EXACT or PREFIX. Defaults to PREFIX. */
    private String matchType = MatchType.PREFIX;

    /** The model name (EXACT) or model name prefix (PREFIX) to match. */
    private String value;

    /** Supported model match types. Regular expressions are intentionally unsupported. */
    public static final class MatchType {
        private MatchType() {
        }

        public static final String EXACT = "EXACT";
        public static final String PREFIX = "PREFIX";
    }
}
