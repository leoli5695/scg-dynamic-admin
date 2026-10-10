package com.leoli.gateway.filter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AiPromptTemplateConfig;
import com.leoli.gateway.model.AiPromptTemplateConfig.Message;
import com.leoli.gateway.model.AiPromptTemplateConfig.PromptTemplate;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI prompt template / decorator filter (mirrors Higress ai-prompt-template +
 * ai-prompt-decorator). Rewrites the OpenAI-compatible request body before provider
 * routing: inject/override a system prompt, prepend/append messages, substitute
 * {{var}} placeholders from a request "variables" object, optionally select a named
 * template, and optionally override the model. Control fields (variables / template
 * selector) are stripped before forwarding.
 * <p>
 * Runs at {@link FilterOrderConstants#AI_PROMPT_TEMPLATE} (10004), just before
 * AiProviderRoutingFilter (10005), so the router reads the decorated body and any
 * model override.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AiPromptTemplateFilter implements GlobalFilter, Ordered {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiPromptTemplateFilter(StrategyManager strategyManager, ObjectMapper objectMapper) {
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        AiPromptTemplateConfig config = strategyManager.getConfig(routeId,
                StrategyDefinition.TYPE_AI_PROMPT_TEMPLATE, AiPromptTemplateConfig.class);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }

        long maxBodySize = Math.max(1024, config.getMaxBodySize());
        ServerHttpResponse response = exchange.getResponse();

        return DataBufferUtils.join(exchange.getRequest().getBody(), (int) Math.min(maxBodySize, Integer.MAX_VALUE))
                .defaultIfEmpty(response.bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    try {
                        dataBuffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                    if (bytes.length == 0) {
                        return chain.filter(exchange);
                    }
                    byte[] out;
                    try {
                        out = decorate(bytes, exchange, config);
                    } catch (Exception e) {
                        // Never break the request on a decoration error - forward the original.
                        log.warn("AI prompt template: decoration failed on route {}, forwarding original: {}",
                                routeId, e.getMessage());
                        out = bytes;
                    }
                    ServerHttpRequest newRequest = decorateRequest(exchange, out);
                    return chain.filter(exchange.mutate().request(newRequest).build());
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    log.warn("AI prompt template: body exceeds maxBodySize {} on route {}", maxBodySize, routeId);
                    response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return response.setComplete();
                });
    }

    /** Apply the selected template to the request body; returns the (possibly unchanged) bytes. */
    private byte[] decorate(byte[] bytes, ServerWebExchange exchange, AiPromptTemplateConfig config) throws Exception {
        JsonNode root = objectMapper.readTree(bytes);
        if (!(root instanceof ObjectNode)) {
            return bytes;
        }
        ObjectNode obj = (ObjectNode) root;

        Map<String, String> vars = extractVariables(obj, config);
        PromptTemplate template = selectTemplate(config, exchange, obj);

        boolean hasSystem = template.getSystemPrompt() != null && !template.getSystemPrompt().isBlank();
        boolean hasPrepend = template.getPrependMessages() != null && !template.getPrependMessages().isEmpty();
        boolean hasAppend = template.getAppendMessages() != null && !template.getAppendMessages().isEmpty();
        boolean hasModelOverride = template.getModel() != null && !template.getModel().isBlank();
        boolean strip = config.isStripControlFields()
                && (hasField(obj, config.getVariablesBodyField()) || hasField(obj, config.getTemplateBodyField()));

        if (!hasSystem && !hasPrepend && !hasAppend && !hasModelOverride && !strip) {
            return bytes; // nothing to do - avoid a needless re-serialization
        }

        if (hasSystem || hasPrepend || hasAppend) {
            obj.set("messages", buildMessages(obj, template, vars, hasSystem));
        }
        if (hasModelOverride) {
            obj.put("model", template.getModel().trim());
        }
        if (config.isStripControlFields()) {
            removeField(obj, config.getVariablesBodyField());
            removeField(obj, config.getTemplateBodyField());
        }
        return objectMapper.writeValueAsBytes(obj);
    }

    /** Compose [system?] + prepend + original(- system if OVERRIDE) + append. */
    private ArrayNode buildMessages(ObjectNode obj, PromptTemplate template, Map<String, String> vars, boolean hasSystem) {
        ArrayNode result = objectMapper.createArrayNode();
        boolean override = "OVERRIDE".equalsIgnoreCase(template.getSystemPromptMode());

        if (hasSystem) {
            result.add(messageNode("system", render(template.getSystemPrompt(), vars)));
        }
        if (template.getPrependMessages() != null) {
            for (Message m : template.getPrependMessages()) {
                if (m != null && m.getContent() != null) {
                    result.add(messageNode(roleOr(m.getRole(), "system"), render(m.getContent(), vars)));
                }
            }
        }
        JsonNode original = obj.get("messages");
        if (original instanceof ArrayNode) {
            for (JsonNode m : (ArrayNode) original) {
                if (override && m.path("role").asText("").equalsIgnoreCase("system")) {
                    continue; // drop original system messages when overriding
                }
                result.add(m);
            }
        }
        if (template.getAppendMessages() != null) {
            for (Message m : template.getAppendMessages()) {
                if (m != null && m.getContent() != null) {
                    result.add(messageNode(roleOr(m.getRole(), "system"), render(m.getContent(), vars)));
                }
            }
        }
        return result;
    }

    private PromptTemplate selectTemplate(AiPromptTemplateConfig config, ServerWebExchange exchange, ObjectNode obj) {
        String selector = null;
        if (notBlank(config.getTemplateHeader())) {
            selector = exchange.getRequest().getHeaders().getFirst(config.getTemplateHeader());
        }
        if (!notBlank(selector) && notBlank(config.getTemplateBodyField())) {
            JsonNode n = obj.get(config.getTemplateBodyField());
            if (n != null && n.isTextual()) {
                selector = n.asText();
            }
        }
        if (notBlank(selector) && config.getTemplates() != null) {
            for (PromptTemplate t : config.getTemplates()) {
                if (t != null && selector.equalsIgnoreCase(t.getName())) {
                    return t;
                }
            }
            log.debug("AI prompt template: no template named '{}', using default decoration", selector);
        }
        // Default decoration from the flat top-level fields.
        PromptTemplate d = new PromptTemplate();
        d.setSystemPrompt(config.getSystemPrompt());
        d.setSystemPromptMode(config.getSystemPromptMode());
        d.setPrependMessages(config.getPrependMessages());
        d.setAppendMessages(config.getAppendMessages());
        d.setModel(config.getModel());
        return d;
    }

    private Map<String, String> extractVariables(ObjectNode obj, AiPromptTemplateConfig config) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (!notBlank(config.getVariablesBodyField())) {
            return vars;
        }
        JsonNode v = obj.get(config.getVariablesBodyField());
        if (v != null && v.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                vars.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString());
            }
        }
        return vars;
    }

    /** Substitute {{key}} from vars; unknown placeholders are left intact. */
    private String render(String tmpl, Map<String, String> vars) {
        if (tmpl == null || tmpl.isEmpty() || vars == null || vars.isEmpty()) {
            return tmpl;
        }
        Matcher m = VAR_PATTERN.matcher(tmpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private ServerHttpRequest decorateRequest(ServerWebExchange exchange, byte[] outBytes) {
        ServerHttpRequest request = exchange.getRequest();
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(outBytes));
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.setContentLength(outBytes.length);
                return headers;
            }
        };
    }

    private ObjectNode messageNode(String role, String content) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("role", role);
        n.put("content", content);
        return n;
    }

    private static String roleOr(String role, String fallback) {
        return notBlank(role) ? role : fallback;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean hasField(ObjectNode obj, String field) {
        return notBlank(field) && obj.has(field);
    }

    private static void removeField(ObjectNode obj, String field) {
        if (notBlank(field)) {
            obj.remove(field);
        }
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.AI_PROMPT_TEMPLATE;
    }
}
