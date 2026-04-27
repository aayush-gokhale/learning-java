package com.beingop.crawler.core;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks live crawl statistics across all concurrent worker threads.
 *
 * WHY ConcurrentHashMap + AtomicInteger (not a synchronized Map):
 * A synchronized HashMap locks the entire map on every get/put — all 1000 Virtual
 * Threads would serialize on a single lock, killing parallelism.
 * ConcurrentHashMap uses bucket-level locking (Java 8+: CAS on individual bins),
 * so threads contend only if they happen to hit the same bucket — rare in practice.
 * AtomicInteger uses CPU-level Compare-And-Swap (CAS) hardware instructions,
 * making incrementAndGet() lock-free. No lock acquisition, no context switch overhead.
 *
 * WHY computeIfAbsent for lazy initialization:
 * We don't know which counter keys will be used at startup. computeIfAbsent is
 * atomic — even if two threads call it simultaneously for the same key, only one
 * AtomicInteger is created and stored. The other thread gets the same instance.
 */
@Component
public class StatsCollector {

    public static final String URLS_VISITED = "urls_visited";
    public static final String URLS_FAILED  = "urls_failed";
    public static final String URLS_SKIPPED = "urls_skipped";

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Tracks how many CompletableFuture tasks are currently in-flight.
     * Used by CrawlPipeline to detect crawl completion:
     *   frontier.isEmpty() && activeTasks == 0  ->  all work is done.
     *
     * WHY a separate AtomicInteger (not in the counters map):
     * activeTasks is incremented/decremented on the hot path of every task
     * submission and completion. A direct AtomicInteger field is faster than a
     * map lookup followed by an increment.
     */
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    public void increment(String key) {
        counters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public int get(String key) {
        AtomicInteger counter = counters.get(key);
        return counter != null ? counter.get() : 0;
    }

    public int incrementActiveTasks() { return activeTasks.incrementAndGet(); }
    public int decrementActiveTasks() { return activeTasks.decrementAndGet(); }
    public int getActiveTasks()       { return activeTasks.get(); }

    public void reset() {
        counters.clear();
        activeTasks.set(0);
    }
}
