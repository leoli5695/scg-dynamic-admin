package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.service.GatewayTokenRevocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Revocation of data-plane (business traffic) JWTs.
 * <p>
 * Writing an entry here makes every gateway node reject the token within its
 * local verdict-cache window, without waiting for the token's own exp. Both
 * endpoints are ADMIN-only via the global {@code /api/**} rule in SecurityConfig,
 * and the raw token is never logged.
 *
 * @author leoli
 */
@RestController
@RequestMapping("/api/gateway/jwt-revocations")
@RequiredArgsConstructor
@Slf4j
public class GatewayTokenRevocationController {

    private final GatewayTokenRevocationService revocationService;

    /**
     * Blacklist a token. Body: {@code {"token": "<jwt>"}} and/or
     * {@code {"jti": "<id>"}}, optionally {@code {"ttlSeconds": 3600}}.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(@RequestBody Map<String, Object> body) {
        try {
            GatewayTokenRevocationService.Result result = revocationService.revoke(
                    stringOf(body, "token"),
                    stringOf(body, "jti"),
                    longOf(body, "ttlSeconds"));
            return ResponseEntity.ok(ApiResponse.success(payload(result), "Token revoked"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(ApiResponse.error(503, e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to revoke data-plane JWT", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to revoke token"));
        }
    }

    /**
     * Lift a revocation before its TTL expires. Same body as the POST.
     */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> unrevoke(@RequestBody Map<String, Object> body) {
        try {
            GatewayTokenRevocationService.Result result = revocationService.unrevoke(
                    stringOf(body, "token"),
                    stringOf(body, "jti"));
            return ResponseEntity.ok(ApiResponse.success(payload(result), "Revocation lifted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(ApiResponse.error(503, e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to lift data-plane JWT revocation", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to lift revocation"));
        }
    }

    private static Map<String, Object> payload(GatewayTokenRevocationService.Result result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ids", result.ids());
        payload.put("ttlSeconds", result.ttlSeconds());
        return payload;
    }

    private static String stringOf(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        return value != null ? String.valueOf(value) : null;
    }

    private static Long longOf(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ttlSeconds must be a number");
            }
        }
        return null;
    }
}
