package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AiUpstream;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.ServiceDefinition;
import com.leoli.gateway.admin.model.ServiceEntity;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.ServiceRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects an AI route's bound AI services into the route's companion AI_PROXY strategy.
 * <p>
 * Each bound service is projected independently into {@code AiProxyConfig.upstreams}, retaining
 * its identity, endpoint, credential, weight and model mapping even when several services share
 * a provider type. Legacy provider maps remain for compatibility with older configurations;
 * the service-level list is authoritative on the updated data plane.
 * <p>
 * Field names written into the strategy config mirror {@code com.leoli.gateway.model.AiProxyConfig}.
 *
 * @author leoli
 */
@Slf4j
@Service
public class AiRouteProjector {

    /** Placeholder upstream for an AI route; the data-plane filter overrides the real target. */
    private static final String AI_PLACEHOLDER_URI = "https://ai-gateway.invalid";

    @Autowired
    private StrategyService strategyService;

    @Autowired
    private StrategyRepository strategyRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Normalize an AI route before validation/persistence: ensure it carries a URI so the
     * standard {@code RouteValidator} (which requires one) passes. The value is a placeholder -
     * {@code AiProviderRoutingFilter} overwrites the upstream URL per request.
     */
    public void normalizeAiRoute(RouteDefinition route) {
        if (route == null || !route.isAiRoute()) {
            return;
        }
        if (!StringUtils.hasText(route.getUri())) {
            route.setUri(AI_PLACEHOLDER_URI);
        }
    }

    /**
     * Validate an AI route: it must bind at least one AI service, and every bound service must
     * exist, be category AI, declare a provider type and hold a credential (API key).
     */
    public void validateAiRoute(RouteDefinition route) {
        if (route == null || !route.isAiRoute()) {
            return;
        }
        List<AiUpstream> upstreams = route.getAiUpstreams();
        if (upstreams == null || upstreams.isEmpty()) {
            throw new IllegalArgumentException("AI 路由必须至少绑定一个 AI 服务");
        }
        for (AiUpstream up : upstreams) {
            if (up == null || !StringUtils.hasText(up.getProvider())) {
                throw new IllegalArgumentException("AI 路由存在未指定服务的上游绑定");
            }
            validateWeight(up);
            requireConfiguredAiService(up.getProvider());
        }
        if (route.getAiFallback() != null && Boolean.TRUE.equals(route.getAiFallback().getEnabled())) {
            List<AiUpstream> fbs = route.getAiFallback().getUpstreams();
            if (fbs == null || fbs.isEmpty()) {
                throw new IllegalArgumentException("启用 fallback 时必须至少配置一个 fallback AI 服务");
            }
            for (AiUpstream fb : fbs) {
                if (fb == null || !StringUtils.hasText(fb.getProvider())) {
                    throw new IllegalArgumentException("fallback 存在未指定服务的上游绑定");
                }
                requireConfiguredAiService(fb.getProvider());
            }
        }
    }

    /**
     * Create or update the companion ROUTE-scoped AI_PROXY strategy so the bound AI services
     * reach the data plane. For a non-AI route, any stale companion strategy is disabled.
     */
    public void syncProxyStrategy(RouteDefinition route, String routeId, String instanceId) {
        if (route == null) {
            return;
        }
        if (!route.isAiRoute()) {
            disableCompanion(routeId, instanceId);
            return;
        }

        Map<String, Object> config = buildProxyConfig(route);

        StrategyDefinition def = new StrategyDefinition();
        def.setStrategyType(StrategyDefinition.TYPE_AI_PROXY);
        def.setScope(StrategyDefinition.SCOPE_ROUTE);
        def.setRouteId(routeId);
        def.setEnabled(true);
        def.setConfig(config);
        def.setDescription("由 AI 路由绑定自动生成");

        StrategyEntity existing = findCompanion(routeId, instanceId);
        if (existing != null) {
            def.setStrategyName(existing.getStrategyName());
            strategyService.updateStrategy(existing.getStrategyId(), def);
            log.info("AI route {} companion AI_PROXY strategy updated ({} upstreams)",
                    routeId, route.getAiUpstreams().size());
        } else {
            def.setStrategyName("ai-proxy-" + routeId);
            strategyService.createStrategy(def, instanceId);
            log.info("AI route {} companion AI_PROXY strategy created ({} upstreams)",
                    routeId, route.getAiUpstreams().size());
        }
    }

