package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.AuthPolicyDefinition;
import lombok.RequiredArgsConstructor;
import okhttp3.Request;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Resolves credentials only on the control plane; the browser receives policy metadata, never signing keys. */
@Service
@RequiredArgsConstructor
public class RouteDebugAuthService {
    private final AuthPolicyService policies;

    public record CredentialOption(String id, String label) { }
    public record PolicyOption(String policyId, String policyName, String authType, boolean bound,
                               boolean automatic, List<CredentialOption> credentials) { }

    public List<PolicyOption> options(String instanceId, String routeId) {
        Set<String> bound = new HashSet<>();
        policies.getPoliciesForRoute(routeId).forEach(p -> bound.add(p.getPolicyId()));
        return policies.getAllPolicies(instanceId).stream().filter(AuthPolicyDefinition::isEnabled).map(p -> {
            List<CredentialOption> credentials = new ArrayList<>();
            if ("API_KEY".equals(p.getAuthType()) && p.getApiKeys() != null) {
                p.getApiKeys().forEach((key, metadata) -> credentials.add(new CredentialOption(fingerprint(key),
                        metadata == null || metadata.get("name") == null ? "API Key " + (credentials.size() + 1) : String.valueOf(metadata.get("name")))));
            }
            if ("HMAC".equals(p.getAuthType()) && p.getAccessKeySecrets() != null) {
                p.getAccessKeySecrets().keySet().forEach(key -> credentials.add(new CredentialOption(fingerprint(key), key)));
            }
            boolean automatic = switch (p.getAuthType()) {
                case "OAUTH2" -> false;
                case "JWT" -> p.getSecretKey() != null && (p.getJwtAlgorithm() == null || p.getJwtAlgorithm().startsWith("HS"));
                case "BASIC" -> p.getBasicUsername() != null && p.getBasicPassword() != null && plaintextBasic(p);
                default -> true;
            };
            return new PolicyOption(p.getPolicyId(), p.getPolicyName(), p.getAuthType(), bound.contains(p.getPolicyId()), automatic, credentials);
        }).toList();
    }

    public Request authenticate(Request request, String instanceId, String policyId, String credential, String credentialId) {
        if (policyId == null || policyId.isBlank()) return request;
        var policy = policies.getPolicy(instanceId, policyId);
        if (policy == null || !policy.isEnabled()) throw badRequest("Authentication policy is unavailable for this instance");
        String supplied = credential == null ? "" : credential.trim();
        var builder = request.newBuilder();
        switch (policy.getAuthType()) {
            case "API_KEY" -> {
                String key = supplied;
                if (key.isEmpty() && policy.getApiKeys() != null && !policy.getApiKeys().isEmpty()) {
                    key = selectedKey(policy.getApiKeys().keySet(), credentialId);
                }
                if (key.isEmpty()) key = policy.getApiKey();
                if (key == null || key.isBlank()) throw badRequest("Select or enter an API key");
                String prefix = policy.getApiKeyPrefix() == null ? "" : policy.getApiKeyPrefix();
                String value = prefix + key;
                if (policy.getApiKeyQueryParam() != null && !policy.getApiKeyQueryParam().isBlank()
                        && (policy.getApiKeyHeader() == null || policy.getApiKeyHeader().isBlank())) {
                    builder.url(request.url().newBuilder().setQueryParameter(policy.getApiKeyQueryParam(), value).build());
                } else builder.header(policy.getApiKeyHeader() == null || policy.getApiKeyHeader().isBlank() ? "X-API-Key" : policy.getApiKeyHeader(), value);
            }
            case "BASIC" -> {
                if (supplied.isEmpty()) {
                    if (!plaintextBasic(policy)) throw badRequest("The Basic policy stores password hashes; enter the original username:password");
                    if (policy.getBasicUsername() == null || policy.getBasicPassword() == null) throw badRequest("Enter Basic username:password");
                    supplied = policy.getBasicUsername() + ":" + policy.getBasicPassword();
                }
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(supplied.getBytes(StandardCharsets.UTF_8)));
            }
            case "JWT" -> {
                if (supplied.isEmpty()) {
                    if (policy.getJwtAlgorithm() != null && !policy.getJwtAlgorithm().startsWith("HS")) {
                        throw badRequest("This JWT policy contains a verification key only; supply a token signed by its issuer");
                    }
                    var example = policies.generateUsageExample(policyId);
                    if (example.get("error") != null || !(example.get("headerValue") instanceof String value)) {
                        throw badRequest("Could not generate a JWT for this policy; supply a valid token");
                    }
                    builder.header("Authorization", value);
                } else builder.header("Authorization", bearer(supplied));
            }
            case "OAUTH2" -> {
                if (supplied.isEmpty()) throw badRequest("Supply a valid OAuth2 access token");
                builder.header("Authorization", bearer(supplied));
            }
            case "HMAC" -> signHmac(builder, request, policy, credentialId);
            default -> throw badRequest("Unsupported authentication type");
        }
        return builder.build();
    }

    private void signHmac(Request.Builder builder, Request request, AuthPolicyDefinition policy, String credentialId) {
        try {
            String key = policy.getAccessKey();
            String secret = policy.getSecretKey();
            if (policy.getAccessKeySecrets() != null && !policy.getAccessKeySecrets().isEmpty()) {
                key = selectedKey(policy.getAccessKeySecrets().keySet(), credentialId);
                secret = policy.getAccessKeySecrets().get(key);
            }
            if (key == null || secret == null || secret.isBlank()) throw badRequest("HMAC key and secret are required");
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();
            String query = request.url().encodedQuery();
            String[] parts = query == null ? new String[0] : query.split("&");
            Arrays.sort(parts);
            String canonical = request.method() + "\n" + request.url().encodedPath() + "\n" + String.join("&", parts) + "\n" + timestamp + "\n" + nonce;
            byte[] bytes = new byte[0];
            if (request.body() != null) {
                var buffer = new okio.Buffer(); request.body().writeTo(buffer); bytes = buffer.readByteArray();
            }
            if (bytes.length > 0) canonical += "\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String algorithm = switch (String.valueOf(policy.getSignatureAlgorithm()).toUpperCase(Locale.ROOT)) {
                case "HMAC-SHA1" -> "HmacSHA1";
                case "HMAC-SHA512" -> "HmacSHA512";
                default -> "HmacSHA256";
            };
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            builder.header("X-Access-Key", key).header("X-Timestamp", timestamp).header("X-Nonce", nonce)
                    .header("X-Signature", Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
            if (policy.isValidateContentMd5()) builder.header("X-Content-MD5", Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)));
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw badRequest("Could not sign the HMAC request"); }
    }

    private static String selectedKey(Set<String> keys, String id) {
        if (id == null || id.isBlank()) {
            if (keys.size() == 1) return keys.iterator().next();
            throw badRequest("Select a credential for this authentication policy");
        }
        return keys.stream().filter(key -> fingerprint(key).equals(id)).findFirst().orElseThrow(() -> badRequest("Selected credential is no longer available"));
    }

    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String bearer(String value) { return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value : "Bearer " + value; }
    private static boolean plaintextBasic(AuthPolicyDefinition policy) {
        return policy.getPasswordHashAlgorithm() == null || policy.getPasswordHashAlgorithm().isBlank()
                || "PLAIN".equalsIgnoreCase(policy.getPasswordHashAlgorithm());
    }
    private static ResponseStatusException badRequest(String value) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, value); }
}
