package com.leoli.gateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigContentTrackerTest {

    private final ConfigContentTracker tracker = new ConfigContentTracker();

    @Test
    @DisplayName("first sighting of a data ID counts as changed so it gets applied")
    void firstSightingIsAChange() {
        assertTrue(tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}"));
    }

    @Test
    @DisplayName("identical content is not reported as changed, so a reconcile cycle is a no-op")
    void identicalContentIsNotAChange() {
        tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}");
        assertFalse(tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}"));
    }

    @Test
    @DisplayName("edited content is reported as changed")
    void editedContentIsAChange() {
        tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}");
        assertTrue(tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":2}"));
    }

    @Test
    @DisplayName("a delete then re-create with the same content is still a change")
    void forgetMakesRecreateVisible() {
        tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}");
        tracker.forget("config.gateway.strategy-s1");
        assertTrue(tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}"));
    }

    @Test
    @DisplayName("null content is tracked as empty rather than rejected")
    void nullContentIsNormalised() {
        assertTrue(tracker.recordIfChanged("config.gateway.strategy-s1", null));
        assertFalse(tracker.recordIfChanged("config.gateway.strategy-s1", ""));
    }

    @Test
    @DisplayName("data IDs are tracked independently")
    void tracksPerDataId() {
        tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}");
        assertTrue(tracker.recordIfChanged("config.gateway.strategy-s2", "{\"a\":1}"));
    }

    @Test
    @DisplayName("clear drops the whole baseline so everything is reapplied")
    void clearResetsBaseline() {
        tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}");
        tracker.clear();
        assertTrue(tracker.recordIfChanged("config.gateway.strategy-s1", "{\"a\":1}"));
    }
}
