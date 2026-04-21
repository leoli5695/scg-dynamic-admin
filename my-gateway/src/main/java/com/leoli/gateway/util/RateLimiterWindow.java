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
 * - Burst capacity for handling traffic spikes (累加语义: 总容量 = maxRequests + burstCapacity)
 * - Non-blocking implementation suitable for reactive environments
 * - Automatic window reset with CAS-based coordination
 *
 * @author leoli
 */
@Data
@Slf4j
public class RateLimiterWindow {

    private final int maxRequests;          // 稳定流量阈值
    private final int burstCapacity;        // 突发额外容量（累加语义）
    private final int totalCapacity;        // 总容量 = maxRequests + burstCapacity
    private final long windowSizeMs;
    private final AtomicInteger currentCount = new AtomicInteger(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Create a rate limiter with default burst capacity (2x maxRequests).
     *
     * @param maxRequests   Maximum requests allowed per window (steady state rate)
     * @param windowSizeMs  Window size in milliseconds
     */
    public RateLimiterWindow(int maxRequests, long windowSizeMs) {
        this(maxRequests, maxRequests * 2, windowSizeMs);
    }

    /**
     * Create a rate limiter with custom burst capacity.
     * <p>
     * 累加语义: 总容量 = maxRequests + burstCapacity
     * 例如: maxRequests=5, burstCapacity=3 → 总容量=8
     *
     * @param maxRequests   Maximum requests allowed per window (steady state rate)
     * @param burstCapacity Extra burst capacity (累加语义)
     * @param windowSizeMs  Window size in milliseconds
     */
    public RateLimiterWindow(int maxRequests, int burstCapacity, long windowSizeMs) {
        this.maxRequests = maxRequests;
        this.burstCapacity = Math.max(0, burstCapacity);  // 允许 burstCapacity >= 0
        this.totalCapacity = maxRequests + this.burstCapacity;
        this.windowSizeMs = windowSizeMs;
    }

    /**
     * Try to acquire a permit from the rate limiter.
     * <p>
     * 累加语义逻辑:
     * 1. currentCount < maxRequests → 稳定流量，允许
     * 2. currentCount < totalCapacity → 突发流量，允许
     * 3. currentCount >= totalCapacity → 拒绝
     *
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long windowStart = windowStartTime.get();

        // Try to reset window if expired (CAS ensures only one thread performs reset)
        if (now - windowStart >= windowSizeMs) {
            if (windowStartTime.compareAndSet(windowStart, now)) {
                // This thread won the CAS - responsible for resetting counter
                currentCount.set(0);
                if (log.isDebugEnabled()) {
                    log.debug("Window reset: currentCount reset to 0, totalCapacity={}", totalCapacity);
                }
            }
            // Continue with acquire logic after potential reset
        }

        // Fast path: try CAS for low contention (optimistic)
        int count = currentCount.get();
        if (count < totalCapacity) {
            if (currentCount.compareAndSet(count, count + 1)) {
                return true;
            }
            // CAS failed due to contention - fall through to slow path
        } else {
            // Total capacity exhausted
            return false;
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
                    windowStartTime.set(now);
                    count = 0;
                }

                if (count < totalCapacity) {
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
     * Get remaining requests allowed (total capacity minus current count).
     *
     * @return remaining requests
     */
    public int getRemaining() {
        return Math.max(0, totalCapacity - currentCount.get());
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

    /**
     * Get total capacity (maxRequests + burstCapacity).
     *
     * @return total capacity
     */
    public int getTotalCapacity() {
        return totalCapacity;
    }
}