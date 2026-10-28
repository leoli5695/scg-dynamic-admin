package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AuthPolicyDefinition;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteDebugAuthServiceTest {
    private final AuthPolicyService policies = mock(AuthPolicyService.class);
    private final RouteDebugAuthService auth = new RouteDebugAuthService(policies);

    private AuthPolicyDefinition policy(String type) {
        var p = new AuthPolicyDefinition(); p.setPolicyId("policy"); p.setPolicyName("test"); p.setAuthType(type);
        when(policies.getPolicy("instance", "policy")).thenReturn(p);
        return p;
    }

    @Test
    void jwtIsActuallySignedByAdminAndCanBeVerifiedWithTheConfiguredKey() {
        String secret = "debug-jwt-secret-32-bytes-minimum-length";
        var p = new AuthPolicyDefinition(); p.setPolicyId("jwt"); p.setAuthType("JWT");
        p.setSecretKey(secret); p.setJwtAlgorithm("HS256"); p.setJwtIssuer("debug-issuer");
        var realPolicies = spy(new AuthPolicyService());
        doReturn(p).when(realPolicies).getPolicy("instance", "jwt");
        doReturn(p).when(realPolicies).getPolicy("jwt");
        var request = new RouteDebugAuthService(realPolicies).authenticate(new Request.Builder().url("http://gateway/hello").build(), "instance", "jwt", null, null);
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).requireIssuer("debug-issuer").build()
                .parseSignedClaims(request.header("Authorization").substring(7)).getPayload();
        assertEquals("test-user", claims.getSubject());
        assertTrue(claims.getExpiration().after(new Date()));
    }

    @Test
    void hmacSignsExactBodyAndSortedRawQueryAndGeneratesFreshNonces() throws Exception {
        var p = policy("HMAC"); p.setAccessKey("access"); p.setSecretKey("hmac-secret"); p.setValidateContentMd5(true);
        byte[] body = "{\"text\":\"中文\"}".getBytes(StandardCharsets.UTF_8);
        var original = new Request.Builder().url("http://gateway/api/send?z=2&a=%2F")
                .post(RequestBody.create(body, (okhttp3.MediaType) null)).build();
        var request = auth.authenticate(original, "instance", "policy", null, null);
        String canonical = "POST\n/api/send\na=%2F&z=2\n" + request.header("X-Timestamp") + "\n" + request.header("X-Nonce")
                + "\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("hmac-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))), request.header("X-Signature"));
        assertEquals(Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body)), request.header("X-Content-MD5"));
        assertNotEquals(request.header("X-Nonce"), auth.authenticate(original, "instance", "policy", null, null).header("X-Nonce"));
    }

    @Test
    void policyOptionsExposeOnlyMetadataAndOpaqueCredentialIdentifiers() throws Exception {
        var p = policy("API_KEY"); p.setApiKeys(Map.of("first-private-key", Map.of("name", "First"), "second-private-key", Map.of("name", "Second")));
        p.setSecretKey("private-signing-secret");
        when(policies.getAllPolicies("instance")).thenReturn(List.of(p));
        when(policies.getPoliciesForRoute("route")).thenReturn(List.of(p));
        var options = auth.options("instance", "route");
        String json = new ObjectMapper().writeValueAsString(options);
        assertFalse(json.contains("private-key")); assertFalse(json.contains("private-signing-secret"));
        assertTrue(options.getFirst().bound());
        var selected = options.getFirst().credentials().stream().filter(c -> c.label().equals("Second")).findFirst().orElseThrow();
        var request = auth.authenticate(new Request.Builder().url("http://gateway/").build(), "instance", "policy", null, selected.id());
        assertEquals("second-private-key", request.header("X-API-Key"));
    }

    @Test
    void basicAndQueryApiKeysAreCalculatedWithoutChangingRequestPayload() {
        var p = policy("BASIC"); p.setBasicUsername("测试"); p.setBasicPassword("password");
        var original = new Request.Builder().url("http://gateway/path?a=1").build();
        var request = auth.authenticate(original, "instance", "policy", null, null);
        assertEquals("测试:password", new String(Base64.getDecoder().decode(request.header("Authorization").substring(6)), StandardCharsets.UTF_8));
        p.setAuthType("API_KEY"); p.setApiKey("secret"); p.setApiKeyHeader(null); p.setApiKeyQueryParam("api_key"); p.setApiKeyPrefix("key-");
        request = auth.authenticate(original, "instance", "policy", null, null);
        assertEquals("1", request.url().queryParameter("a")); assertEquals("key-secret", request.url().queryParameter("api_key"));
        p.setApiKey("key-secret");
        request = auth.authenticate(original, "instance", "policy", null, null);
        assertEquals("key-key-secret", request.url().queryParameter("api_key"));
    }

    @Test
    void rejectsUnavailablePoliciesAndRequiresTokensForVerificationOnlyJwtOrOauth() {
        var request = new Request.Builder().url("http://gateway/").build();
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> auth.authenticate(request, "other-instance", "policy", null, null));
        var p = policy("JWT"); p.setJwtAlgorithm("RS256");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> auth.authenticate(request, "instance", "policy", null, null));
        p.setAuthType("OAUTH2");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> auth.authenticate(request, "instance", "policy", null, null));
        assertEquals("Bearer external-token", auth.authenticate(request, "instance", "policy", "external-token", null).header("Authorization"));
    }

    @Test
    void passwordHashesAreNeverSentAsIfTheyWereBasicPasswords() {
        var p = policy("BASIC"); p.setBasicUsername("demo"); p.setBasicPassword("stored-hash"); p.setPasswordHashAlgorithm("BCRYPT");
        var request = new Request.Builder().url("http://gateway/").build();
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> auth.authenticate(request, "instance", "policy", null, null));
        var authenticated = auth.authenticate(request, "instance", "policy", "demo:actual-password", null);
        assertEquals("demo:actual-password", new String(Base64.getDecoder().decode(authenticated.header("Authorization").substring(6)), StandardCharsets.UTF_8));
    }
}
