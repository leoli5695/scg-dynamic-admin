package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Filter-level Circuit Breaker Manager.
 * 
 * Manages circuit breaker state for each individual filter, enabling
 * automatic isolation of problematic filters based on health score.
 * 
 * States:
 * - CLOSED: Normal operation, filter executes normally
 * - OPEN: Circuit broken, filter is skipped (bypassed)
 * - HALF_OPEN: Testing state, limited requests pass through to check recovery
 * 
 * @author leoli
 */
@Slf4j
@Component
public class FilterCircuitBreakerManager {

    private final ConcurrentHashMap<String, FilterCircuitBreakerState> states = new ConcurrentHashMap<>();
    private final List<FilterCircuitBreakerEvent> eventHistory = new ArrayList<>();
    private final int maxHistorySize = 1000;

    // Default configuration
    private volatile int healthScoreThreshold = 60;      // Health score threshold to trigger OPEN
    private volatile double failureRateThreshold = 50.0; // Failure rate threshold (%)
    private volatile long waitDurationMs = 30000;         // OPEN -> HALF_OPEN wait time (30s)
    private volatile int halfOpenRequestCount = 5;       // Requests allowed in HALF_OPEN state
    private volatile boolean enabled = true;              // Global enable/disable

    /**
     * Circuit breaker states.
     */
    public enum CircuitState {
        CLOSED,     // Normal operation
        OPEN,       // Circuit broken, filter skipped
        HALF_OPEN   // Testing recovery
    }

    /**
     * Check if a filter should be skipped (circuit broken).
     */
    public boolean shouldSkipFilter(String filterName) {
        if (!enabled) {
            return false;
        }

        FilterCircuitBreakerState state = states.get(filterName);
        if (state == null) {
            return false;
        }

        CircuitState currentState = state.getState();

        if (currentState == CircuitState.OPEN) {
            log.warn("Filter {} is OPEN (circuit-breaked), skipping execution", filterName);
            return true;
        }

        if (currentState == CircuitState.HALF_OPEN) {
            // In HALF_OPEN, allow limited requests
            int passed = state.getHalfOpenPassed().incrementAndGet();
            if (passed <= halfOpenRequestCount) {
                log.info("Filter {} in HALF_OPEN, allowing request {} of {}",
                        filterName, passed, halfOpenRequestCount);
                return false;
            } else {
                log.warn("Filter {} in HALF_OPEN, max requests reached, skipping", filterName);
                return true;
            }
        }

        return false;
    }

    /**
     * Record execution result for a filter (used in HALF_OPEN state).
     */
    public void recordExecutionResult(String filterName, boolean success) {
        FilterCircuitBreakerState state = states.get(filterName);
        if (state == null || state.getState() != CircuitState.HALF_OPEN) {
            return;
        }

        if (success) {
            int successCount = state.getHalfOpenSuccess().incrementAndGet();
            log.info("Filter {} HALF_OPEN success recorded: {}", filterName, successCount);

            // Check if recovery threshold reached (80% success in half-open requests)
            int passed = state.getHalfOpenPassed().get();
            if (passed >= halfOpenRequestCount && successCount >= halfOpenRequestCount * 0.8) {
                transitionToClosed(filterName, "Recovery successful in HALF_OPEN");
            }
        } else {
            int failureCount = state.getHalfOpenFailure().incrementAndGet();
            log.warn("Filter {} HALF_OPEN failure recorded: {}", filterName, failureCount);

            // Immediate return to OPEN on failure in HALF_OPEN
            if (failureCount >= 1) {
                transitionToOpen(filterName, state.getLastHealthScore(),
                        state.getLastFailureRate(), "Failure detected in HALF_OPEN");
            }
        }
    }

    /**
     * Transition to OPEN state (circuit broken).
     */
    public void transitionToOpen(String filterName, int healthScore, double failureRate, String reason) {
        FilterCircuitBreakerState state = states.computeIfAbsent(filterName,
                k -> new FilterCircuitBreakerState(k));

        CircuitState previousState = state.getState();
        state.setState(CircuitState.OPEN);
        state.setOpenSince(System.currentTimeMillis());
        state.setLastHealthScore(healthScore);
        state.setLastFailureRate(failureRate);
        state.resetHalfOpenCounters();

        log.warn("Filter {} circuit OPENED: healthScore={}, failureRate={}, reason={}",
                filterName, healthScore, failureRate, reason);

        // Record event
        recordEvent(filterName, CircuitState.OPEN, previousState, reason, healthScore, failureRate);
    }

