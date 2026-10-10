package com.leoli.gateway.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Prompt Template / Decorator configuration (strategy type AI_PROMPT_TEMPLATE).
 * <p>
 * Mirrors Higress ai-prompt-template + ai-prompt-decorator: inject or override a
 * system prompt, prepend/append messages, substitute {@code {{var}}} placeholders
 * from request variables, optionally select a named template, and optionally
 * override the model. The top-level fields are the default decoration (flat, so the
 * generic admin form can edit them); {@link #templates} are named overrides selected
 * by header/body field.
 *
 * @author leoli
 */
@Data
public class AiPromptTemplateConfig {

    private boolean enabled = false;

    // ============== Default decoration (flat / UI-friendly) ==============

    /** System prompt to inject; supports {{var}} placeholders. */
    private String systemPrompt;

    /** PREPEND (keep existing system messages) or OVERRIDE (replace them). */
    private String systemPromptMode = "PREPEND";

    /** Messages inserted before the conversation. */
    private List<Message> prependMessages = new ArrayList<>();

    /** Messages appended after the conversation. */
    private List<Message> appendMessages = new ArrayList<>();

    /** Optional model override applied with the default decoration. */
    private String model;

    // ============== Named templates (advanced) ==============

    /** Header used to select a named template; absent -> default decoration. */
    private String templateHeader = "X-Prompt-Template";

    /** Body field used to select a named template (optional). */
    private String templateBodyField;

    /** Named templates matched by {@link PromptTemplate#name}. */
    private List<PromptTemplate> templates = new ArrayList<>();

    // ============== Variables & control fields ==============

    /** Body field carrying the variables object for {{var}} substitution. */
    private String variablesBodyField = "variables";

    /** Remove the variables / template-selector fields before forwarding upstream. */
    private boolean stripControlFields = true;

    /** Max request body buffered to rewrite the prompt. */
    private long maxBodySize = 1048576;

    /** A named prompt template. */
    @Data
    public static class PromptTemplate {
        private String name;
        private String systemPrompt;
        private String systemPromptMode = "PREPEND";
        private List<Message> prependMessages = new ArrayList<>();
        private List<Message> appendMessages = new ArrayList<>();
        private String model;
    }

    /** A chat message to inject. */
    @Data
    public static class Message {
        private String role = "system";
        private String content;
    }
}
