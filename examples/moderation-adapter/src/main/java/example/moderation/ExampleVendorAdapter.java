package example.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.ai.security.ModerationAdapter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Illustrative protocol only. This is NOT an implementation of any commercial provider. */
public final class ExampleVendorAdapter implements ModerationAdapter {
    private final ObjectMapper mapper = new ObjectMapper();
    @Override public String id() { return "EXAMPLE_VENDOR"; }

    @Override public Request prepare(Context context) {
        Object appId = context.options().get("appId");
        if (!(appId instanceof String s) || s.isBlank()) throw new IllegalArgumentException("appId is required");
        try {
            byte[] bytes = mapper.writeValueAsBytes(Map.of("content", context.text(), "scene", context.direction(), "appId", appId));
            Map<String, String> headers = new LinkedHashMap<>(context.headers());
            headers.put("Content-Type", "application/json");
            // Provider-specific signing belongs here. Sign these exact bytes, then add the signature header.
            return new Request("POST", context.endpoint(), headers, bytes);
        } catch (Exception e) { throw new IllegalStateException("Cannot build provider request", e); }
    }

    @Override public Decision parse(Response response) throws Exception {
        if (response.status() != 200) throw new IllegalStateException("Provider HTTP failure");
        JsonNode body = mapper.readTree(response.body());
        if (!body.path("code").isIntegralNumber() || body.path("code").asInt() != 0) {
            throw new IllegalStateException("Provider business failure");
        }
        return switch (body.path("data").path("decision").asText()) {
            case "pass" -> Decision.clean();
            case "reject" -> Decision.flagged("PROHIBITED");
            default -> throw new IllegalStateException("Unknown provider verdict");
        };
    }
}
