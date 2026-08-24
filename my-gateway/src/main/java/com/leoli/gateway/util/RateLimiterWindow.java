package com.leoli.gateway.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe sliding time window rate limiter with burst support.
 * <p>
 * Uses CAS with limited spinning for low contention, tryLock fallback for
 * window rollover. This hybrid approach avoids blocking EventLoop threads in
 * reactive context.
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

    /**
     * FIX (false rejection under contention): bounded CAS retry count on the fast
     * path. Previously a single failed CAS dropped straight to tryLock, and a
     * failed tryLock rejected the request outright - under pure lock contention
     * with a far-from-full window legitimate requests were wrongly rejected and
     * the effective limit dropped well below the configured value.
     */
    private static final int MAX_CAS_SPINS = 3;

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
     * <p>
     * FIX (window rollover race): the previous fast path won a CAS on windowStartTime
     * and reset the counter OUTSIDE any lock - concurrent threads could increment the
     * stale counter after the timestamp moved, double-counting or wrongly rejecting
     * requests. Window reset now happens only under the lock.
     * <p>
     * FIX (false rejection under contention): the fast path now spins a bounded
     * number of CAS attempts instead of giving up after one failure, and when the
     * lock is contended the limiter fails OPEN as long as capacity is visibly
     * available - wrongly rejecting a request inside a far-from-full window costs
     * more than letting one extra request through.
     *
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean tryAcquire() {
        // Fast path: window still valid - limited CAS spinning for low contention
        for (int spin = 0; spin < MAX_CAS_SPINS; spin++) {
            long now = System.currentTimeMillis();
            long windowStart = windowStartTime.get();
            if (now - windowStart >= windowSizeMs) {
                break; // window expired - rollover must happen under the lock
            }
            int count = currentCount.get();
            if (count >= totalCapacity) {
                // Total capacity exhausted
                return false;
            }
            if (currentCount.compareAndSet(count, count + 1)) {
                return true;
            }
            // CAS failed due to contention - spin briefly before the lock path
            Thread.onSpinWait();
        }

        // Slow path: tryLock for window rollover (never blocks!)
        if (lock.tryLock()) {
            try {
                // Re-check window reset under lock (atomic reset + start update)
                long now = System.currentTimeMillis();
                long windowStart = windowStartTime.get();
                if (now - windowStart >= windowSizeMs) {
                    currentCount.set(0);
                    windowStartTime.set(now);
                    if (log.isDebugEnabled()) {
                        log.debug("Window reset: currentCount reset to 0, totalCapacity={}", totalCapacity);
                    }
                }

                int count = currentCount.get();
                if (count < totalCapacity) {
                    currentCount.incrementAndGet();
                    return true;
                }

                return false;
            } finally {
                lock.unlock();
            }
        }

        // tryLock failed (another thread is resetting the window). Instead of
        // rejecting immediately, fail open when capacity is visibly available:
        // rejecting while the window is far from full would make burst traffic
        // hit an effective limit well below the configured value.
        int count = currentCount.get();
        if (count >= totalCapacity) {
            return false;
        }
        currentCount.incrementAndGet(); // best-effort accounting under contention
        return true;
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