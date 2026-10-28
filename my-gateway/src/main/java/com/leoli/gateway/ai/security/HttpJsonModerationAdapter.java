package com.leoli.gateway.ai.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.LinkedHashMap;

/** Reference protocol only; not a claim of compatibility with a particular commercial service. */
@Component
public class HttpJsonModerationAdapter implements ModerationAdapter {
    private final ObjectMapper mapper = new ObjectMapper();
    @Override public String id() { return "HTTP_JSON"; }

    @Override public Request prepare(Context context) {
        Map<String, String> headers = new LinkedHashMap<>(context.headers());
        headers.put("Content-Type", "application/json");
        try {
            return new Request("POST", context.endpoint(), headers,
                    mapper.writeValueAsBytes(Map.of("text", context.text(), "direction", context.direction())));
        } catch (Exception error) { throw new IllegalStateException("Cannot encode moderation request", error); }
    }

    @Override public Decision parse(Response response) throws Exception {
        if (response.status() < 200 || response.status() >= 300) throw new IllegalStateException("Moderation HTTP error");
        JsonNode flag = mapper.readTree(response.body()).path("flagged");
        if (!flag.isBoolean()) throw new IllegalStateException("Missing boolean moderation verdict");
        return flag.booleanValue() ? Decision.flagged("CONTENT") : Decision.clean();
    }
}
