package com.leoli.gateway.admin.reconcile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@link NamespacedReconcile}, the single implementation of namespace-complete
 * reconciliation shared by the route / service / auth-policy / certificate / strategy tasks.
 *
 * <p>These assertions encode the behaviour the per-task copies kept getting wrong: compare against
 * each namespace's OWN index, publish only what is genuinely absent, remove orphans per namespace,
 * never count a failed Nacos write as a repair, and never wipe live config when the DB comes back
 * empty.
 */
@ExtendWith(MockitoExtension.class)
class NamespacedReconcileTest {

    private static final Logger LOG = LoggerFactory.getLogger(NamespacedReconcileTest.class);
    private static final String PREFIX = "config.gateway.thing-";
    private static final String INDEX = "config.gateway.metadata.things-index";

    /** Stand-in entity: id, the namespace it belongs to, and whether it is enabled. */
    private record Thing(String id, String namespace, boolean enabled) {
    }

    @Mock
    private ConfigCenterService configCenter;
    @Mock
    private AlertService alertService;

    private List<Thing> db;
    private List<String> namespaces;

    @BeforeEach
    void setUp() {
        db = List.of();
        namespaces = List.of();
    }

    private NamespacedReconcile.Spec<Thing> spec() {
        return new NamespacedReconcile.Spec<>(
                "THING",
                configCenter,
                LOG,
                db,
                Thing::enabled,
                Thing::id,
                thing -> thing.namespace() == null ? List.of() : List.of(thing.namespace()),
                PREFIX,
                INDEX,
                thing -> Map.of("id", thing.id()),
                namespaces,
                alertService);
    }

    /** A spec whose entities belong in EVERY namespace, like a GLOBAL-scope strategy. */
    private NamespacedReconcile.Spec<Thing> broadcastSpec() {
        return new NamespacedReconcile.Spec<>(
                "THING", configCenter, LOG, db, Thing::enabled, Thing::id,
                thing -> (Collection<String>) namespaces,
                PREFIX, INDEX, thing -> Map.of("id", thing.id()), namespaces, alertService);
    }

    @SuppressWarnings("unchecked")
    private void stubIndex(String namespace, List<String> ids) {
        lenient().when(configCenter.getConfig(eq(INDEX), eq(namespace), any(TypeReference.class)))
                .thenReturn(ids);
    }

    private void stubExists(String namespace, Map<String, Boolean> result) {
        lenient().when(configCenter.configExistsBatch(anyList(), eq(namespace))).thenReturn(result);
    }

