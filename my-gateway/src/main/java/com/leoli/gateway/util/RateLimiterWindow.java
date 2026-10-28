package com.leoli.gateway.util;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Local fixed-window limiter. Capacity is steady quota plus extra burst capacity.
 * Window timestamp and count are published together so rollover cannot erase
 * an acquired permit. Contention never grants an uncounted permit.
 */
@Getter
public class RateLimiterWindow {
    private final int maxRequests;
    private final int burstCapacity;
    private final int totalCapacity;
    private final long windowSizeMs;
    private final LongSupplier clock;
    private final AtomicReference<WindowState> state;

    private record WindowState(long start, int count) { }

    public RateLimiterWindow(int maxRequests, long windowSizeMs) {
        this(maxRequests, (int) Math.min(Integer.MAX_VALUE, (long) maxRequests * 2), windowSizeMs);
    }

    public RateLimiterWindow(int maxRequests, int burstCapacity, long windowSizeMs) {
        this(maxRequests, burstCapacity, windowSizeMs,
                () -> java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    RateLimiterWindow(int maxRequests, int burstCapacity, long windowSizeMs, LongSupplier clock) {
        if (maxRequests < 0 || windowSizeMs <= 0) {
            throw new IllegalArgumentException("Quota must be nonnegative and window size must be positive");
        }
        this.maxRequests = maxRequests;
        this.burstCapacity = Math.max(0, burstCapacity);
        this.totalCapacity = (int) Math.min(Integer.MAX_VALUE, (long) maxRequests + this.burstCapacity);
        this.windowSizeMs = windowSizeMs;
        this.clock = clock;
        this.state = new AtomicReference<>(new WindowState(clock.getAsLong(), 0));
    }

    public boolean tryAcquire() {
        while (true) {
            WindowState previous = state.get();
            long now = clock.getAsLong();
            boolean expired = now - previous.start() >= windowSizeMs;
            int count = expired ? 0 : previous.count();
            if (count >= totalCapacity) {
                return false;
            }
            WindowState next = new WindowState(expired ? now : previous.start(), count + 1);
            if (state.compareAndSet(previous, next)) {
                return true;
            }
            Thread.onSpinWait();
        }
    }

    public int getRemaining() {
        return totalCapacity - getCurrentCount();
    }

    public int getCurrentCount() {
        WindowState current = state.get();
        return clock.getAsLong() - current.start() >= windowSizeMs ? 0 : current.count();
    }

    public boolean isInBurstMode() {
        return getCurrentCount() >= maxRequests;
    }
}
