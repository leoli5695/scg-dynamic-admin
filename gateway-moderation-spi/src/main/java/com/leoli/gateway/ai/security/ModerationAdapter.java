package com.leoli.gateway.ai.security;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Java SPI for provider-specific request building/signing and response interpretation.
 * Implementations must be thread-safe and CPU-only: networking and timeout belong to the gateway.
 * Deploy on the gateway classpath with META-INF/services/<this interface FQN>, then restart.
 * This SPI does not participate in the existing GatewayFilterFactory hot-load mechanism.
 */
public interface ModerationAdapter {
    String id();
    Request prepare(Context context);
    Decision parse(Response response) throws Exception;

    record Context(String text, String direction, URI endpoint, Map<String, String> headers, Map<String, Object> options) {}
    record Request(String method, URI endpoint, Map<String, String> headers, byte[] body) {}
    record Response(int status, Map<String, List<String>> headers, byte[] body) {}
    record Decision(boolean flagged, List<String> categories) {
        public static Decision clean() { return new Decision(false, List.of()); }
        public static Decision flagged(String category) { return new Decision(true, List.of(category)); }
    }
}
