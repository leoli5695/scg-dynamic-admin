package com.leoli.gateway.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe sliding time window rate limiter with burst support.
 * <p>
 * Uses CAS for low contention, tryLock fallback for high contention.
 * This hybrid approach avoids blocking EventLoop threads in reactive context.
 * <p>
 * Features:
 * - Sliding window rate limiting
 * - Burst capacity for handling traffic spikes
 * - Non-blocking implementation suitable for reactive environments
 * - Automatic window reset with CAS-based coordination
 *
 * @author leoli
 */
@Data
@Slf4j
public class RateLimiterWindow {

    private final int maxRequests;
    private final int burstCapacity;
    private final long windowSizeMs;
    private final AtomicInteger currentCount = new AtomicInteger(0);
    private final AtomicInteger burstTokens = new AtomicInteger(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Create a rate limiter with default burst capacity (2x maxRequests).
     *
     * @param maxRequests   Maximum requests allowed per window
     * @param windowSizeMs  Window size in milliseconds
     */
    public RateLimiterWindow(int maxRequests, long windowSizeMs) {
        this(maxRequests, maxRequests * 2, windowSizeMs);
    }

    /**
     * Create a rate limiter with custom burst capacity.
     *
     * @param maxRequests   Maximum requests allowed per window (steady state rate)
     * @param burstCapacity Maximum burst capacity
     * @param windowSizeMs  Window size in milliseconds
     */
    public RateLimiterWindow(int maxRequests, int burstCapacity, long windowSizeMs) {
        this.maxRequests = maxRequests;
        this.burstCapacity = Math.max(burstCapacity, maxRequests);
        this.windowSizeMs = windowSizeMs;
        this.burstTokens.set(this.burstCapacity);
    }

    /**
     * Try to acquire a permit from the rate limiter.
     * <p>
     * Algorithm:
     * 1. Check if window needs reset (CAS-based, only one thread performs reset)
     * 2. Fast path: try CAS for low contention scenarios
     * 3. Slow path: tryLock for high contention (never blocks!)
     * 4. If tryLock fails, immediately reject to prevent thread starvation
     *
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long windowStart = windowStartTime.get();

        // Try to reset window if expired (CAS ensures only one thread performs reset)
        if (now - windowStart >= windowSizeMs) {
            if (windowStartTime.compareAndSet(windowStart, now)) {
                // This thread won the CAS - responsible for resetting counters
                currentCount.set(0);
                int prevBurst = burstTokens.get();
                int newBurstTokens = Math.min(burstCapacity, maxRequests + prevBurst);
                burstTokens.set(newBurstTokens);
                if (log.isDebugEnabled()) {
                    log.debug("Window reset: burstTokens restored from {} to {}", prevBurst, newBurstTokens);
                }
            }
            // Continue with acquire logic after potential reset
        }

        // Fast path: try CAS for low contention (optimistic)
        int count = currentCount.get();
        if (count < maxRequests) {
            if (currentCount.compareAndSet(count, count + 1)) {
                return true;
            }
            // CAS failed due to contention - fall through to slow path
        } else {
            // Steady rate exhausted, try burst tokens with CAS
            int tokens = burstTokens.get();
            if (tokens > 0) {
                if (burstTokens.compareAndSet(tokens, tokens - 1)) {
                    currentCount.incrementAndGet();
                    return true;
                }
                // CAS failed - fall through to slow path
            } else {
                // No burst tokens available
                return false;
            }
        }

        // Slow path: tryLock for high contention (never blocks!)
        if (lock.tryLock()) {
            try {
                count = currentCount.get();

                // Re-check window reset under lock
                now = System.currentTimeMillis();
                windowStart = windowStartTime.get();
                if (now - windowStart >= windowSizeMs) {
                    currentCount.set(0);
                    int prevBurst = burstTokens.get();
                    int newBurstTokens = Math.min(burstCapacity, maxRequests + prevBurst);
                    burstTokens.set(newBurstTokens);
                    windowStartTime.set(now);
                    count = 0;
                }

                if (count < maxRequests) {
                    currentCount.incrementAndGet();
                    return true;
                }

                int tokens = burstTokens.get();
                if (tokens > 0) {
                    burstTokens.decrementAndGet();
                    currentCount.incrementAndGet();
                    return true;
                }

                return false;
            } finally {
                lock.unlock();
            }
        }

        // tryLock failed - immediately reject without blocking
        // This prevents EventLoop thread starvation under extreme load
        return false;
    }

    /**
     * Get remaining requests allowed (burst capacity minus current count).
     *
     * @return remaining requests
     */
    public int getRemaining() {
        return Math.max(0, burstCapacity - currentCount.get());
    }

    /**
     * Get remaining burst tokens.
     *
     * @return remaining burst tokens
     */
    public int getBurstRemaining() {
        return burstTokens.get();
    }

    /**
     * Get current request count in the window.
     *
     * @return current count
     */
    public int getCurrentCount() {
        return currentCount.get();
    }

    /**
     * Check if rate limiter is in burst mode.
     *
     * @return true if current count exceeds steady state limit
     */
    public boolean isInBurstMode() {
        return currentCount.get() >= maxRequests;
    }
}