package com.beingop.crawler.model;

/**
 * Immutable result from Phase 1 (Platform Thread / ForkJoinPool benchmark).
 * Passed from BenchmarkRunner → CrawlerOrchestrator → reporters.
 *
 * WHY a record:
 * BenchmarkResult is created once, read many times, never mutated.
 * Records enforce this contract at the language level.
 */
public record BenchmarkResult(int urlCount, long elapsedMs) {

    /**
     * WHY a method instead of a stored field:
     * Throughput is derived from urlCount and elapsedMs — storing it separately
     * would create the possibility of inconsistency if one field changed.
     */
    public double throughput() {
        return elapsedMs == 0 ? 0.0 : urlCount / (elapsedMs / 1000.0);
    }
}