    /**
     * Transition to CLOSED state (normal operation).
     */
    public void transitionToClosed(String filterName, String reason) {
        FilterCircuitBreakerState state = states.get(filterName);
        if (state == null) {
            return;
        }

        CircuitState previousState = state.getState();
        state.setState(CircuitState.CLOSED);
        state.setOpenSince(0);
        state.resetHalfOpenCounters();
        state.incrementRecoveryCount();

        log.info("Filter {} circuit CLOSED (recovered): reason={}", filterName, reason);

        // Record event
        recordEvent(filterName, CircuitState.CLOSED, previousState, reason,
                state.getLastHealthScore(), state.getLastFailureRate());
    }

    /**
     * Transition to HALF_OPEN state (testing recovery).
     */
    public void transitionToHalfOpen(String filterName) {
        FilterCircuitBreakerState state = states.get(filterName);
        if (state == null) {
            return;
        }

        if (state.getState() != CircuitState.OPEN) {
            return;
        }

        long elapsed = System.currentTimeMillis() - state.getOpenSince();
        if (elapsed < waitDurationMs) {
            return; // Not enough time elapsed
        }

        CircuitState previousState = state.getState();
        state.setState(CircuitState.HALF_OPEN);
        state.resetHalfOpenCounters();
        state.incrementRecoveryAttempts();

        log.info("Filter {} circuit HALF_OPEN: elapsed={}ms, recoveryAttempt={}",
                filterName, elapsed, state.getRecoveryAttempts());

        // Record event
        recordEvent(filterName, CircuitState.HALF_OPEN, previousState, "Automatic transition after waitDuration",
                state.getLastHealthScore(), state.getLastFailureRate());
    }

    /**
     * Force open a filter circuit (manual operation).
     */
    public void forceOpen(String filterName, String operator) {
        FilterCircuitBreakerState state = states.computeIfAbsent(filterName,
                k -> new FilterCircuitBreakerState(k));

        CircuitState previousState = state.getState();
        state.setState(CircuitState.OPEN);
        state.setOpenSince(System.currentTimeMillis());
        state.resetHalfOpenCounters();

        log.warn("Filter {} circuit FORCE OPENED by {}", filterName, operator);

        recordEvent(filterName, CircuitState.OPEN, previousState,
                "Manual force open by " + operator, 0, 0);
    }

    /**
     * Force close a filter circuit (manual operation).
     */
    public void forceClose(String filterName, String operator) {
        FilterCircuitBreakerState state = states.get(filterName);
        if (state == null) {
            states.put(filterName, new FilterCircuitBreakerState(filterName));
            return;
        }

        CircuitState previousState = state.getState();
        state.setState(CircuitState.CLOSED);
        state.setOpenSince(0);
        state.resetHalfOpenCounters();

        log.info("Filter {} circuit FORCE CLOSED by {}", filterName, operator);

        recordEvent(filterName, CircuitState.CLOSED, previousState,
                "Manual force close by " + operator, 0, 0);
    }

    /**
     * Check all OPEN states for HALF_OPEN transition.
     */
    public void checkHalfOpenTransitions() {
        for (Map.Entry<String, FilterCircuitBreakerState> entry : states.entrySet()) {
            String filterName = entry.getKey();
            FilterCircuitBreakerState state = entry.getValue();

            if (state.getState() == CircuitState.OPEN) {
                long elapsed = System.currentTimeMillis() - state.getOpenSince();
                if (elapsed >= waitDurationMs) {
                    transitionToHalfOpen(filterName);
                }
            }
        }
    }

    /**
     * Get all filter circuit breaker states.
     */
    public Map<String, FilterCircuitBreakerState> getAllStates() {
        return new HashMap<>(states);
    }

    /**
     * Get state for a specific filter.
     */
    public FilterCircuitBreakerState getState(String filterName) {
        return states.get(filterName);
    }

    /**
     * Get event history.
     */
    public List<FilterCircuitBreakerEvent> getEventHistory(int limit) {
        int fromIndex = Math.max(0, eventHistory.size() - limit);
        return new ArrayList<>(eventHistory.subList(fromIndex, eventHistory.size()));
    }

    /**
     * Update configuration.
     */
    public void updateConfig(int healthScoreThreshold, double failureRateThreshold,
                             long waitDurationMs, int halfOpenRequestCount, boolean enabled) {
        this.healthScoreThreshold = healthScoreThreshold;
        this.failureRateThreshold = failureRateThreshold;
        this.waitDurationMs = waitDurationMs;
        this.halfOpenRequestCount = halfOpenRequestCount;
        this.enabled = enabled;

        log.info("Circuit breaker config updated: healthThreshold={}, failureThreshold={}, waitMs={}, halfOpenCount={}, enabled={}",
                healthScoreThreshold, failureRateThreshold, waitDurationMs, halfOpenRequestCount, enabled);
    }

