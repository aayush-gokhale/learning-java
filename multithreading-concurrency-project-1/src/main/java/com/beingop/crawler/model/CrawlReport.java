package com.beingop.crawler.model;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Shared, thread-safe store for all CrawlResults produced during Phase 0.
 *
 * WHY @Component (singleton):
 * Both CrawlPipeline (writes results) and CrawlerOrchestrator (reads them for the
 * report) need the same instance. Spring's default singleton scope guarantees this.
 *
 * WHY CopyOnWriteArrayList:
 * In a web crawler, results are written frequently (one per page fetch) but read
 * only once (at report generation time). CopyOnWriteArrayList is ideal when writes
 * are infrequent relative to reads AND when you need iteration without locking.
 * Here, many Virtual Threads write concurrently while the main thread reads once at
 * the end — a perfect fit. A synchronized ArrayList would require explicit locking
 * on every add(), creating contention. A ConcurrentLinkedQueue would work but lacks
 * the List interface needed for stream operations in report generation.
 */
@Component
public class CrawlReport {

    private final List<CrawlResult> results = new CopyOnWriteArrayList<>();

    /** Phase 0 metrics — set by CrawlerOrchestrator after crawl completes. */
    private volatile long virtualThreadTimeMs;
    private volatile int virtualThreadUrlCount;

    /**
     * WHY volatile for the metrics fields:
     * CrawlerOrchestrator writes these on the main thread after Phase 0. Reporters
     * read them in Phase 2. Without volatile, the JVM is free to cache the values in
     * a CPU register and never flush to main memory, so readers might see stale values.
     * volatile guarantees visibility across threads without the overhead of synchronization.
     */
    public void addResult(CrawlResult result) {
        results.add(result);
    }

    public List<CrawlResult> getResults() {
        return results;
    }

    public List<CrawlResult> getBrokenLinks() {
        return results.stream().filter(r -> !r.isSuccess()).toList();
    }

    public Map<Integer, Long> getDepthDistribution() {
        return results.stream()
                .collect(Collectors.groupingBy(CrawlResult::getDepth, Collectors.counting()));
    }

    public void setVirtualThreadTimeMs(long ms) { this.virtualThreadTimeMs = ms; }
    public long getVirtualThreadTimeMs() { return virtualThreadTimeMs; }
    public void setVirtualThreadUrlCount(int count) { this.virtualThreadUrlCount = count; }
    public int getVirtualThreadUrlCount() { return virtualThreadUrlCount; }
}
