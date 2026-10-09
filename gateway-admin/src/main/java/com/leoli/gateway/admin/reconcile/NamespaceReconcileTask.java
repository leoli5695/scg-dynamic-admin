package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.alert.AlertLevel;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.service.AlertService;
import com.leoli.gateway.admin.service.InstanceConfigCleanupHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation task for per-instance Nacos namespaces.
 * <p>
 * Each gateway instance owns a {@code gateway-<instanceId>} namespace. {@code deleteInstance}
 * removes it, but namespace deletion is a non-transactional HTTP side effect that only logs on
 * failure - so a transient Nacos outage during a delete (or a pre-fix historical instance, a direct
 * DB deletion, or a crash between namespace creation and commit) can leave an orphan namespace that
 * nothing ever retries. Left alone these accumulate without bound (a local Nacos once reached 71
 * namespaces for a single live instance).
 * <p>
 * This task self-heals them: any {@code gateway-*} namespace not owned by a live instance is an
 * orphan and is removed on the shared reconcile cadence (see {@code ReconcileScheduler}).
 * <p>
 * Safety:
 * <ul>
 *   <li>Only {@code gateway-*} namespaces are ever considered; {@code public} and any other
 *       namespace are invisible here (prefix filter in {@code listGatewayNamespaces}, re-checked in
 *       {@link #removeOrphanFromNacos}).</li>
 *   <li>Empty-DB protection: with zero live instances the keep-set is empty, which would mark every
 *       gateway namespace an orphan - so removal is skipped and an alert raised instead. A DB outage
 *       can never wipe all namespaces.</li>
 *   <li>A namespace listing failure yields an empty set, so zero orphans are detected and nothing is
 *       deleted (fail-closed).</li>
 *   <li>{@code gateway.nacos.orphan-cleanup.enabled} (default true) and {@code .dry-run}
 *       (default false) give operators an off-switch and an observe-only mode.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NamespaceReconcileTask implements ReconcileTask<GatewayInstanceEntity> {

    private static final String GATEWAY_NS_PREFIX = "gateway-";

    private final GatewayInstanceRepository gatewayInstanceRepository;
    private final InstanceConfigCleanupHelper configCleanupHelper;
    private final AlertService alertService;

    @Value("${gateway.nacos.orphan-cleanup.enabled:true}")
    private boolean enabled;

    @Value("${gateway.nacos.orphan-cleanup.dry-run:false}")
    private boolean dryRun;

    /**
     * Comma-separated namespace names that are never treated as orphans, even with no matching
     * live instance. Defaults to {@code gateway-test} - the fixed namespace the integration-test
     * profile (application-test.yml) publishes into, which has no gateway_instances row and would
     * otherwise be deleted on the first reconcile.
     */
    @Value("${gateway.nacos.orphan-cleanup.preserve:gateway-test}")
    private String preserveList;

    @Override
    public String getType() {
        return "NAMESPACE";
    }

    /**
     * Namespace reconciliation is orphan-removal only: the generic template's "repair missing"
     * direction does not apply (re-creating an empty namespace restores none of its configs), and
     * running it would report a misleading "+N repaired". Protection mirrors the framework: an
     * empty keep-set refuses to delete so a DB outage cannot wipe every namespace.
     */
    @Override
    public ReconcileResult reconcile() {
        if (!enabled) {
            return new ReconcileResult(getType(), 0, 0, true);
        }
        int orphansRemoved = 0;
        try {
            List<GatewayInstanceEntity> instances = loadFromDB();
            Set<String> nacosNamespaces = loadFromNacos();
            Set<String> live = instances.stream().map(this::extractId).collect(Collectors.toSet());
            Set<String> preserved = preservedNamespaces();

            List<String> orphans = nacosNamespaces.stream()
                    .filter(ns -> !live.contains(ns))
                    .filter(ns -> !preserved.contains(ns))
                    .sorted()
                    .collect(Collectors.toList());

            // Protection: no live instances -> empty keep-set -> every gateway-* namespace would look
            // like an orphan. Refuse to delete and alert instead (mirrors ReconcileTask's empty-DB guard).
            if (instances.isEmpty() && !orphans.isEmpty()) {
                log.warn("[NAMESPACE] PROTECTION: no live instances in DB but {} gateway-* namespace(s) exist in "
                        + "Nacos - skipping orphan removal to avoid mass deletion. Check DB connectivity.", orphans.size());
                AlertService alerts = getAlertService();
                if (alerts != null) {
                    alerts.sendAlert("[NAMESPACE] Reconcile Protection Triggered",
                            String.format("Database has no gateway instances but Nacos has %d gateway-* namespace(s). "
                                            + "Orphan namespace removal was skipped to prevent mass deletion. "
                                            + "Please verify database connectivity.", orphans.size()),
                            AlertLevel.WARNING);
                }
                return new ReconcileResult(getType(), 0, 0, true, true, orphans.size());
            }

            // Live instances whose namespace vanished from Nacos: surface for the operator, do not
            // auto-recreate (an empty namespace restores no configs and would race an in-flight delete).
            for (GatewayInstanceEntity instance : instances) {
                String ns = extractId(instance);
                if (ns.startsWith(GATEWAY_NS_PREFIX) && !nacosNamespaces.contains(ns)) {
                    repairMissingInNacos(instance);
                }
            }

            for (String orphan : orphans) {
                removeOrphanFromNacos(orphan);
                if (!dryRun) {
                    orphansRemoved++;
                }
            }
            if (!orphans.isEmpty()) {
                log.info("[NAMESPACE] Reconcile: {} gateway-* namespace(s) in Nacos, {} live, {} orphan(s){}",
                        nacosNamespaces.size(), live.size(), orphans.size(), dryRun ? " (dry-run: none deleted)" : "");
            }
            return new ReconcileResult(getType(), 0, orphansRemoved, true);
        } catch (Exception e) {
            log.error("[NAMESPACE] Reconciliation failed", e);
            return new ReconcileResult(getType(), 0, orphansRemoved, false, e.getMessage(), false, 0);
        }
    }

    @Override
    public List<GatewayInstanceEntity> loadFromDB() {
        return gatewayInstanceRepository.findAll();
    }

    @Override
    public Set<String> loadFromNacos() {
        return configCleanupHelper.listGatewayNamespaces();
    }

    @Override
    public String extractId(GatewayInstanceEntity instance) {
        String namespace = instance.getNacosNamespace();
        if (namespace == null || namespace.isBlank()) {
            namespace = GATEWAY_NS_PREFIX + instance.getInstanceId();
        }
        return namespace.trim();
    }

    @Override
    public void repairMissingInNacos(GatewayInstanceEntity instance) {
        log.warn("[NAMESPACE] Live instance {} ({}) expects namespace '{}' but it is missing in Nacos; "
                        + "not auto-recreating (its configs would still be absent). Redeploy the instance if it should be live.",
                instance.getInstanceId(), instance.getInstanceName(), extractId(instance));
    }

    @Override
    public void removeOrphanFromNacos(String namespaceId) {
        // Defense in depth: never delete anything that is not a gateway-* namespace, even though
        // loadFromNacos already filters by prefix.
        if (namespaceId == null || !namespaceId.startsWith(GATEWAY_NS_PREFIX)) {
            log.warn("[NAMESPACE] Refusing to remove non-gateway namespace: {}", namespaceId);
            return;
        }
        // Defense in depth: never delete a preserved namespace (reconcile() already excludes them).
        if (preservedNamespaces().contains(namespaceId)) {
            log.info("[NAMESPACE] Skipping preserved namespace '{}'", namespaceId);
            return;
        }
        if (dryRun) {
            log.warn("[NAMESPACE] DRY-RUN: would remove orphan namespace '{}' (no live instance)", namespaceId);
            return;
        }
        log.info("[NAMESPACE] Removing orphan namespace '{}' (no live instance)", namespaceId);
        configCleanupHelper.deleteNacosNamespace(namespaceId);
    }

    /**
     * Parse the comma-separated preserve list into a trimmed set (empty when unset).
     */
    private Set<String> preservedNamespaces() {
        if (preserveList == null || preserveList.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(preserveList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}
