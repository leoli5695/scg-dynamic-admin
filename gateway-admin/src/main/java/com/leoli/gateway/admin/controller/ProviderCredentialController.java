package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.dto.ApiResponse;
import com.leoli.gateway.admin.dto.ProviderCredentialRequest;
import com.leoli.gateway.admin.service.ProviderCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider API credential management for the AI proxy.
 * <p>
 * Admin CRUD is ADMIN-authority (default RBAC) and write-only for the key: reads
 * return a masked view. The {@code /internal/{ref}/key} endpoint returns the
 * decrypted key and is guarded by X-Internal-Token (permitAll in SecurityConfig,
 * matched in InternalApiTokenFilter) - it is the only path that yields plaintext.
 *
 * @author leoli
 */
@RestController
@RequestMapping("/api/provider-credentials")
@RequiredArgsConstructor
@Slf4j
public class ProviderCredentialController extends BaseController {

    private final ProviderCredentialService credentialService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        return ok(credentialService.listMasked());
    }

    @GetMapping("/{ref}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String ref) {
        try {
            return ok(credentialService.getMasked(ref));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody ProviderCredentialRequest req) {
        try {
            Map<String, Object> view = credentialService.create(
                    req.getCredentialRef(), req.getName(), req.getProvider(),
                    req.getApiKey(), req.getTenantId(), getOperator());
            return ok(view, "Credential created successfully");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create provider credential: {}", e.getMessage());
            return fail("Failed to create provider credential: " + e.getMessage());
        }
    }

    @PutMapping("/{ref}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable String ref, @RequestBody ProviderCredentialRequest req) {
        try {
            Map<String, Object> view = credentialService.update(
                    ref, req.getName(), req.getApiKey(), req.getEnabled(), req.getTenantId(), getOperator());
            return ok(view, "Credential updated successfully");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update provider credential: {}", e.getMessage());
            return fail("Failed to update provider credential: " + e.getMessage());
        }
    }

    @DeleteMapping("/{ref}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String ref) {
        try {
            credentialService.delete(ref, getOperator());
            return ok("Credential deleted successfully");
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete provider credential: {}", e.getMessage());
            return fail("Failed to delete provider credential: " + e.getMessage());
        }
    }

    // ============== Internal API (gateway pods fetch decrypted key) ==============

    /**
     * Returns the decrypted provider key. Guarded by X-Internal-Token; never
     * exposed to the browser UI. Called by the gateway's ProviderKeyResolver.
     */
    @GetMapping("/internal/{ref}/key")
    public ResponseEntity<ApiResponse<Map<String, String>>> resolveKey(@PathVariable String ref) {
        try {
            String apiKey = credentialService.resolveKey(ref);
            Map<String, String> body = new LinkedHashMap<>();
            body.put("credentialRef", ref);
            body.put("apiKey", apiKey);
            return ok(body);
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            // Do not leak the underlying crypto/key error detail to the caller.
            log.error("Failed to resolve provider credential {}: {}", ref, e.getMessage());
            return fail("Failed to resolve credential");
        }
    }
}