    @Test
    @DisplayName("an entity already present in a namespace is NOT republished (the write-storm fix)")
    void doesNotRepublishWhatAlreadyExists() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", "ns1", true));
        stubExists("ns1", Map.of(PREFIX + "T1", true));
        stubIndex("ns1", List.of("T1"));

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertTrue(result.isSuccess());
        assertEquals(0, result.getMissingRepaired(), "nothing was missing, nothing may be republished");
        verify(configCenter, never()).publishConfig(eq(PREFIX + "T1"), anyString(), any());
        // Index already matches the desired set, so it must not be rewritten either.
        verify(configCenter, never()).publishConfig(eq(INDEX), anyString(), any());
    }

    @Test
    @DisplayName("only the namespace actually missing the entity gets a publish")
    void publishesOnlyWhereMissing() {
        namespaces = List.of("ns1", "ns2");
        db = List.of(new Thing("T1", "ns1", true), new Thing("T2", "ns2", true));
        stubExists("ns1", Map.of(PREFIX + "T1", true));
        stubExists("ns2", Map.of(PREFIX + "T2", false));
        when(configCenter.publishConfig(anyString(), anyString(), any())).thenReturn(true);
        stubIndex("ns1", List.of("T1"));
        stubIndex("ns2", List.of());

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertTrue(result.isSuccess());
        assertEquals(1, result.getMissingRepaired());
        verify(configCenter).publishConfig(eq(PREFIX + "T2"), eq("ns2"), any());
        verify(configCenter, never()).publishConfig(eq(PREFIX + "T1"), anyString(), any());
    }

    @Test
    @DisplayName("a failed publish is NOT counted as a repair")
    void failedPublishIsNotCountedAsRepair() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", "ns1", true));
        stubExists("ns1", Map.of(PREFIX + "T1", false));
        when(configCenter.publishConfig(eq(PREFIX + "T1"), eq("ns1"), any())).thenReturn(false);
        stubIndex("ns1", List.of());

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        // The old per-task code discarded this boolean and reported success with a repair count,
        // so Nacos could be empty while monitoring said everything was reconciled.
        assertTrue(result.isSuccess(), "the cycle itself did not throw");
        assertEquals(0, result.getMissingRepaired(), "a rejected write must not be counted as repaired");
    }

    @Test
    @DisplayName("an indexed id absent from the DB is removed as an orphan, per namespace")
    void removesOrphanFromItsOwnNamespace() {
        namespaces = List.of("ns1", "ns2");
        db = List.of(new Thing("T1", "ns1", true));
        stubExists("ns1", Map.of(PREFIX + "T1", true));
        stubIndex("ns1", List.of("T1", "GONE"));
        stubIndex("ns2", List.of());
        when(configCenter.removeConfig(PREFIX + "GONE", "ns1")).thenReturn(true);

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertTrue(result.isSuccess());
        assertEquals(1, result.getOrphansRemoved());
        assertFalse(result.isProtectionTriggered());
        verify(configCenter).removeConfig(PREFIX + "GONE", "ns1");
        verify(configCenter, never()).removeConfig(eq(PREFIX + "T1"), anyString());
    }

    @Test
    @DisplayName("a failed orphan removal is NOT counted as removed")
    void failedRemovalIsNotCounted() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", "ns1", true));
        stubExists("ns1", Map.of(PREFIX + "T1", true));
        stubIndex("ns1", List.of("T1", "GONE"));
        when(configCenter.removeConfig(PREFIX + "GONE", "ns1")).thenReturn(false);

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertEquals(0, result.getOrphansRemoved());
    }

    @Test
    @DisplayName("empty DB triggers protection: nothing is removed and no existence probe is made")
    void emptyDbTriggersProtection() {
        namespaces = List.of("ns1");
        db = List.of();
        stubIndex("ns1", List.of("T1", "T2"));

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertTrue(result.isSuccess());
        assertTrue(result.isProtectionTriggered());
        assertEquals(2, result.getProtectedOrphanCount());
        assertEquals(0, result.getOrphansRemoved());
        verify(configCenter, never()).removeConfig(anyString(), anyString());
        verify(configCenter, never()).configExistsBatch(anyList(), anyString());
        verify(alertService).sendAlert(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("disabled entities are not desired anywhere, and are removed if still indexed")
    void disabledEntitiesAreNotPublished() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", "ns1", false));
        stubIndex("ns1", List.of("T1"));
        when(configCenter.removeConfig(PREFIX + "T1", "ns1")).thenReturn(true);

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertTrue(result.isSuccess());
        verify(configCenter, never()).configExistsBatch(anyList(), anyString());
        verify(configCenter, never()).publishConfig(eq(PREFIX + "T1"), anyString(), any());
        assertEquals(1, result.getOrphansRemoved(), "a disabled entity left in the index is an orphan");
    }

    @Test
    @DisplayName("an entity with no resolvable namespace is skipped instead of published to public")
    void unresolvedNamespaceIsSkipped() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", null, true));
        stubIndex("ns1", List.of());

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertTrue(result.isSuccess());
        assertEquals(0, result.getMissingRepaired());
        // Publishing to the public namespace is exactly the corruption this must avoid.
        verify(configCenter, never()).publishConfig(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an entity pointing at a namespace that is not a gateway namespace is skipped")
    void unknownNamespaceIsSkipped() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", "ns-that-no-longer-exists", true));
        stubIndex("ns1", List.of());

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertTrue(result.isSuccess());
        assertEquals(0, result.getMissingRepaired());
        verify(configCenter, never()).publishConfig(eq(PREFIX + "T1"), anyString(), any());
    }

    @Test
    @DisplayName("a broadcast entity is repaired in every namespace that lacks it (GLOBAL strategy scope)")
    void broadcastEntityIsRepairedEverywhere() {
        namespaces = List.of("ns1", "ns2", "ns3");
        db = List.of(new Thing("G1", null, true));
        stubExists("ns1", Map.of(PREFIX + "G1", true));
        stubExists("ns2", Map.of(PREFIX + "G1", false));
        stubExists("ns3", Map.of(PREFIX + "G1", false));
        when(configCenter.publishConfig(anyString(), anyString(), any())).thenReturn(true);
        stubIndex("ns1", List.of("G1"));
        stubIndex("ns2", List.of());
        stubIndex("ns3", List.of());

        ReconcileResult result = NamespacedReconcile.reconcile(broadcastSpec());

        assertTrue(result.isSuccess());
        assertEquals(2, result.getMissingRepaired());
        verify(configCenter, never()).publishConfig(eq(PREFIX + "G1"), eq("ns1"), any());
        verify(configCenter).publishConfig(eq(PREFIX + "G1"), eq("ns2"), any());
        verify(configCenter).publishConfig(eq(PREFIX + "G1"), eq("ns3"), any());
    }

    @Test
    @DisplayName("the index is rewritten only when it drifted from the desired set")
    void indexRewrittenOnlyOnDrift() {
        namespaces = List.of("ns1", "ns2");
        db = List.of(new Thing("T1", "ns1", true));
        stubExists("ns1", Map.of(PREFIX + "T1", true));
        stubIndex("ns1", List.of("T1"));   // already correct
        stubIndex("ns2", List.of("STALE")); // drifted
        when(configCenter.removeConfig(PREFIX + "STALE", "ns2")).thenReturn(true);

        NamespacedReconcile.reconcile(spec());

        verify(configCenter, never()).publishConfig(eq(INDEX), eq("ns1"), any());
        verify(configCenter).publishConfig(eq(INDEX), eq("ns2"), any());
    }

    @Test
    @DisplayName("an unexpected failure yields success=false rather than a silent pass")
    void failureIsReported() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", "ns1", true));
        when(configCenter.configExistsBatch(anyList(), anyString()))
                .thenThrow(new IllegalStateException("nacos unreachable"));
        stubIndex("ns1", List.of());

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("nacos unreachable"));
    }

    @Test
    @DisplayName("a namespace with nothing desired is still scanned, so orphans there get cleaned")
    void emptyDesiredNamespaceIsStillScannedForOrphans() {
        namespaces = List.of("ns1", "ns2");
        db = List.of(new Thing("T1", "ns1", true));
        stubExists("ns1", Map.of(PREFIX + "T1", true));
        stubIndex("ns1", List.of("T1"));
        stubIndex("ns2", List.of("LEFTOVER"));
        when(configCenter.removeConfig(PREFIX + "LEFTOVER", "ns2")).thenReturn(true);

        ReconcileResult result = NamespacedReconcile.reconcile(spec());

        assertEquals(1, result.getOrphansRemoved(),
            "iterating only namespaces with desired entities would leave ns2 dirty forever");
        verify(configCenter, never()).configExistsBatch(anyList(), eq("ns2"));
    }

    @Test
    @DisplayName("auxiliary hooks fire with the repaired/removed id and its namespace")
    void auxiliaryHooksAreInvoked() {
        namespaces = List.of("ns1");
        db = List.of(new Thing("T1", "ns1", true));
        stubExists("ns1", Map.of(PREFIX + "T1", false));
        stubIndex("ns1", List.of("GONE"));
        when(configCenter.publishConfig(eq(PREFIX + "T1"), eq("ns1"), any())).thenReturn(true);
        when(configCenter.removeConfig(PREFIX + "GONE", "ns1")).thenReturn(true);

        List<String> published = new java.util.ArrayList<>();
        List<String> cleaned = new java.util.ArrayList<>();
        NamespacedReconcile.Spec<Thing> withAux = new NamespacedReconcile.Spec<>(
                "THING", configCenter, LOG, db, Thing::enabled, Thing::id,
                thing -> List.of(thing.namespace()), PREFIX, INDEX,
                thing -> Map.of("id", thing.id()), namespaces, alertService,
                (id, ns) -> published.add(id + "@" + ns),
                (id, ns) -> cleaned.add(id + "@" + ns));

        ReconcileResult result = NamespacedReconcile.reconcile(withAux);

        assertTrue(result.isSuccess());
        assertEquals(List.of("T1@ns1"), published,
            "a repaired entity's companion config must be republished with it");
        assertEquals(List.of("GONE@ns1"), cleaned,
            "an orphan's companion config must be cleaned up or it leaks in Nacos forever");
    }

    @Test
    @DisplayName("a failing auxiliary hook does not abort the namespace loop or lose the primary count")
    void auxiliaryFailureIsContained() {
        namespaces = List.of("ns1", "ns2");
        db = List.of(new Thing("T1", "ns1", true), new Thing("T2", "ns2", true));
        stubExists("ns1", Map.of(PREFIX + "T1", false));
        stubExists("ns2", Map.of(PREFIX + "T2", false));
        when(configCenter.publishConfig(anyString(), anyString(), any())).thenReturn(true);
        stubIndex("ns1", List.of());
        stubIndex("ns2", List.of());

        NamespacedReconcile.Spec<Thing> withBrokenAux = new NamespacedReconcile.Spec<>(
                "THING", configCenter, LOG, db, Thing::enabled, Thing::id,
                thing -> List.of(thing.namespace()), PREFIX, INDEX,
                thing -> Map.of("id", thing.id()), namespaces, alertService,
                (id, ns) -> { throw new IllegalStateException("aux publish blew up"); },
                null);

        ReconcileResult result = NamespacedReconcile.reconcile(withBrokenAux);

        assertTrue(result.isSuccess(), "one bad companion config must not fail the whole cycle");
        assertEquals(2, result.getMissingRepaired(), "both primaries were published successfully");
    }
}
