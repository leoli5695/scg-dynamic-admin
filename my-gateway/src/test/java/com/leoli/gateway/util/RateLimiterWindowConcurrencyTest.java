package com.leoli.gateway.util;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterWindowConcurrencyTest {
    @Test void simultaneousAcquisitionAndRolloverNeverExceedCapacity() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        RateLimiterWindow window = new RateLimiterWindow(71, 2, 1000, clock::get);
        try (ExecutorService pool = Executors.newFixedThreadPool(24)) {
            for (int round = 0; round < 10; round++) {
                clock.set(round * 1000L);
                CountDownLatch start = new CountDownLatch(1);
                var results = new ArrayList<Future<Integer>>();
                for (int worker = 0; worker < 24; worker++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        int allowed = 0;
                        for (int request = 0; request < 100; request++) {
                            if (window.tryAcquire()) allowed++;
                        }
                        return allowed;
                    }));
                }
                start.countDown();
                int allowed = 0;
                for (Future<Integer> result : results) allowed += result.get(5, TimeUnit.SECONDS);
                assertEquals(73, allowed, "round " + round);
                assertEquals(73, window.getCurrentCount());
                assertEquals(0, window.getRemaining());
            }
        }
    }

    @Test void expiredWindowReportsFreshCapacityAndZeroBurstRemainsZero() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiterWindow window = new RateLimiterWindow(1, 0, 1000, clock::get);
        assertTrue(window.tryAcquire());
        assertFalse(window.tryAcquire());
        clock.set(1000);
        assertEquals(1, window.getRemaining());
        assertTrue(window.tryAcquire());
        assertFalse(window.tryAcquire());
    }

    @Test void largeCapacityDoesNotOverflowIntoRejectingAllTraffic() {
        RateLimiterWindow window = new RateLimiterWindow(Integer.MAX_VALUE, Integer.MAX_VALUE, 1000);
        assertTrue(window.tryAcquire());
        assertEquals(Integer.MAX_VALUE - 1, window.getRemaining());
    }
}
