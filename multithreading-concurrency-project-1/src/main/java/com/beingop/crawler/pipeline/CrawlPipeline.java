package com.beingop.crawler.pipeline;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.core.CrawlFrontier;
import com.beingop.crawler.core.PageFetcher;
import com.beingop.crawler.core.PageParser;
import com.beingop.crawler.core.StatsCollector;
import com.beingop.crawler.model.CrawlReport;
import com.beingop.crawler.model.CrawlResult;
import com.beingop.crawler.model.CrawlTask;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects the frontier queue to the fetch/parse/enqueue cycle using two
 * modern concurrency primitives: CompletableFuture and Virtual Threads.
 *
 * WHY CompletableFuture for the pipeline:
 * The crawler's core work — fetch a page, parse its links, enqueue them — is a
 * natural pipeline of dependent steps. Without CompletableFuture, you'd write
 * nested callbacks (callback hell) or block a thread waiting for each step.
 * CompletableFuture models asynchronous pipelines as composable method chains:
 *   supplyAsync → thenApply → thenAccept → whenComplete
 * Each stage runs when the previous one finishes, on the provided executor.
 *
 * WHY Virtual Threads (VirtualThreadPerTaskExecutor):
 * Each page fetch involves blocking I/O (network call). With Platform Threads,
 * blocking I/O blocks the OS thread — you can't have thousands of them.
 * Virtual Threads are lightweight (JVM-managed, not OS-managed). When a Virtual
 * Thread blocks on I/O, the JVM mounts it off the carrier thread, which then
 * picks up another Virtual Thread. This allows thousands of concurrent page
 * fetches with minimal OS thread count — perfect for I/O-bound crawling.
 *
 * WHY AtomicInteger for active task tracking (not CountDownLatch):
 * A CountDownLatch requires a fixed count at creation time. Our crawler doesn't
 * know how many tasks it will create — new URLs are discovered as pages are crawled.
 * AtomicInteger supports dynamic increment/decrement. The crawl is complete when:
 *   frontier.isEmpty() && activeTasks == 0
 * This is safe because activeTasks is incremented BEFORE submission and decremented
 * AFTER enqueueLinks — so if we see 0 active tasks and an empty frontier, all
 * discovered URLs have already been enqueued and processed.
 */
@Component
public class CrawlPipeline {

    private final PageFetcher fetcher;
    private final PageParser  parser;
    private final CrawlFrontier frontier;
    private final StatsCollector stats;
    private final CrawlReport report;
    private final CrawlerConfig config;
    private final String domain;
    private final ExecutorService executor;
    private final AtomicInteger activeTasks;

    public CrawlPipeline(PageFetcher fetcher, PageParser parser, CrawlFrontier frontier,
                         StatsCollector stats, CrawlReport report, CrawlerConfig config) {
        this.fetcher     = fetcher;
        this.parser      = parser;
        this.frontier    = frontier;
        this.stats       = stats;
        this.report      = report;
        this.config      = config;
        this.domain      = parser.extractDomain(config.getSeedUrl());
        this.activeTasks = new AtomicInteger(0);
        this.executor    = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Submits one CrawlTask through the full async pipeline.
     * Returns immediately — the pipeline runs asynchronously on Virtual Threads.
     */
    public void submit(CrawlTask task) {
        // Increment BEFORE submitting — ensures activeTasks > 0 while this task is alive,
        // preventing isDone() from returning true prematurely.
        activeTasks.incrementAndGet();

        CompletableFuture
            // Stage 1: Fetch the page (blocking I/O — runs on a Virtual Thread)
            .supplyAsync(() -> fetcher.fetch(task), executor)

            // Stage 2: Parse discovered links from the HTML body
            .thenApply(fetchResult -> {
                List<String> links = parser.extractLinks(
                        fetchResult.getHtml() != null ? fetchResult.getHtml() : "",
                        task.url(), domain, task.depth(), config.getMaxDepth()
                );
                // Build an updated CrawlResult with links set, html cleared to free memory
                return fetchResult.toBuilder()
                        .discoveredLinks(links)
                        .html(null)
                        .build();
            })

            // Stage 3: Record result and enqueue discovered links
            .thenAccept(result -> {
                report.addResult(result);

                if (result.isSuccess()) {
                    stats.increment(StatsCollector.URLS_VISITED);
                    result.getDiscoveredLinks()
                          .forEach(url -> frontier.enqueue(url, task.depth() + 1));
                } else {
                    stats.increment(StatsCollector.URLS_FAILED);
                }
            })

            // Stage 4: Error recovery — if any stage throws, log and count as failure
            .exceptionally(ex -> {
                stats.increment(StatsCollector.URLS_FAILED);
                System.err.printf("[ERROR] Pipeline failed for %s: %s%n", task.url(), ex.getMessage());
                return null;
            })

            // Stage 5: Always runs — decrement active counter so isDone() works correctly
            .whenComplete((v, ex) -> activeTasks.decrementAndGet());
    }

    /**
     * The crawl is complete when:
     * - No URLs are waiting in the frontier queue, AND
     * - No CompletableFuture tasks are currently executing
     *
     * WHY both conditions are required:
     * If only frontier.isEmpty() were checked, we'd stop prematurely when the queue
     * drains but tasks are still in-flight (they might enqueue new links).
     * If only activeTasks == 0 were checked, we'd stop when tasks finish before
     * the orchestrator polls them from the queue.
     */
    public boolean isDone() {
        return frontier.isEmpty() && activeTasks.get() == 0;
    }

    public void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }
}
