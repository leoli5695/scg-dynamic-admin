package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.PluginBindingEntity;
import com.leoli.gateway.admin.model.RouteDefinition.FilterDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Locks the invariant behind the "plugin filters multiplied on every edit" fix: a published route
 * carries each plugin filter exactly once, in global-plugin -> operator-authored -> route-plugin
 * order, no matter how many GET/PUT round trips the stored definition has been through.
 * <p>
 * The two collaborators see different binding sets on purpose: merge publishes only the ENABLED
 * bindings, while strip recognizes an echo against ALL bindings so a definition dirtied by a
 * pre-fix save is still cleaned up after its binding was disabled.
 * <p>
 * Pure Mockito - no Spring context, no DB, so unlike the {@code @SpringBootTest} suites in this
 * module it runs without a reachable MySQL. Both collaborators are private, hence
 * {@link ReflectionTestUtils}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RouteService: plugin-binding filter merge/strip invariant")
class RouteServicePluginFilterMergeTest {

    private static final String ROUTE_ID = "route-uuid-123";
    private static final String AUTH_PLUGIN = "AuthPlugin";
    private static final String RATE_LIMIT_PLUGIN = "RateLimitPlugin";
    private static final Map<String, String> AUTH_CONFIG = Map.of("secretKey", "s3cret");
    private static final Map<String, String> RATE_LIMIT_CONFIG = Map.of("replenishRate", "10");

    @Mock
    private RoutePluginBindingService routePluginBindingService;

    @InjectMocks
    private RouteService routeService;

    private final PluginBindingEntity authBinding = binding(AUTH_PLUGIN);
    private final PluginBindingEntity rateLimitBinding = binding(RATE_LIMIT_PLUGIN);

    @Test
    @DisplayName("Publishing is idempotent: repeated GET/PUT round trips do not grow the filter list")
    void publishIsIdempotentAcrossRepeatedRoundTrips() {
        stubBothBindingsEnabled();

        // convertToResponse hands the client the merged list; updateRouteByRouteId persists what
        // comes back. One full round trip therefore used to bake a second copy of every plugin
        // filter into the stored definition, and the next publish ran each plugin twice.
        List<FilterDefinition> published = merge(authored(), List.of(authBinding));
        assertEquals(canonical(), published, "first publish");

        for (int round = 1; round <= 5; round++) {
            published = merge(strip(published), List.of(authBinding));
            assertEquals(canonical(), published, "round trip " + round + " changed the filter list");
        }
    }

    @Test
    @DisplayName("Merge alone is idempotent, so republishing stored metadata cannot multiply filters")
    void mergeAloneIsIdempotent() {
        // No strip involved: this is the republish/enable path, which publishes the stored
        // definition as-is. Merging an already-merged list must be a no-op.
        stubEnabledRouteBindings(List.of(rateLimitBinding));
        stubConfigs(authBinding, rateLimitBinding);

        List<FilterDefinition> published = authored();
        for (int round = 1; round <= 3; round++) {
            published = merge(published, List.of(authBinding));
            assertEquals(canonical(), published, "merge round " + round + " changed the filter list");
        }
    }

    @Test
    @DisplayName("Strip removes the echoed plugin filters and keeps every operator-authored filter")
    void stripRemovesEchoesAndKeepsAuthoredFilters() {
        stubBothBindingsEnabled();

        List<FilterDefinition> echoed = merge(authored(), List.of(authBinding));
        assertEquals(authored(), strip(echoed));
    }

    @Test
    @DisplayName("Definitions already dirtied by pre-fix saves heal back to one copy per plugin")
    void healsDefinitionDirtiedByEarlierSaves() {
        stubBothBindingsEnabled();

        // What three pre-fix saves would have left behind: a copy of each plugin filter per save.
        List<FilterDefinition> dirtied = new ArrayList<>();
        dirtied.add(filter(AUTH_PLUGIN, AUTH_CONFIG));
        dirtied.addAll(authored());
        dirtied.add(filter(AUTH_PLUGIN, AUTH_CONFIG));
        dirtied.add(filter(AUTH_PLUGIN, AUTH_CONFIG));
        dirtied.add(filter(RATE_LIMIT_PLUGIN, RATE_LIMIT_CONFIG));
        dirtied.add(filter(RATE_LIMIT_PLUGIN, RATE_LIMIT_CONFIG));

        assertEquals(authored(), strip(dirtied), "stored definition after the next save");
        // The republish/enable paths publish stored metadata without saving, so merge must heal too.
        assertEquals(canonical(), merge(dirtied, List.of(authBinding)), "published without a save");
    }

    @Test
    @DisplayName("A dirtied echo is cleaned up even after its binding was disabled")
    void disabledBindingStillHasItsEchoStripped() {
        // strip compares against ALL bindings, so the echo is still recognized; merge compares
        // against the ENABLED ones, so the plugin is not published. One save therefore both drops
        // the stale filter and stops it running.
        stubAllBindings(List.of(authBinding), List.of());
        stubEnabledRouteBindings(List.of());
        stubConfigs(authBinding);

        List<FilterDefinition> stored = new ArrayList<>();
        stored.add(filter(AUTH_PLUGIN, AUTH_CONFIG));
        stored.add(filter("StripPrefix", Map.of("parts", "1")));

        List<FilterDefinition> saved = strip(stored);
        assertEquals(List.of(filter("StripPrefix", Map.of("parts", "1"))), saved);
        assertEquals(saved, merge(saved, List.of()), "a disabled plugin must not be republished");
    }

