package com.beingop.crawler.report;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.core.CrawlFrontier;
import com.beingop.crawler.core.StatsCollector;
import com.beingop.crawler.model.BenchmarkResult;
import com.beingop.crawler.model.CrawlReport;
import org.springframework.stereotype.Component;

/**
 * Prints live progress and the final summary to stdout.
 *
 * Thread safety note: printLiveStats() is called from the orchestrator's main loop
 * and printSummary() is called once in Phase 2. Neither method is called concurrently,
 * so no synchronization is needed here. The stats it reads (StatsCollector) are
 * themselves thread-safe — it reads a consistent snapshot of AtomicInteger values.
 */
@Component
public class ConsoleReporter {

    private final StatsCollector stats;
    private final CrawlFrontier  frontier;
    private final CrawlerConfig  config;

    public ConsoleReporter(StatsCollector stats, CrawlFrontier frontier, CrawlerConfig config) {
        this.stats    = stats;
        this.frontier = frontier;
        this.config   = config;
    }

    /** Called repeatedly by the orchestrator during Phase 0 to show live progress. */
    public void printLiveStats() {
        // \r (carriage return) moves the cursor to the start of the current line
        // without advancing to the next — creates an in-place updating counter
        System.out.printf("\r[CRAWL]  Visited: %-5d | Errors: %-4d | Queue: %-5d",
                stats.get(StatsCollector.URLS_VISITED),
                stats.get(StatsCollector.URLS_FAILED),
                frontier.size());
    }

    public void printSummary(CrawlReport report, BenchmarkResult benchmarkResult) {
        System.out.println(); // newline after the live counter
        System.out.println();
        System.out.printf("[DONE]   Total visited: %d | Broken: %d | Virtual Thread time: %.1fs%n",
                report.getVirtualThreadUrlCount(),
                report.getBrokenLinks().size(),
                report.getVirtualThreadTimeMs() / 1000.0);

        if (config.isBenchmarkEnabled() && benchmarkResult != null) {
            System.out.println();
            System.out.println("--- BENCHMARK RESULTS ---");
            System.out.printf("%-22s | %-12s | %-10s | %s%n",
                    "Mode", "URLs Crawled", "Time", "Throughput");
            System.out.printf("%-22s | %-12d | %-10.1fs | %.1f URLs/s%n",
                    "Virtual Threads",
                    report.getVirtualThreadUrlCount(),
                    report.getVirtualThreadTimeMs() / 1000.0,
                    report.getVirtualThreadUrlCount() / (report.getVirtualThreadTimeMs() / 1000.0));
            System.out.printf("%-22s | %-12d | %-10.1fs | %.1f URLs/s%n",
                    "Platform Threads (FJP)",
                    benchmarkResult.urlCount(),
                    benchmarkResult.elapsedMs() / 1000.0,
                    benchmarkResult.throughput());
        }
    }
}