    /**
     * Get current configuration.
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("healthScoreThreshold", healthScoreThreshold);
        config.put("failureRateThreshold", failureRateThreshold);
        config.put("waitDurationMs", waitDurationMs);
        config.put("halfOpenRequestCount", halfOpenRequestCount);
        config.put("enabled", enabled);
        return config;
    }

    /**
     * Get threshold values.
     */
    public int getHealthScoreThreshold() {
        return healthScoreThreshold;
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    /**
     * Record a circuit breaker event.
     */
    private void recordEvent(String filterName, CircuitState newState, CircuitState previousState,
                             String reason, int healthScore, double failureRate) {
        FilterCircuitBreakerEvent event = new FilterCircuitBreakerEvent(
                filterName, newState, previousState, reason, healthScore, failureRate,
                System.currentTimeMillis(), "automatic");

        eventHistory.add(event);

        // Trim history if too large
        if (eventHistory.size() > maxHistorySize) {
            eventHistory.remove(0);
        }
    }

    /**
     * Filter circuit breaker state.
     */
    public static class FilterCircuitBreakerState {
        private final String filterName;
        private volatile CircuitState state = CircuitState.CLOSED;
        private volatile long openSince = 0;
        private volatile int lastHealthScore = 100;
        private volatile double lastFailureRate = 0.0;
        private volatile int recoveryAttempts = 0;
        private volatile int recoveryCount = 0;

        // HALF_OPEN counters
        private final AtomicInteger halfOpenPassed = new AtomicInteger(0);
        private final AtomicInteger halfOpenSuccess = new AtomicInteger(0);
        private final AtomicInteger halfOpenFailure = new AtomicInteger(0);

        public FilterCircuitBreakerState(String filterName) {
            this.filterName = filterName;
        }

        public void resetHalfOpenCounters() {
            halfOpenPassed.set(0);
            halfOpenSuccess.set(0);
            halfOpenFailure.set(0);
        }

        public void incrementRecoveryAttempts() {
            recoveryAttempts++;
        }

        public void incrementRecoveryCount() {
            recoveryCount++;
        }

        public String getFilterName() { return filterName; }
        public CircuitState getState() { return state; }
        public void setState(CircuitState state) { this.state = state; }
        public long getOpenSince() { return openSince; }
        public void setOpenSince(long openSince) { this.openSince = openSince; }
        public int getLastHealthScore() { return lastHealthScore; }
        public void setLastHealthScore(int lastHealthScore) { this.lastHealthScore = lastHealthScore; }
        public double getLastFailureRate() { return lastFailureRate; }
        public void setLastFailureRate(double lastFailureRate) { this.lastFailureRate = lastFailureRate; }
        public int getRecoveryAttempts() { return recoveryAttempts; }
        public int getRecoveryCount() { return recoveryCount; }
        public AtomicInteger getHalfOpenPassed() { return halfOpenPassed; }
        public AtomicInteger getHalfOpenSuccess() { return halfOpenSuccess; }
        public AtomicInteger getHalfOpenFailure() { return halfOpenFailure; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("state", state.name());
            map.put("openSince", openSince);
            map.put("lastHealthScore", lastHealthScore);
            map.put("lastFailureRate", lastFailureRate);
            map.put("recoveryAttempts", recoveryAttempts);
            map.put("recoveryCount", recoveryCount);
            map.put("halfOpenPassed", halfOpenPassed.get());
            map.put("halfOpenSuccess", halfOpenSuccess.get());
            map.put("halfOpenFailure", halfOpenFailure.get());
            map.put("openDurationMs", state == CircuitState.OPEN ? System.currentTimeMillis() - openSince : 0);
            return map;
        }
    }

    /**
     * Circuit breaker event record.
     */
    public static class FilterCircuitBreakerEvent {
        private final String filterName;
        private final CircuitState newState;
        private final CircuitState previousState;
        private final String reason;
        private final int healthScore;
        private final double failureRate;
        private final long timestamp;
        private final String operator;

        public FilterCircuitBreakerEvent(String filterName, CircuitState newState, CircuitState previousState,
                                          String reason, int healthScore, double failureRate,
                                          long timestamp, String operator) {
            this.filterName = filterName;
            this.newState = newState;
            this.previousState = previousState;
            this.reason = reason;
            this.healthScore = healthScore;
            this.failureRate = failureRate;
            this.timestamp = timestamp;
            this.operator = operator;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("newState", newState.name());
            map.put("previousState", previousState.name());
            map.put("reason", reason);
            map.put("healthScore", healthScore);
            map.put("failureRate", failureRate);
            map.put("timestamp", timestamp);
            map.put("operator", operator);
            return map;
        }

        public String getFilterName() { return filterName; }
        public CircuitState getNewState() { return newState; }
        public CircuitState getPreviousState() { return previousState; }
        public String getReason() { return reason; }
        public int getHealthScore() { return healthScore; }
        public double getFailureRate() { return failureRate; }
        public long getTimestamp() { return timestamp; }
        public String getOperator() { return operator; }
    }
}