    @Test
    @DisplayName("Matching is by name AND args, so operator config is never silently discarded")
    void dedupesByContentNotByName() {
        stubAllBindings(List.of(authBinding), List.of());
        stubEnabledRouteBindings(List.of());
        stubConfigs(authBinding);

        // A hand-authored filter that shares the plugin's name but carries different args is not an
        // echo of the binding. Stripping by name would delete operator configuration; keeping it
        // means the plugin runs twice, which is at least visible in the published definition.
        FilterDefinition handAuthored = filter(AUTH_PLUGIN, Map.of("secretKey", "operator-own-value"));

        assertEquals(List.of(handAuthored), strip(new ArrayList<>(List.of(handAuthored))));
        assertEquals(List.of(filter(AUTH_PLUGIN, AUTH_CONFIG), handAuthored),
                merge(new ArrayList<>(List.of(handAuthored)), List.of(authBinding)));
    }

    @Test
    @DisplayName("Boundary: a stored copy survives once its binding is deleted outright")
    void storedCopySurvivesBindingDeletion() {
        // Characterization, not an endorsement. Recognition is by comparing against bindings that
        // still exist, so once a binding is DELETED there is nothing left to compare against and a
        // copy baked in by a pre-fix save keeps being published until the row is cleaned up out of
        // band. The alternative - matching on the filter name alone - would silently discard
        // operator-authored configuration, which is worse.
        stubAllBindings(List.of(), List.of());
        stubEnabledRouteBindings(List.of());

        List<FilterDefinition> stale = new ArrayList<>();
        stale.add(filter(AUTH_PLUGIN, AUTH_CONFIG));
        stale.add(filter("StripPrefix", Map.of("parts", "1")));

        assertEquals(stale, strip(stale));
        assertEquals(stale, merge(stale, List.of()));
    }

    private List<FilterDefinition> merge(List<FilterDefinition> existing, List<PluginBindingEntity> globals) {
        return ReflectionTestUtils.invokeMethod(
                routeService, "mergePluginBindingsToFilters", ROUTE_ID, existing, globals);
    }

    private List<FilterDefinition> strip(List<FilterDefinition> filters) {
        return ReflectionTestUtils.invokeMethod(
                routeService, "stripPluginBindingFilters", ROUTE_ID, filters);
    }

    /** The ordinary case: both bindings enabled, so merge and strip see the same set. */
    private void stubBothBindingsEnabled() {
        stubAllBindings(List.of(authBinding), List.of(rateLimitBinding));
        stubEnabledRouteBindings(List.of(rateLimitBinding));
        stubConfigs(authBinding, rateLimitBinding);
    }

    /** What strip sees: every binding, enabled or not. */
    private void stubAllBindings(List<PluginBindingEntity> globals, List<PluginBindingEntity> routeBindings) {
        when(routePluginBindingService.getGlobalBindings()).thenReturn(globals);
        when(routePluginBindingService.getBindingsByRouteId(ROUTE_ID)).thenReturn(routeBindings);
    }

    /** What merge sees: only enabled route bindings get published. Globals arrive as a parameter. */
    private void stubEnabledRouteBindings(List<PluginBindingEntity> routeBindings) {
        when(routePluginBindingService.getEnabledBindingsByRouteId(ROUTE_ID)).thenReturn(routeBindings);
    }

    private void stubConfigs(PluginBindingEntity... bindings) {
        for (PluginBindingEntity binding : bindings) {
            when(routePluginBindingService.parseBindingConfig(binding))
                    .thenReturn(AUTH_PLUGIN.equals(binding.getPluginName()) ? AUTH_CONFIG : RATE_LIMIT_CONFIG);
        }
    }

    /** The operator-authored filters, i.e. what a clean stored definition holds. */
    private static List<FilterDefinition> authored() {
        List<FilterDefinition> filters = new ArrayList<>();
        filters.add(filter("StripPrefix", Map.of("parts", "1")));
        filters.add(filter("AddRequestHeader", Map.of("name", "X-A", "value", "b")));
        return filters;
    }

    /** The published list: global plugins, then authored filters, then route plugins. */
    private static List<FilterDefinition> canonical() {
        List<FilterDefinition> filters = new ArrayList<>();
        filters.add(filter(AUTH_PLUGIN, AUTH_CONFIG));
        filters.addAll(authored());
        filters.add(filter(RATE_LIMIT_PLUGIN, RATE_LIMIT_CONFIG));
        return filters;
    }

    private static FilterDefinition filter(String name, Map<String, String> args) {
        return new FilterDefinition(name, new LinkedHashMap<>(args));
    }

    private static PluginBindingEntity binding(String pluginName) {
        PluginBindingEntity entity = new PluginBindingEntity();
        entity.setPluginName(pluginName);
        entity.setEnabled(true);
        entity.setOrder(0);
        return entity;
    }
}
