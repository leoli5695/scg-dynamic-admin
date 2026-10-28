package com.leoli.gateway.admin.service.copilot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Session ids sent by a browser identify conversations only within its authenticated account. */
public final class CopilotSessionIds {
    private CopilotSessionIds() { }
    public static String scoped(String operator, String clientSessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (operator + "\0" + clientSessionId).getBytes(StandardCharsets.UTF_8));
            return "user-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
