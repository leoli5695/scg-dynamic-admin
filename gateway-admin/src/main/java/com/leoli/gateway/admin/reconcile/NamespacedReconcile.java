package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.alert.AlertLevel;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.service.AlertService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Namespace-complete reconciliation, implemented once.
 *
 * <p><b>Why this exists.</b> The generic {@link ReconcileTask#reconcile()} template compares the DB
 * against a single flat index read from the <b>public</b> namespace, while every per-instance task
 * publishes and removes in that instance's <b>own</b> namespace. The two views never agree, so each
 * cycle concluded that every enabled entity was missing and republished all of them plus a rebuilt
 * index - a permanent Nacos write storm and a matching config-refresh storm on the gateway. Orphan
 * removal was worse than wrong: an orphan is by definition absent from the DB, so resolving its
 * namespace through a DB lookup returned null and the removal silently did nothing.
 *
 * <p>{@code StrategyReconcileTask} was fixed by overriding {@code reconcile()} with a
 * namespace-aware loop. {@code RouteReconcileTask}, {@code ServiceReconcileTask},
 * {@code AuthPolicyReconcileTask} and {@code CertificateReconcileTask} all had the identical bug
 * and none of them had been fixed - fixing them one at a time by copy-pasting that override is how
 * the next task added to this package ends up broken again. So the algorithm lives here and each
 * task describes itself with a {@link Spec}.
 *
 * <p>A static utility rather than a base class on purpose: these tasks are constructed by Lombok
 * {@code @RequiredArgsConstructor} and injected in tests with {@code @InjectMocks}, so introducing
 * an abstract parent would change every constructor for no behavioural gain.
 *
 * @author leoli
 */
public final class NamespacedReconcile {

    private NamespacedReconcile() {
    }

    /**
     * Everything the algorithm needs to know about one reconciled entity type.
     *
     * @param type            label used in logs, metrics and alerts ("ROUTE", "SERVICE", ...)
     * @param configCenter    Nacos facade
     * @param log             caller's logger, so messages are attributed to the task
     * @param allFromDb       every row, enabled or not - used only to detect an empty DB
     * @param enabled         which rows should exist in Nacos at all
     * @param idOf            business identifier, and the value stored in the index
     * @param namespacesFor   namespaces this entity must exist in. One entry for per-instance
     *                        config; every gateway namespace for a broadcast entity (strategies
     *                        with GLOBAL scope). Empty means "skip and warn".
     * @param dataIdPrefix    prefix of the per-entity config dataId
     * @param indexDataId     dataId of the namespace's id index
     * @param definitionOf    the object to publish
     * @param allNamespaces   every gateway namespace, including ones with no desired entities -
     *                        an orphan can live in a namespace that wants nothing
     * @param alertService    optional; alerted when empty-DB protection triggers
     * @param auxiliaryPublisher optional {@code (id, namespace)} hook run after a primary config is
     *                        repaired, for entities that publish a second dataId alongside the
     *                        main one (auth policies also publish their bound-route list). Null
     *                        when the entity has a single config.
     * @param auxiliaryCleaner   optional {@code (id, namespace)} hook run after an orphan's primary
     *                        config is removed, so the companion dataId does not leak in Nacos
     *                        forever. Null when the entity has a single config.
     */
    public record Spec<T>(
            String type,
            ConfigCenterService configCenter,
            Logger log,
            List<T> allFromDb,
            Predicate<T> enabled,
            Function<T, String> idOf,
            Function<T, Collection<String>> namespacesFor,
            String dataIdPrefix,
            String indexDataId,
            Function<T, Object> definitionOf,
            List<String> allNamespaces,
            AlertService alertService,
            java.util.function.BiConsumer<String, String> auxiliaryPublisher,
            java.util.function.BiConsumer<String, String> auxiliaryCleaner) {

        /** Convenience constructor for entities with a single config dataId (no auxiliaries). */
        public Spec(String type, ConfigCenterService configCenter, Logger log, List<T> allFromDb,
                    Predicate<T> enabled, Function<T, String> idOf,
                    Function<T, Collection<String>> namespacesFor, String dataIdPrefix,
                    String indexDataId, Function<T, Object> definitionOf, List<String> allNamespaces,
                    AlertService alertService) {
            this(type, configCenter, log, allFromDb, enabled, idOf, namespacesFor, dataIdPrefix,
                    indexDataId, definitionOf, allNamespaces, alertService, null, null);
        }
    }

    public static <T> ReconcileResult reconcile(Spec<T> spec) {
        int repaired = 0;
        int orphansRemoved = 0;
        boolean protectionTriggered = false;
        int protectedOrphanCount = 0;

        try {
            boolean dbEmpty = spec.allFromDb() == null || spec.allFromDb().isEmpty();
            List<T> desired = dbEmpty ? List.of()
                    : spec.allFromDb().stream().filter(spec.enabled()).toList();

            Map<String, Set<String>> desiredByNs = new HashMap<>();
            for (String ns : spec.allNamespaces()) {
                desiredByNs.put(ns, new HashSet<>());
            }
            Map<String, T> entityById = new HashMap<>();
            for (T entity : desired) {
                String id = spec.idOf().apply(entity);
                Collection<String> namespaces = spec.namespacesFor().apply(entity);
                if (namespaces == null || namespaces.isEmpty()) {
                    spec.log().warn("[{}] Skipping {} in reconcile: no resolvable namespace", spec.type(), id);
                    continue;
                }
                for (String ns : namespaces) {
                    Set<String> bucket = desiredByNs.get(ns);
                    if (bucket == null) {
                        // An entity pointing at a namespace that is no longer a gateway namespace.
                        spec.log().warn("[{}] Skipping {} in reconcile: namespace {} is not a gateway namespace",
                                spec.type(), id, ns);
                        continue;
                    }
                    bucket.add(id);
                }
                entityById.put(id, entity);
            }

            for (String ns : spec.allNamespaces()) {
                Set<String> desiredIds = desiredByNs.get(ns);

                // 1) Publish only what is physically missing from this namespace. Checking existence
                //    first is what stops the blind full republish every cycle.
                if (!desiredIds.isEmpty()) {
                    List<String> desiredDataIds = desiredIds.stream()
                            .map(id -> spec.dataIdPrefix() + id)
                            .toList();
                    Map<String, Boolean> exists =
                            spec.configCenter().configExistsBatch(desiredDataIds, ns);
                    for (String id : desiredIds) {
                        String dataId = spec.dataIdPrefix() + id;
                        if (Boolean.TRUE.equals(exists.get(dataId))) {
                            continue;
                        }
                        T entity = entityById.get(id);
                        if (entity == null) {
                            continue;
                        }
                        if (spec.configCenter().publishConfig(dataId, ns, spec.definitionOf().apply(entity))) {
                            repaired++;
                            spec.log().info("[{}] Reconcile repaired missing {} in namespace {}",
                                    spec.type(), id, ns);
                            if (spec.auxiliaryPublisher() != null) {
                                try {
                                    spec.auxiliaryPublisher().accept(id, ns);
                                } catch (RuntimeException ex) {
                                    // The primary config is already repaired and counted; a companion
                                    // dataId failure must not abort the whole namespace loop.
                                    spec.log().error("[{}] Repaired {} but its auxiliary config failed in namespace {}",
                                            spec.type(), id, ns, ex);
                                }
                            }
                        } else {
                            // Counting a failed write as a repair is how the old code reported
                            // success=true while nothing had landed in Nacos.
                            spec.log().error("[{}] Reconcile FAILED to publish {} to namespace {} - not counting it as repaired",
                                    spec.type(), id, ns);
                        }
                    }
                }

                // 2) Orphan removal against this namespace's own index, with the framework's
                //    empty-DB protection so a DB outage cannot wipe live gateway config.
                Set<String> indexedIds = readIndex(spec, ns);
                if (!indexedIds.isEmpty() && dbEmpty) {
                    protectionTriggered = true;
                    protectedOrphanCount += indexedIds.size();
                    spec.log().warn("[{}] PROTECTION: DB empty but namespace {} index has {} entries - skipping removal",
                            spec.type(), ns, indexedIds.size());
                    continue;
                }
                for (String id : indexedIds) {
                    if (desiredIds.contains(id)) {
                        continue;
                    }
                    if (spec.configCenter().removeConfig(spec.dataIdPrefix() + id, ns)) {
                        orphansRemoved++;
                        spec.log().info("[{}] Reconcile removed orphan {} from namespace {}", spec.type(), id, ns);
                        if (spec.auxiliaryCleaner() != null) {
                            try {
                                spec.auxiliaryCleaner().accept(id, ns);
                            } catch (RuntimeException ex) {
                                spec.log().error("[{}] Removed orphan {} but its auxiliary config cleanup failed in namespace {}",
                                        spec.type(), id, ns, ex);
                            }
                        }
                    } else {
                        spec.log().error("[{}] Reconcile FAILED to remove orphan {} from namespace {}",
                                spec.type(), id, ns);
                    }
                }

                // 3) Rewrite the index only when it drifted from the desired set.
                if (!indexedIds.equals(desiredIds)) {
                    if (!spec.configCenter().publishConfig(spec.indexDataId(), ns, new ArrayList<>(desiredIds))) {
                        spec.log().error("[{}] Reconcile FAILED to rewrite index in namespace {}", spec.type(), ns);
                    }
                }
            }

            if (protectionTriggered && spec.alertService() != null) {
                spec.alertService().sendAlert(
                        String.format("[%s] Reconcile Protection Triggered", spec.type()),
                        String.format(
                                "Database has no %s rows but Nacos still holds %d indexed entries.%n%n"
                                        + "Possible causes: database connection failure, a reset or migrated "
                                        + "database, or a misconfigured datasource.%n%n"
                                        + "Action: orphan removal was skipped to avoid wiping live gateway "
                                        + "config. Check database connectivity and data integrity.",
                                spec.type(), protectedOrphanCount),
                        AlertLevel.WARNING);
            }

            return new ReconcileResult(spec.type(), repaired, orphansRemoved, true,
                    protectionTriggered, protectedOrphanCount);
        } catch (Exception e) {
            spec.log().error("[{}] Reconciliation failed", spec.type(), e);
            return new ReconcileResult(spec.type(), repaired, orphansRemoved, false, e.getMessage(), false, 0);
        }
    }

    private static <T> Set<String> readIndex(Spec<T> spec, String namespace) {
        try {
            List<String> ids = spec.configCenter().getConfig(spec.indexDataId(), namespace,
                    new TypeReference<List<String>>() {});
            return ids == null ? new HashSet<>() : new HashSet<>(ids);
        } catch (Exception e) {
            spec.log().warn("[{}] Failed to read index from namespace {}: {}",
                    spec.type(), namespace, e.getMessage());
            return new HashSet<>();
        }
    }
}
