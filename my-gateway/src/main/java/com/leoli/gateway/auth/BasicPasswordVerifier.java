package com.leoli.gateway.auth;

import org.springframework.security.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Shared password verification for Basic policy lookup and authentication. */
public final class BasicPasswordVerifier {

    private BasicPasswordVerifier() {
    }

    public static boolean matches(String password, String storedPassword, String algorithm) {
        if (password == null || storedPassword == null) {
            return false;
        }
        String normalized = algorithm == null || algorithm.isEmpty()
                ? "PLAIN" : algorithm.toUpperCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "PLAIN" -> constantTimeEquals(password, storedPassword);
                case "MD5" -> constantTimeEquals(digest(password, "MD5"), storedPassword);
                case "SHA256", "SHA-256" -> constantTimeEquals(digest(password, "SHA-256"), storedPassword);
                case "BCRYPT" -> BCrypt.checkpw(password, storedPassword);
                default -> false;
            };
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            // Malformed hashes and unavailable algorithms must fail closed.
            return false;
        }
    }

    private static String digest(String value, String algorithm) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm)
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
