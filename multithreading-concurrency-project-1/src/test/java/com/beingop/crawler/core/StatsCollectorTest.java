package com.beingop.crawler.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StatsCollectorTest {

    /**
     * This test verifies thread safety of increment() under high contention.
     *
     * HOW IT WORKS:
     * - CountDownLatch(1) acts as a starting gun: all threads wait at startLatch.await()
     *   until countDown() fires them simultaneously, maximizing contention.
     * - CountDownLatch(threadCount) acts as a finish line: main thread blocks at
     *   doneLatch.await() until every worker thread has called countDown().
     * - Without AtomicInteger (using a plain int instead), this test would fail
     *   intermittently due to lost updates from unsynchronized read-modify-write cycles.
     */
    @Test
    void concurrentIncrements_shouldBeAccurate() throws InterruptedException {
        StatsCollector collector = new StatsCollector();
        int threadCount = 200;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // block until all threads are ready
                    collector.increment(StatsCollector.URLS_VISITED);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown(); // signal this thread is done
                }
            }).start();
        }

        startLatch.countDown(); // fire all threads at once
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads did not finish in time");
        assertEquals(threadCount, collector.get(StatsCollector.URLS_VISITED));
    }

    @Test
    void activeTaskCounter_incrementAndDecrement() {
        StatsCollector collector = new StatsCollector();
        assertEquals(0, collector.getActiveTasks());
        collector.incrementActiveTasks();
        collector.incrementActiveTasks();
        assertEquals(2, collector.getActiveTasks());
        collector.decrementActiveTasks();
        assertEquals(1, collector.getActiveTasks());
    }

    @Test
    void reset_clearsAllCounters() {
        StatsCollector collector = new StatsCollector();
        collector.increment(StatsCollector.URLS_VISITED);
        collector.incrementActiveTasks();
        collector.reset();
        assertEquals(0, collector.get(StatsCollector.URLS_VISITED));
        assertEquals(0, collector.getActiveTasks());
    }
}
