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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for strategy configurations (rate limiter, circuit breaker, etc).
 * Ensures consistency between DB and Nacos for strategies.
 *
 * Special handling:
 * - GLOBAL scope strategies are broadcast to all gateway namespaces
 * - ROUTE scope strategies are synced to their instance namespace
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
     * The generic {@link ReconcileTask#reconcile()} template compares the DB against a single
     * flat index read from the default namespace, which cannot express "present in gateway A's
     * namespace but missing from gateway B's". Strategies are broadcast per-namespace, so this
     * override verifies every gateway namespace directly with {@code configExistsBatch} and
     * republishes only what is actually missing (no blind full republish each cycle).
     * <p>
     * Orphan removal uses each namespace's own strategies-index, with the same empty-DB
     * protection as the framework: if the DB has no strategies at all, removals are skipped so a
     * DB outage cannot wipe live gateway config.
     */
    @Override
    public ReconcileResult reconcile() {
        int repaired = 0;
        int orphansRemoved = 0;
        boolean protectionTriggered = false;
        int protectedOrphanCount = 0;
        try {
            List<StrategyEntity> allDb = strategyRepository.findAll();
            boolean dbEmpty = allDb == null || allDb.isEmpty();
            List<StrategyEntity> enabled = dbEmpty ? List.of()
                    : allDb.stream().filter(StrategyEntity::getEnabled).collect(Collectors.toList());
            List<String> namespaces = getAllGatewayNamespaces();

            Map<String, StrategyEntity> enabledById = enabled.stream()
                    .collect(Collectors.toMap(StrategyEntity::getStrategyId, e -> e, (a, b) -> a));

            // Desired strategy IDs per namespace: GLOBAL -> every namespace; ROUTE -> its instance namespace.
            Map<String, Set<String>> desiredByNs = new HashMap<>();
            for (String ns : namespaces) {
                desiredByNs.put(ns, new HashSet<>());
            }
            for (StrategyEntity e : enabled) {
                boolean isGlobal = StrategyDefinition.SCOPE_GLOBAL.equals(e.getScope()) || e.getScope() == null;
                if (isGlobal) {
                    for (String ns : namespaces) {
                        desiredByNs.get(ns).add(e.getStrategyId());
                    }
                } else {
                    String ns = getNacosNamespace(e.getInstanceId());
                    Set<String> desired = ns != null ? desiredByNs.get(ns) : null;
                    if (desired != null) {
                        desired.add(e.getStrategyId());
                    }
                }
            }

            for (String ns : namespaces) {
                Set<String> desiredIds = desiredByNs.get(ns);

                // 1) Ensure every desired strategy is physically present in this namespace.
                if (!desiredIds.isEmpty()) {
                    List<String> desiredDataIds = desiredIds.stream()
                            .map(id -> STRATEGY_PREFIX + id)
                            .collect(Collectors.toList());
                    Map<String, Boolean> exists = configCenterService.configExistsBatch(desiredDataIds, ns);
                    for (String id : desiredIds) {
                        String dataId = STRATEGY_PREFIX + id;
                        if (!Boolean.TRUE.equals(exists.get(dataId))) {
                            StrategyEntity entity = enabledById.get(id);
                            if (entity != null && configCenterService.publishConfig(dataId, ns, toDefinition(entity))) {
                                repaired++;
                                log.info("Reconcile repaired missing strategy {} in namespace {}", id, ns);
                            }
                        }
                    }
                }

                // 2) Orphan removal based on this namespace's own index.
                Set<String> indexedIds = readIndex(ns);
                if (!indexedIds.isEmpty() && dbEmpty) {
                    protectionTriggered = true;
                    protectedOrphanCount += indexedIds.size();
                    log.warn("[STRATEGY] PROTECTION: DB empty but namespace {} index has {} strategies - skipping removal",
                            ns, indexedIds.size());
                    continue;
                }
                for (String id : indexedIds) {
                    if (!desiredIds.contains(id)) {
                        if (configCenterService.removeConfig(STRATEGY_PREFIX + id, ns)) {
                            orphansRemoved++;
                            log.info("Reconcile removed orphan strategy {} from namespace {}", id, ns);
                        }
                    }
                }

                // 3) Rewrite the index only when it drifted from the desired set.
                if (!indexedIds.equals(desiredIds)) {
                    configCenterService.publishConfig(STRATEGIES_INDEX, ns, new ArrayList<>(desiredIds));
                }
            }

            return new ReconcileResult(getType(), repaired, orphansRemoved, true, protectionTriggered, protectedOrphanCount);
        } catch (Exception e) {
            log.error("[STRATEGY] Reconciliation failed", e);
            return new ReconcileResult(getType(), repaired, orphansRemoved, false, e.getMessage(), false, 0);
        }
    }

    /**
     * Read a namespace's strategies-index as a mutable set (empty on absence or error).
     */
    private Set<String> readIndex(String namespace) {
        try {
            List<String> ids = configCenterService.getConfig(STRATEGIES_INDEX, namespace,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return ids == null ? new HashSet<>() : new HashSet<>(ids);
        } catch (Exception e) {
            log.warn("Failed to read strategies index from namespace {}: {}", namespace, e.getMessage());
            return new HashSet<>();
        }
    }

    @Override
    public List<StrategyEntity> loadFromDB() {
        // Only load ENABLED strategies - disabled strategies should not be in Nacos
        return strategyRepository.findByEnabledTrue();
    }

    @Override
    public Set<String> loadFromNacos() {
        // Note: This loads from default namespace (public), which is legacy behavior
        // The actual reconciliation now uses per-instance namespace
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
        boolean isGlobal = StrategyDefinition.SCOPE_GLOBAL.equals(entity.getScope()) || entity.getScope() == null;

        if (isGlobal) {
            // Global strategies: broadcast to all gateway namespaces
            List<String> namespaces = getAllGatewayNamespaces();
            for (String namespace : namespaces) {
                configCenterService.publishConfig(strategyDataId, namespace, strategy);
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
            configCenterService.publishConfig(strategyDataId, nacosNamespace, strategy);
            log.info("Route strategy repaired: {} in namespace: {}", entity.getStrategyId(), nacosNamespace);
        }

        // Rebuild strategies index to ensure consistency
        rebuildStrategiesIndex();
    }

    @Override
    public void removeOrphanFromNacos(String strategyId) throws Exception {
        log.info("Removing orphaned strategy from Nacos: {}", strategyId);

        // Find the strategy to get scope and instanceId
        StrategyEntity strategy = strategyRepository.findByStrategyId(strategyId);
        if (strategy == null) {
            // Strategy not in DB, try to remove from all namespaces (safe cleanup)
            List<String> namespaces = getAllGatewayNamespaces();
            String strategyDataId = STRATEGY_PREFIX + strategyId;
            for (String namespace : namespaces) {
                configCenterService.removeConfig(strategyDataId, namespace);
                log.debug("Orphan strategy {} removed from namespace: {}", strategyId, namespace);
            }
            log.info("Orphan strategy removed from {} namespaces (no DB record)", namespaces.size());
        } else {
            boolean isGlobal = StrategyDefinition.SCOPE_GLOBAL.equals(strategy.getScope()) || strategy.getScope() == null;
            String strategyDataId = STRATEGY_PREFIX + strategyId;

            if (isGlobal) {
                // Remove from all namespaces
                List<String> namespaces = getAllGatewayNamespaces();
                for (String namespace : namespaces) {
                    configCenterService.removeConfig(strategyDataId, namespace);
                    log.debug("Global strategy {} removed from namespace: {}", strategyId, namespace);
                }
                log.info("Global orphan strategy removed from {} namespaces", namespaces.size());
            } else {
                // Remove from instance namespace only
                String nacosNamespace = getNacosNamespace(strategy.getInstanceId());
                if (nacosNamespace != null) {
                    configCenterService.removeConfig(strategyDataId, nacosNamespace);
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
            configCenterService.publishConfig(STRATEGIES_INDEX, namespace, new ArrayList<>(allStrategyIds));
            log.debug("Strategies index rebuilt with {} strategies in namespace {}", allStrategyIds.size(), namespace);
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