package com.leoli.gateway.admin.service;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last trace-capture configuration reported by each gateway instance, keyed by instanceId.
 *
 * <p>The console's sampling panel used to serve hardcoded numbers, because admin has no other way
 * to know what a gateway is running. Gateways now report their effective {@code gateway.trace.*}
 * values with every heartbeat, and this is where those reports land.
 *
 * <p>Deliberately in-memory rather than persisted: a gateway re-reports every 10s, so the only cost
 * of a restart is that the panel shows "not reported yet" for at most one interval. A stored column
 * would instead hold a value that is stale by design the moment a gateway changes config or dies.
 */
@Component
public class GatewayTraceConfigRegistry {

    /** A report older than this is presented as stale rather than as the current truth. */
    private static final long STALE_AFTER_MS = 60_000;

    public record Report(Map<String, Object> config, long reportedAtMillis) {
        public boolean isStale() {
            return System.currentTimeMillis() - reportedAtMillis > STALE_AFTER_MS;
        }
    }

    private final Map<String, Report> reports = new ConcurrentHashMap<>();

    /**
     * Record a report. An absent or empty config is ignored rather than stored, so a gateway that
     * predates trace-config reporting leaves any previous value alone instead of blanking it.
     */
    public void record(String instanceId, Map<String, Object> traceConfig) {
        if (instanceId == null || instanceId.isEmpty() || traceConfig == null || traceConfig.isEmpty()) {
            return;
        }
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(traceConfig));
        reports.put(instanceId, new Report(copy, System.currentTimeMillis()));
    }

    /** The most recently received report across all instances, if any gateway has reported. */
    public Optional<Report> freshest() {
        return reports.values().stream()
                .max(Comparator.comparingLong(Report::reportedAtMillis));
    }

    /** True when instances disagree, so a single displayed value cannot describe the fleet. */
    public boolean isDivergent() {
        return reports.values().stream().map(Report::config).distinct().count() > 1;
    }
}
