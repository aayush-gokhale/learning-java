package com.beingop.crawler.report;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.model.BenchmarkResult;
import com.beingop.crawler.model.CrawlReport;
import com.beingop.crawler.model.CrawlResult;
import org.springframework.stereotype.Component;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Writes the final crawl report to a file, guarded by a ReentrantReadWriteLock.
 *
 * WHY ReentrantReadWriteLock here (even though only one thread writes):
 * This class demonstrates the ReadWriteLock pattern for educational purposes.
 * In a production crawler, multiple threads might concurrently read partial results
 * to stream a live report while the crawl is running. ReadWriteLock allows:
 *   - Unlimited concurrent readers (no contention between read-only threads)
 *   - Exclusive writer (blocks all readers while writing, ensuring consistency)
 *
 * WHY NOT a simple synchronized method:
 * synchronized allows only one thread at a time, period — even concurrent reads
 * block each other. ReadWriteLock is strictly more permissive: multiple readers
 * proceed in parallel, and only the writer requires exclusivity.
 *
 * WHY writeLock().lock() in a try/finally:
 * If the file write throws an IOException, the lock MUST still be released.
 * Without finally, a write exception would leave the lock permanently held,
 * and any future reader or writer would deadlock waiting to acquire it.
 */
@Component
public class FileReporter {

    private final CrawlerConfig config;
    private final ReentrantReadWriteLock lock      = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public FileReporter(CrawlerConfig config) {
        this.config = config;
    }

    public void write(CrawlReport report, BenchmarkResult benchmarkResult) {
        writeLock.lock();
        try {
            writeReport(report, benchmarkResult);
        } finally {
            writeLock.unlock(); // always release — even if writeReport() throws
        }
    }

    private void writeReport(CrawlReport report, BenchmarkResult benchmarkResult) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(config.getOutputFile()))) {

            writer.println("=== WEB CRAWLER REPORT ===");
            writer.printf("Generated : %s%n", LocalDateTime.now());
            writer.printf("Seed URL  : %s%n", config.getSeedUrl());
            writer.printf("Max Depth : %d%n", config.getMaxDepth());
            writer.println();

            writer.println("--- VISITED URLS ---");
            report.getResults().stream()
                    .filter(CrawlResult::isSuccess)
                    .forEach(r -> writer.printf("[%d] (depth=%d) %s%n",
                            r.getStatusCode(), r.getDepth(), r.getUrl()));

            writer.println();
            writer.println("--- BROKEN LINKS ---");
            if (report.getBrokenLinks().isEmpty()) {
                writer.println("None");
            } else {
                report.getBrokenLinks().forEach(r ->
                        writer.printf("[%d] %s%s%n",
                                r.getStatusCode(), r.getUrl(),
                                r.getErrorMessage() != null ? " (" + r.getErrorMessage() + ")" : ""));
            }

            writer.println();
            writer.println("--- DEPTH DISTRIBUTION ---");
            report.getDepthDistribution().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> writer.printf("  Depth %d : %d URLs%n", e.getKey(), e.getValue()));

            if (benchmarkResult != null) {
                writer.println();
                writer.println("--- BENCHMARK RESULTS ---");
                writer.printf("Virtual Threads    : %d URLs in %.1fs (%.1f URLs/s)%n",
                        report.getVirtualThreadUrlCount(),
                        report.getVirtualThreadTimeMs() / 1000.0,
                        report.getVirtualThreadUrlCount() / (report.getVirtualThreadTimeMs() / 1000.0));
                writer.printf("Platform Threads   : %d URLs in %.1fs (%.1f URLs/s)%n",
                        benchmarkResult.urlCount(),
                        benchmarkResult.elapsedMs() / 1000.0,
                        benchmarkResult.throughput());
            }

        } catch (IOException e) {
            System.err.printf("[ERROR] Failed to write report to %s: %s%n",
                    config.getOutputFile(), e.getMessage());
        }
    }
}
