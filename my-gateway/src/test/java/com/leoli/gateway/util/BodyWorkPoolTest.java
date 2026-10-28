package com.leoli.gateway.util;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BodyWorkPoolTest {
    @Test void cancellationKeepsRunningTaskChargedUntilItActuallyExits() throws Exception {
        ByteBudget budget = new ByteBudget();
        var lease = budget.tryAcquire(100, 100);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1), exited = new CountDownLatch(1);
        try (BodyWorkPool pool = new BodyWorkPool("body-test", 1, 1)) {
            var subscription = pool.submit(lease, () -> {
                started.countDown(); finish.await(); return "done";
            }).doFinally(signal -> exited.countDown()).subscribe();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            subscription.dispose(); lease.close();
            assertEquals(100, budget.used());
            assertNull(budget.tryAcquire(1, 100));
            finish.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (budget.used() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, budget.used());
        } finally { finish.countDown(); }
    }

    @Test void rejectsExcessWorkWithoutLeakingItsReservation() throws Exception {
        ByteBudget budget = new ByteBudget();
        var first = budget.tryAcquire(1, 3); var second = budget.tryAcquire(1, 3); var third = budget.tryAcquire(1, 3);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (BodyWorkPool pool = new BodyWorkPool("body-test", 1, 1)) {
            pool.submit(first, () -> { started.countDown(); finish.await(); return 1; }).subscribe();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            pool.submit(second, () -> 2).subscribe();
            assertThrows(RejectedExecutionException.class, () -> pool.submit(third, () -> 3).block());
            third.close(); assertEquals(2, budget.used());
        } finally { first.close(); second.close(); finish.countDown(); }
    }
}
