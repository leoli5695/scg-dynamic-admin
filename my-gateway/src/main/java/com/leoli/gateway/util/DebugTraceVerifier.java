package com.leoli.gateway.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Short-lived admin signatures permit metadata capture without trusting public debug flags. */
@Component
public class DebugTraceVerifier {
    public static final String HEADER = "X-Gateway-Debug-Trace";
    public static final String TRUSTED_ATTR = "gateway.debug.trace.verified";
    private final String secret;
    private final String instanceId;
    public DebugTraceVerifier(@Value("${gateway.admin.internal-token:${GATEWAY_INTERNAL_TOKEN:}}") String secret,
            @Value("${gateway.instance-id:${GATEWAY_INSTANCE_ID:gateway-1}}") String instanceId) {
        this.secret = secret; this.instanceId = instanceId;
    }
    public boolean verify(String signature, String method, URI uri) {
        if (secret == null || secret.isBlank() || signature == null || signature.length() > 180) return false;
        try {
            String[] parts = signature.split(":", -1);
            if (parts.length != 3 || !parts[1].matches("[a-f0-9]{32}")) return false;
            long issued = Long.parseLong(parts[0]);
            long age = System.currentTimeMillis() - issued;
            if (age < -5000 || age > 60000) return false;
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String payload = instanceId + "\n" + method + "\n" + path + "\n" + parts[0] + "\n" + parts[1];
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(parts[2]));
        } catch (Exception invalid) { return false; }
    }
}
