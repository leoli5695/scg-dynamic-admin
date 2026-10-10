package com.leoli.gateway.ai;

import reactor.core.publisher.Mono;

/**
 * Resolves a provider API key from a credential reference at request time.
 * The plaintext key is fetched from the admin console over the internal-token
 * channel and cached briefly; it is never stored in gateway config.
 *
 * @author leoli
 */
public interface ProviderKeyResolver {

    /**
     * @param credentialRef the ref stored in AiProxyConfig.providerApiKeyRefs
     * @return the plaintext key, or {@link Mono#empty()} when it cannot be resolved
     */
    Mono<String> resolveKey(String credentialRef);
}