    /** Build the AiProxyConfig-shaped map from the route's bound AI services. */
    private Map<String, Object> buildProxyConfig(RouteDefinition route) {
        Map<String, String> refs = new LinkedHashMap<>();
        Map<String, String> baseUrlOverrides = new LinkedHashMap<>();
        Map<String, String> modelMapping = new LinkedHashMap<>();
        List<Map<String, Object>> upstreams = new ArrayList<>();

        for (AiUpstream up : route.getAiUpstreams()) {
            validateWeight(up);
            ServiceDefinition svc = requireConfiguredAiService(up.getProvider());
            String providerType = svc.getProviderType();
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("serviceId", up.getProvider());
            target.put("provider", providerType);
            target.put("credentialRef", svc.getCredentialRef());
            target.put("weight", up.getWeight() == null ? 100 : up.getWeight());
            target.put("baseUrlOverride", svc.getBaseUrl());
            target.put("modelMapping", up.getModelMapping());
            upstreams.add(target);
            refs.put(providerType, svc.getCredentialRef());
            if (StringUtils.hasText(svc.getBaseUrl())) {
                baseUrlOverrides.put(providerType, svc.getBaseUrl().trim());
            }
            if (up.getModelMapping() != null && !up.getModelMapping().isEmpty()) {
                modelMapping.putAll(up.getModelMapping());
            }
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        config.put("providerSelection", "MODEL");
        config.put("upstreams", upstreams);
        config.put("providerApiKeyRefs", refs);
        if (!baseUrlOverrides.isEmpty()) {
            config.put("providerBaseUrlOverride", baseUrlOverrides);
        }
        if (!modelMapping.isEmpty()) {
            config.put("modelMapping", modelMapping);
        }

        if (route.getAiFallback() != null && Boolean.TRUE.equals(route.getAiFallback().getEnabled())) {
            List<Map<String, Object>> fallbacks = new ArrayList<>();
            for (AiUpstream fb : route.getAiFallback().getUpstreams()) {
                ServiceDefinition svc = requireConfiguredAiService(fb.getProvider());
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("serviceId", fb.getProvider());
                target.put("modelMapping", fb.getModelMapping());
                target.put("provider", svc.getProviderType());
                target.put("credentialRef", svc.getCredentialRef());
                if (StringUtils.hasText(svc.getBaseUrl())) {
                    target.put("baseUrlOverride", svc.getBaseUrl().trim());
                }
                fallbacks.add(target);
            }
            config.put("fallbacks", fallbacks);
        }
        return config;
    }

    private void validateWeight(AiUpstream upstream) {
        if (upstream.getWeight() != null && (upstream.getWeight() < 1 || upstream.getWeight() > 100)) {
            throw new IllegalArgumentException("AI 上游权重必须在 1 到 100 之间");
        }
    }

    private StrategyEntity findCompanion(String routeId, String instanceId) {
        List<StrategyEntity> bound = (instanceId != null && !instanceId.isEmpty())
                ? strategyRepository.findByRouteIdAndInstanceId(routeId, instanceId)
                : strategyRepository.findByRouteId(routeId);
        return bound.stream()
                .filter(s -> StrategyDefinition.TYPE_AI_PROXY.equals(s.getStrategyType()))
                .findFirst()
                .orElse(null);
    }

    private void disableCompanion(String routeId, String instanceId) {
        StrategyEntity existing = findCompanion(routeId, instanceId);
        if (existing == null) {
            return;
        }
        StrategyDefinition def = new StrategyDefinition();
        def.setStrategyName(existing.getStrategyName());
        def.setStrategyType(StrategyDefinition.TYPE_AI_PROXY);
        def.setScope(StrategyDefinition.SCOPE_ROUTE);
        def.setRouteId(routeId);
        def.setEnabled(false);
        def.setConfig(new LinkedHashMap<>());
        def.setDescription("由 AI 路由绑定自动生成（路由已改回普通类型，停用）");
        strategyService.updateStrategy(existing.getStrategyId(), def);
        log.info("AI route {} reverted to normal - companion AI_PROXY strategy disabled", routeId);
    }

    /**
     * Resolve a bound service by name and assert it is a fully-configured AI service.
     * Returns the parsed definition (carrying providerType / credentialRef / baseUrl).
     */
    private ServiceDefinition requireConfiguredAiService(String serviceName) {
        ServiceEntity entity = serviceRepository.findByServiceName(serviceName);
        if (entity == null) {
            throw new IllegalArgumentException("AI 路由绑定的服务不存在: " + serviceName);
        }
        if (!ServiceDefinition.ServiceCategory.AI.equalsIgnoreCase(entity.getCategory())) {
            throw new IllegalArgumentException("AI 路由只能绑定 AI 服务，而 '" + serviceName + "' 是普通服务");
        }
        ServiceDefinition def;
        try {
            def = objectMapper.readValue(entity.getMetadata(), ServiceDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI 服务配置解析失败: " + serviceName, e);
        }
        if (def == null || !StringUtils.hasText(def.getProviderType())) {
            throw new IllegalArgumentException("AI 服务未指定 provider 类型: " + serviceName);
        }
        if (!StringUtils.hasText(def.getCredentialRef())) {
            throw new IllegalArgumentException("AI 服务未配置 API Key: " + serviceName);
        }
        return def;
    }
}
