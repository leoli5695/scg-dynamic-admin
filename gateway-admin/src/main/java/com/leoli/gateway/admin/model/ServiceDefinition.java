package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service definition model
 *
 * @author leoli
 */
@Data
public class ServiceDefinition {

    /**
     * Service name
     */
    private String name;

    /**
     * Service description (only for UI display, NOT pushed to Nacos)
     */
    private transient String description;

    /**
     * Service instance list
     */
    private List<ServiceInstance> instances = new ArrayList<>();

    /**
     * Load balancer strategy
     */
    private String loadBalancer = "round-robin";

    /**
     * Service ID (UUID) for display purposes.
     * This is used to show "serviceId (serviceName)" in UI dropdowns.
     */
    private transient String serviceId;

    // ============== AI service (category = AI) ==============
    // Mirrors Higress LlmProvider: type/protocol/tokens/rawConfigs. The API key is
    // never stored here - it is encrypted into provider_credentials and referenced by
    // credentialRef (see ProviderCredentialService).

    /**
     * Service category: NORMAL (default) or AI (an LLM provider service).
     * AI services carry the provider fields below and can only be bound by AI routes.
     */
    private String category = ServiceCategory.NORMAL;

    /**
     * LLM provider type for AI services, e.g. qwen / openai / deepseek / zhipu /
     * moonshot / anthropic / custom. Aligns with the gateway's built-in provider catalogue.
     */
    private String providerType;

    /** Wire protocol for AI services. Defaults to OPENAI-compatible passthrough. */
    private String protocol;

    /**
     * Base URL override for AI services (relay / egress proxy / self-hosted backend).
     * Falls back to the provider's built-in default when blank.
     */
    private String baseUrl;

    /**
     * Reference into the encrypted provider_credentials store holding this AI service's
     * API key. Set by the backend when an apiKey is supplied; never the plaintext key.
     */
    private String credentialRef;

    /**
     * Plaintext API key input for create/rotate. Transient: never persisted to the
     * service metadata JSON nor returned by any read API. The backend encrypts it into
     * provider_credentials and stores only {@link #credentialRef}.
     */
    private transient String apiKey;

    /** Service category values. */
    public static final class ServiceCategory {
        private ServiceCategory() {
        }

        public static final String NORMAL = "NORMAL";
        public static final String AI = "AI";
    }

    /** True when this is an AI (LLM provider) service. */
    @JsonIgnore
    public boolean isAiService() {
        return ServiceCategory.AI.equalsIgnoreCase(category);
    }

    /**
     * Service instance
     */
    @Data
    public static class ServiceInstance {
        /**
         * Instance ID
         */
        private String instanceId;
        
        /**
         * Service IP
         */
        private String ip;
        
        /**
         * Service port
         */
        private int port;
        
        /**
         * Weight
         */
        private int weight = 1;
        
        /**
         * Whether enabled
         */
        private boolean enabled = true;
        
        /**
         * Metadata
         */
        private Map<String, String> metadata = new HashMap<>();
        
        public ServiceInstance() {}
        
        public ServiceInstance(String ip, int port) {
            this.ip = ip;
            this.port = port;
            this.instanceId = ip + ":" + port;
        }
    }
}
