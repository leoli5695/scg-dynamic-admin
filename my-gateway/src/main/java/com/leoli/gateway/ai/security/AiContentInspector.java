package com.leoli.gateway.ai.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.model.AiSecurityConfig;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Inspect text without rewriting model IDs, roles, usage or protocol metadata. */
@Component
public class AiContentInspector {
    private static final Set<String> TEXT_KEYS = Set.of("content", "text", "input", "prompt", "instructions", "system",
            "arguments", "refusal", "reasoning", "reasoning_content", "output_text", "partial_json");
    private final RuleBasedModerator rules;
    private final ExternalContentModerator external;

    public AiContentInspector(RuleBasedModerator rules, ExternalContentModerator external) {
        this.rules = rules;
        this.external = external;
    }

    public record Result(JsonNode body, boolean blocked, Set<String> categories, boolean externalFailed) {}
    private record TextField(JsonNode parent, String key) {
        String text() { return (parent.isArray() ? parent.path(Integer.parseInt(key)) : parent.path(key)).asText(); }
        void set(String text) {
            if (parent instanceof ObjectNode object) object.put(key, text);
            else ((com.fasterxml.jackson.databind.node.ArrayNode) parent).set(Integer.parseInt(key),
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(text));
        }
    }

    public Mono<Result> inspect(JsonNode root, String direction, AiSecurityConfig config, boolean stream) {
        return Mono.defer(() -> {
            List<TextField> fields = new ArrayList<>();
            collect(root, fields);
            Set<String> categories = new LinkedHashSet<>();
            if (fields.isEmpty()) {
                categories.add("NO_TEXT_FIELDS");
                return Mono.just(new Result(root, false, categories, false));
            }
            String action = "REQUEST".equals(direction) ? config.getInboundAction() : config.getOutboundAction();
            boolean blocked = false;
            if (stream && !fields.isEmpty()) {
                // Combine deltas so words and PII split over network frames cannot escape detection.
                String original = fields.stream().map(TextField::text).collect(Collectors.joining());
                ObjectNode combined = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                combined.put("text", original);
                TextField field = new TextField(combined, "text");
                blocked = inspectLocal(field, config, action, categories);
                if (!original.equals(field.text())) replaceAll(fields, field.text());
            } else {
                for (TextField field : fields) blocked |= inspectLocal(field, config, action, categories);
            }
            if (blocked || fields.isEmpty() || config.getExternalUrl() == null || config.getExternalUrl().isBlank()) {
                return Mono.just(new Result(root, blocked, categories, false));
            }
            AiSecurityConfig pii = piiConfig(config);
            String text = fields.stream().map(TextField::text)
                    .map(t -> config.isEnablePiiMasking() ? rules.moderate(t, pii).getSanitizedText() : t)
                    .collect(Collectors.joining(stream ? "" : "\n"));
            return external.moderate(text, direction, config).map(result -> {
                if (result.isFlagged()) {
                    categories.addAll(result.getCategories());
                    if ("BLOCK".equalsIgnoreCase(action)) return new Result(root, true, categories, false);
                    // A remote verdict has no trusted offending spans. Replace the complete inspected text.
                    if (!"DETECT".equalsIgnoreCase(action)) replaceAll(fields, config.getBlockMessage());
                }
                return new Result(root, false, categories, false);
            }).onErrorResume(error -> {
                categories.add("EXTERNAL_ERROR");
                return Mono.just(new Result(root, !"LOCAL".equalsIgnoreCase(config.getExternalFailureAction()), categories, true));
            });
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private void replaceAll(List<TextField> fields, String text) {
        fields.get(0).set(text);
        for (int i = 1; i < fields.size(); i++) fields.get(i).set("");
    }

    private boolean inspectLocal(TextField field, AiSecurityConfig config, String action, Set<String> categories) {
        AiSecurityConfig content = new AiSecurityConfig();
        content.setEnablePiiMasking(false);
        content.setBannedWords(config.getBannedWords());
        content.setProhibitedWords(config.getProhibitedWords());
        content.setBannedPatterns(config.getBannedPatterns());
        var result = rules.moderate(field.text(), content);
        boolean blocked = false;
        if (result.isFlagged()) {
            categories.addAll(result.getCategories());
            blocked = "BLOCK".equalsIgnoreCase(action);
            if (!"DETECT".equalsIgnoreCase(action)) field.set(result.getSanitizedText());
        }
        if (config.isEnablePiiMasking()) {
            var pii = rules.moderate(field.text(), piiConfig(config));
            if (pii.isFlagged()) {
                categories.addAll(pii.getCategories());
                blocked |= "BLOCK".equalsIgnoreCase(config.getPiiAction());
                if (!"DETECT".equalsIgnoreCase(config.getPiiAction())) field.set(pii.getSanitizedText());
            }
        }
        return blocked;
    }

    private AiSecurityConfig piiConfig(AiSecurityConfig config) {
        AiSecurityConfig pii = new AiSecurityConfig();
        pii.setPiiTypes(config.getPiiTypes());
        return pii;
    }

    private void collect(JsonNode node, List<TextField> fields) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                if (TEXT_KEYS.contains(entry.getKey()) && entry.getValue().isTextual()) {
                    fields.add(new TextField(object, entry.getKey()));
                } else if (TEXT_KEYS.contains(entry.getKey()) && entry.getValue().isArray()) {
                    JsonNode array = entry.getValue();
                    for (int i = 0; i < array.size(); i++) {
                        if (array.get(i).isTextual()) fields.add(new TextField(array, String.valueOf(i)));
                        else collect(array.get(i), fields);
                    }
                } else if (entry.getValue().isContainerNode()) collect(entry.getValue(), fields);
            });
        } else if (node != null && node.isArray()) node.forEach(child -> collect(child, fields));
    }
}
