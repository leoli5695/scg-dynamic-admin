package com.leoli.gateway.admin.dto;

import lombok.Data;

/**
 * Request body for creating/updating a provider credential.
 * {@code apiKey} carries the plaintext key over TLS on write only; it is never
 * echoed back in any response.
 *
 * @author leoli
 */
@Data
public class ProviderCredentialRequest {

    /** Optional stable ref; generated when omitted on create. */
    private String credentialRef;

    private String name;

    /** Provider id: deepseek / qwen / zhipu / moonshot / openai / anthropic / custom. */
    private String provider;

    /** Plaintext API key (write-only). */
    private String apiKey;

    private String tenantId;

    private Boolean enabled;
}
