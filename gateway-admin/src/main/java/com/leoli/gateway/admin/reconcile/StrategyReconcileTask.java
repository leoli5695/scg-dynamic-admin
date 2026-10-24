package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for strategy configurations (rate limiter, circuit breaker, etc).
 * Ensures consistency between DB and Nacos for strategies.
 *
 * Special handling:
 * - GLOBAL scope strategies are broadcast to all gateway namespaces
 * - ROUTE scope strategies are synced to their instance namespace
 *
 * <p>Because of that broadcast scope the generic {@link ReconcileTask#reconcile()} template cannot
 * be used: a single flat index cannot express "present in gateway A's namespace but missing from
 * gateway B's". See {@link NamespacedReconcile} for the namespace-complete algorithm this task
 * delegates to.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyReconcileTask implements ReconcileTask<StrategyEntity> {

    private static final String STRATEGY_PREFIX = "config.gateway.strategy-";
    private static final String STRATEGIES_INDEX = "config.gateway.metadata.strategies-index";
    private static final String GROUP = "DEFAULT_GROUP";

    private final StrategyRepository strategyRepository;
    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final InstanceNamespaceCache namespaceCache;
    private final ConfigCenterService configCenterService;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;

    @Override
    public String getType() {
        return "STRATEGY";
    }

    /**
     * Namespace-complete reconciliation.
     * <p>
     * Strategies are the one entity type with a broadcast scope: a GLOBAL strategy (and a strategy
     * with no scope at all, which the entity treats as GLOBAL) must exist in EVERY gateway namespace,
     * while a ROUTE-scope strategy belongs only to its own instance's namespace. That is exactly what
     * the {@code namespacesFor} function below expresses, and {@link NamespacedReconcile} then does
     * what this task used to do in a 90-line inline override: probe each namespace with
     * {@code configExistsBatch} and republish only what is genuinely missing (no blind full republish
     * every cycle), remove orphans against each namespace's OWN strategies-index, and skip removal
     * entirely when the DB has no strategies at all so a DB outage cannot wipe live gateway config.
     */
    @Override
    public ReconcileResult reconcile() {
        // Resolved once: GLOBAL strategies broadcast to this very list, and the reconciler needs it
        // as the set of namespaces to scan for orphans.
        List<String> namespaces = getAllGatewayNamespaces();
        return NamespacedReconcile.reconcile(new NamespacedReconcile.Spec<>(
                getType(),
                configCenterService,
                log,
                strategyRepository.findAll(),
                strategy -> Boolean.TRUE.equals(strategy.getEnabled()),
                StrategyEntity::getStrategyId,
                // GLOBAL -> every gateway namespace; ROUTE -> its instance's namespace only. An empty
                // result makes the reconciler skip the strategy and warn instead of writing to public.
                strategy -> {
                    if (isGlobalScope(strategy.getScope())) {
                        return namespaces;
                    }
                    String ns = getNacosNamespace(strategy.getInstanceId());
                    return ns != null ? List.of(ns) : List.of();
                },
                STRATEGY_PREFIX,
                STRATEGIES_INDEX,
                this::toDefinition,
                namespaces,
                alertService));
    }

    /**
     * GLOBAL scope - or no scope at all, which {@link StrategyEntity} defaults to GLOBAL - means the
     * strategy is broadcast to every gateway namespace.
     */
    private boolean isGlobalScope(String scope) {
        return StrategyDefinition.SCOPE_GLOBAL.equals(scope) || scope == null;
    }

    @Override
    public List<StrategyEntity> loadFromDB() {
        // Only load ENABLED strategies - disabled strategies should not be in Nacos
        return strategyRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Retained for the ReconcileTask contract and for ad-hoc inspection. It reads the PUBLIC
        // namespace index, which per-namespace strategy config is never written to - that mismatch is
        // exactly what reconcile() above works around, so do not use this to decide what is missing.
        try {
            // Read as List<String> since index is stored as JSON array
            List<String> strategyIds = configCenterService.getConfig(STRATEGIES_INDEX,
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (strategyIds == null || strategyIds.isEmpty()) {
                return Set.of();
            }
            return strategyIds.stream().collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to load strategies index from Nacos", e);
            return Set.of();
        }
    }

    @Override
    public String extractId(StrategyEntity entity) {
        return entity.getStrategyId();  // Use strategyId (UUID) as business identifier
    }

    @Override
    public void repairMissingInNacos(StrategyEntity entity) throws Exception {
        // Skip disabled strategies - they should NOT be in Nacos
        if (!entity.getEnabled()) {
            log.debug("Skipping disabled strategy: {}", entity.getStrategyId());
            return;
        }

        log.info("Repairing missing strategy in Nacos: {}", entity.getStrategyId());

        // Convert entity to definition
        StrategyDefinition strategy = toDefinition(entity);

        String strategyDataId = STRATEGY_PREFIX + entity.getStrategyId();
        boolean isGlobal = isGlobalScope(entity.getScope());

        if (isGlobal) {
            // Global strategies: broadcast to all gateway namespaces. A false return used to be
            // discarded here, so the caller counted the strategy as repaired and reported success
            // while one or more namespaces had actually received nothing.
            List<String> namespaces = getAllGatewayNamespaces();
            for (String namespace : namespaces) {
                if (!configCenterService.publishConfig(strategyDataId, namespace, strategy)) {
                    throw new IllegalStateException("Failed to publish global strategy " + entity.getStrategyId()
                            + " to namespace " + namespace);
                }
                log.debug("Global strategy {} published to namespace: {}", entity.getStrategyId(), namespace);
            }
            log.info("Global strategy repaired: {} in {} namespaces", entity.getStrategyId(), namespaces.size());
        } else {
            // Route-level strategies: sync to instance namespace
            String nacosNamespace = getNacosNamespace(entity.getInstanceId());
            if (nacosNamespace == null) {
                log.warn("Skipping route strategy {} without valid instanceId, will not publish to public namespace",
                         entity.getStrategyId());
                return;
            }
            if (!configCenterService.publishConfig(strategyDataId, nacosNamespace, strategy)) {
                throw new IllegalStateException("Failed to publish strategy " + entity.getStrategyId()
                        + " to namespace " + nacosNamespace);
            }
            log.info("Route strategy repaired: {} in namespace: {}", entity.getStrategyId(), nacosNamespace);
        }

        // Rebuild strategies index to ensure consistency
        rebuildStrategiesIndex();
    }

    @Override
    public void removeOrphanFromNacos(String strategyId) throws Exception {
        log.info("Removing orphaned strategy from Nacos: {}", strategyId);

        // Find the strategy to get scope and instanceId. NOTE: a true orphan has no DB row, so this
        // lookup comes back null and there is no scope/namespace to reason with - the broadcast
        // fallback below is a best-effort sweep, and authoritative orphan removal is done per
        // namespace by reconcile(), which works from each namespace's own index instead of the DB.
        // Kept for the contract and for the row-still-exists case (a strategy that was disabled and
        // should stop being published).
        StrategyEntity strategy = strategyRepository.findByStrategyId(strategyId);
        if (strategy == null) {
            // Strategy not in DB, try to remove from all namespaces (safe cleanup)
            List<String> namespaces = getAllGatewayNamespaces();
            String strategyDataId = STRATEGY_PREFIX + strategyId;
            for (String namespace : namespaces) {
                if (!configCenterService.removeConfig(strategyDataId, namespace)) {
                    throw new IllegalStateException("Failed to remove orphan strategy " + strategyId
                            + " from namespace " + namespace);
                }
                log.debug("Orphan strategy {} removed from namespace: {}", strategyId, namespace);
            }
            log.info("Orphan strategy removed from {} namespaces (no DB record)", namespaces.size());
        } else {
            boolean isGlobal = isGlobalScope(strategy.getScope());
            String strategyDataId = STRATEGY_PREFIX + strategyId;

            if (isGlobal) {
                // Remove from all namespaces
                List<String> namespaces = getAllGatewayNamespaces();
                for (String namespace : namespaces) {
                    if (!configCenterService.removeConfig(strategyDataId, namespace)) {
                        throw new IllegalStateException("Failed to remove global strategy " + strategyId
                                + " from namespace " + namespace);
                    }
                    log.debug("Global strategy {} removed from namespace: {}", strategyId, namespace);
                }
                log.info("Global orphan strategy removed from {} namespaces", namespaces.size());
            } else {
                // Remove from instance namespace only
                String nacosNamespace = getNacosNamespace(strategy.getInstanceId());
                if (nacosNamespace != null) {
                    if (!configCenterService.removeConfig(strategyDataId, nacosNamespace)) {
                        throw new IllegalStateException("Failed to remove strategy " + strategyId
                                + " from namespace " + nacosNamespace);
                    }
                    log.info("Route orphan strategy removed: {} from namespace: {}", strategyId, nacosNamespace);
                }
            }
        }

        // Rebuild strategies index after removal
        rebuildStrategiesIndex();
    }

    /**
     * Get nacosNamespace from instanceId (uses cache).
     */
    private String getNacosNamespace(String instanceId) {
        return namespaceCache.getNamespace(instanceId);
    }

    /**
     * Get all registered gateway instance namespaces.
     * Used for broadcasting global strategies.
     */
    private List<String> getAllGatewayNamespaces() {
        return gatewayInstanceRepository.findAll().stream()
                .map(i -> i.getNacosNamespace())
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Rebuild strategies index for all namespaces.
     * Only includes ENABLED strategies.
     */
    private void rebuildStrategiesIndex() throws Exception {
        List<String> namespaces = getAllGatewayNamespaces();

        for (String namespace : namespaces) {
            // Find the instanceId for this namespace
            String instanceId = gatewayInstanceRepository.findAll().stream()
                    .filter(i -> namespace.equals(i.getNacosNamespace()))
                    .findFirst()
                    .map(i -> i.getInstanceId())
                    .orElse(null);

            if (instanceId == null) {
                continue;
            }

            // Get enabled strategies for this instance (both GLOBAL and ROUTE scope)
            List<String> strategyIds = strategyRepository.findByInstanceIdAndEnabledTrue(instanceId).stream()
                    .map(StrategyEntity::getStrategyId)
                    .collect(Collectors.toList());

            // Also include global strategies that should be in every namespace
            List<String> globalStrategyIds = strategyRepository.findByScopeAndEnabledTrue(StrategyDefinition.SCOPE_GLOBAL).stream()
                    .map(StrategyEntity::getStrategyId)
                    .collect(Collectors.toList());

            // Merge and deduplicate (mutable set - Set.copyOf returns an immutable set that
            // would throw UnsupportedOperationException on addAll).
            Set<String> allStrategyIds = new HashSet<>(strategyIds);
            allStrategyIds.addAll(globalStrategyIds);

            // Publish as JSON array to instance namespace
            if (!configCenterService.publishConfig(STRATEGIES_INDEX, namespace, new ArrayList<>(allStrategyIds))) {
                log.error("Failed to rebuild strategies index in namespace {}", namespace);
            } else {
                log.debug("Strategies index rebuilt with {} strategies in namespace {}", allStrategyIds.size(), namespace);
            }
        }
    }

    /**
     * Convert entity to definition.
     */
    private StrategyDefinition toDefinition(StrategyEntity entity) {
        if (entity == null) {
            return null;
        }

        // Try to parse from metadata JSON first
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            try {
                StrategyDefinition strategy = objectMapper.readValue(entity.getMetadata(), StrategyDefinition.class);
                strategy.setStrategyId(entity.getStrategyId());
                strategy.setStrategyName(entity.getStrategyName());
                strategy.setStrategyType(entity.getStrategyType());
                strategy.setScope(entity.getScope());
                strategy.setRouteId(entity.getRouteId());
                strategy.setPriority(entity.getPriority());
                strategy.setEnabled(entity.getEnabled());
                return strategy;
            } catch (Exception e) {
                log.warn("Failed to parse strategy metadata JSON: {}", e.getMessage());
            }
        }

        // Fallback: create from entity fields
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setStrategyId(entity.getStrategyId());
        strategy.setStrategyName(entity.getStrategyName());
        strategy.setStrategyType(entity.getStrategyType());
        strategy.setScope(entity.getScope());
        strategy.setRouteId(entity.getRouteId());
        strategy.setPriority(entity.getPriority());
        strategy.setEnabled(entity.getEnabled());
        return strategy;
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}