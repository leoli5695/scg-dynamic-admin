package com.leoli.gateway.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Remembers which config content was last applied for each data ID, so a periodic
 * reconcile can tell "unchanged" from "edited" instead of re-applying everything.
 *
 * <p>Why this exists: nacos-client 3.0.3 against nacos-server 2.5.4 never receives
 * config pushes (measured on a live deployment - the server's {@code remote-push.log}
 * stays empty while {@code config-trace.log} shows the publish and dump succeeding),
 * so listener callbacks alone leave every console edit invisible until the gateway
 * restarts. The scheduled reconcile that stands in for push has to be content-aware:
 * comparing only the index ID set recovers creates and deletes but silently misses
 * edits, which are the most common operation of all.
 *
 * <p>Callers must record at every apply site (initial load as well as change
 * handling) so the first reconcile after startup sees a complete baseline and does
 * not re-apply configs that are already in effect.
 *
 * @author leoli
 */
public class ConfigContentTracker {

    private final ConcurrentMap<String, String> lastApplied = new ConcurrentHashMap<>();

    /**
     * Records {@code content} as the applied state for {@code dataId} and reports
     * whether it differs from what was recorded before. An untracked data ID counts
     * as changed, which is what makes a config that appeared while the gateway was
     * not looking get applied on the next pass.
     *
     * <p>Null content is normalised to the empty string because {@link ConcurrentHashMap}
     * rejects null values; callers treat a blank config as "deleted" before getting here.
     */
    public boolean recordIfChanged(String dataId, String content) {
        String normalized = content == null ? "" : content;
        return !normalized.equals(lastApplied.put(dataId, normalized));
    }

    /**
     * Drops the record for a config that no longer exists, so a later re-create with
     * identical content is still recognised as a change.
     */
    public void forget(String dataId) {
        lastApplied.remove(dataId);
    }

    /** Drops every record, for the reload paths that discard all applied state at once. */
    public void clear() {
        lastApplied.clear();
    }
}
