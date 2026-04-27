package com.beingop.crawler.core;

import com.beingop.crawler.benchmark.BenchmarkRunner;
import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.model.BenchmarkResult;
import com.beingop.crawler.model.CrawlReport;
import com.beingop.crawler.model.CrawlTask;
import com.beingop.crawler.pipeline.CrawlPipeline;
import com.beingop.crawler.report.ConsoleReporter;
import com.beingop.crawler.report.FileReporter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

/**
 * The top-level controller that wires all components into a 3-phase crawl.
 * Implements CommandLineRunner so Spring Boot calls run() automatically on startup.
 *
 * WHY Phaser (not CountDownLatch or CyclicBarrier):
 *
 * CountDownLatch: one-shot, fixed count at creation. Can't reuse across multiple phases.
 *
 * CyclicBarrier: reusable, but all parties must call await() simultaneously at the
 * barrier. Our phases don't have multiple threads converging — just the main thread
 * advancing through sequential phases.
 *
 * Phaser: the most flexible synchronizer. Supports:
 *   - Dynamic party registration (register()/arriveAndDeregister())
 *   - Multiple phases (arriveAndAwaitAdvance() advances the phase counter)
 *   - A single party (the main thread) is enough — no requirement for multiple threads
 *
 * In our design, the Phaser has 1 registered party (the main thread). Each phase
 * boundary is crossed by arriveAndAwaitAdvance(), which atomically arrives AND waits
 * for the phase to advance. With only 1 party, it advances immediately — but this
 * structure makes it trivial to add parallel phases later (just register more parties).
 *
 * WHY this is the entry point (not main()):
 * CommandLineRunner.run() is called by Spring after the application context is fully
 * initialized — all @Component beans are created and injected. This lets the
 * orchestrator receive fully configured dependencies without manual wiring.
 */
@Component
public class CrawlerOrchestrator implements CommandLineRunner {

    private final CrawlerConfig    config;
    private final CrawlFrontier    frontier;
    private final CrawlPipeline    pipeline;
    private final StatsCollector   stats;
    private final CrawlReport      report;
    private final BenchmarkRunner  benchmarkRunner;
    private final ConsoleReporter  consoleReporter;
    private final FileReporter     fileReporter;

    public CrawlerOrchestrator(CrawlerConfig config, CrawlFrontier frontier,
                               CrawlPipeline pipeline, StatsCollector stats,
                               CrawlReport report, BenchmarkRunner benchmarkRunner,
                               ConsoleReporter consoleReporter, FileReporter fileReporter) {
        this.config          = config;
        this.frontier        = frontier;
        this.pipeline        = pipeline;
        this.stats           = stats;
        this.report          = report;
        this.benchmarkRunner = benchmarkRunner;
        this.consoleReporter = consoleReporter;
        this.fileReporter    = fileReporter;
    }

    @Override
    public void run(String... args) throws Exception {
        // Phaser with 1 party: the main thread. Each arriveAndAwaitAdvance() call
        // advances one phase. With 1 party it advances immediately, but the Phaser
        // still serves as the canonical phase-tracking mechanism.
        Phaser phaser = new Phaser(1);

        // ── PHASE 0: CRAWL WITH VIRTUAL THREADS ─────────────────────────────────
        System.out.printf("%n[PHASE 0] Crawling %s (max depth: %d) with Virtual Threads...%n",
                config.getSeedUrl(), config.getMaxDepth());

        frontier.seed(config.getSeedUrl());
        long crawlStart = System.currentTimeMillis();

        runCrawlLoop();

        long crawlTime = System.currentTimeMillis() - crawlStart;
        report.setVirtualThreadTimeMs(crawlTime);
        report.setVirtualThreadUrlCount(stats.get(StatsCollector.URLS_VISITED));

        System.out.printf("%n[PHASE 0] Complete. Visited %d URLs in %.1fs.%n",
                stats.get(StatsCollector.URLS_VISITED), crawlTime / 1000.0);

        phaser.arriveAndAwaitAdvance(); // advance from phase 0 → 1

        // ── PHASE 1: BENCHMARK WITH PLATFORM THREADS (FORKJOINPOOL) ─────────────
        BenchmarkResult benchmarkResult = null;
        if (config.isBenchmarkEnabled()) {
            System.out.printf("%n[PHASE 1] Re-crawling with Platform Threads (ForkJoinPool) for benchmark...%n");
            benchmarkResult = benchmarkRunner.run();
            System.out.printf("[PHASE 1] Complete. Visited %d URLs in %.1fs.%n",
                    benchmarkResult.urlCount(), benchmarkResult.elapsedMs() / 1000.0);
        } else {
            System.out.println("[PHASE 1] Skipped (benchmark-enabled=false).");
        }

        phaser.arriveAndAwaitAdvance(); // advance from phase 1 → 2

        // ── PHASE 2: REPORT ───────────────────────────────────────────────────────
        System.out.println("\n[PHASE 2] Generating reports...");
        consoleReporter.printSummary(report, benchmarkResult);
        fileReporter.write(report, benchmarkResult);
        System.out.printf("[PHASE 2] File report saved to: %s%n", config.getOutputFile());

        phaser.arriveAndDeregister(); // main thread done — deregister from Phaser

        // Gracefully shut down the Virtual Thread executor
        pipeline.shutdown();
    }

    /**
     * The main crawl loop. Polls the frontier for tasks and submits them to the
     * pipeline until both the queue is empty and all in-flight tasks have completed.
     *
     * WHY poll with a timeout instead of take():
     * take() blocks indefinitely — if the queue is temporarily empty because all
     * in-flight tasks are still running (but haven't yet enqueued new URLs), take()
     * would hang. poll(timeout) lets us re-check pipeline.isDone() after the timeout,
     * correctly detecting termination when queue is empty AND no tasks are active.
     *
     * WHY printLiveStats() inside the loop:
     * Each iteration takes ~100ms (the poll timeout). Printing stats each iteration
     * gives the user a live counter that updates every ~100ms without a separate thread.
     */
    private void runCrawlLoop() throws InterruptedException {
        while (!pipeline.isDone()) {
            CrawlTask task = frontier.poll(100, TimeUnit.MILLISECONDS);
            if (task != null) {
                pipeline.submit(task);
            }
            consoleReporter.printLiveStats();
        }
    }
}
