package com.beingop.crawler.benchmark;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.core.PageFetcher;
import com.beingop.crawler.core.PageParser;
import com.beingop.crawler.model.BenchmarkResult;
import com.beingop.crawler.model.CrawlResult;
import com.beingop.crawler.model.CrawlTask;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * Re-crawls the same website using Platform Threads via ForkJoinPool, to measure
 * how much faster Virtual Threads are for I/O-bound workloads.
 *
 * WHY ForkJoinPool for the benchmark:
 * ForkJoinPool implements the work-stealing algorithm: idle threads "steal" tasks
 * from busy threads' queues, maximizing CPU utilization for recursive, divide-and-
 * conquer workloads. A web crawl is naturally recursive — each page forks subtasks
 * for its child links — making ForkJoinPool a conceptually clean fit.
 * The contrast with VirtualThreadPerTaskExecutor is instructive: ForkJoin uses a
 * fixed pool of Platform Threads (one per CPU core) that block when waiting for I/O.
 * Virtual Threads decouple concurrency from OS threads, allowing thousands of
 * simultaneous I/O waits without proportional thread overhead.
 *
 * WHY RecursiveTask<Integer> (not RecursiveAction):
 * RecursiveTask returns a value — here, the count of URLs crawled by this subtask
 * and all its descendants. The root task's result is the total URL count, which
 * we use to verify the benchmark crawled the same number of pages as Phase 0.
 */
@Component
public class BenchmarkRunner {

    private final PageFetcher fetcher;
    private final PageParser  parser;
    private final CrawlerConfig config;

    public BenchmarkRunner(PageFetcher fetcher, PageParser parser, CrawlerConfig config) {
        this.fetcher = fetcher;
        this.parser  = parser;
        this.config  = config;
    }

    public BenchmarkResult run() {
        ConcurrentHashMap<String, Boolean> visited = new ConcurrentHashMap<>();
        String domain = parser.extractDomain(config.getSeedUrl());

        // Size the pool to available CPU cores — Platform Threads are CPU-bound when
        // not blocked, so more threads than cores would cause context-switch overhead.
        ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());

        long start = System.currentTimeMillis();
        try {
            int urlCount = pool.invoke(
                    new CrawlRecursiveTask(config.getSeedUrl(), 0, visited, domain)
            );
            long elapsed = System.currentTimeMillis() - start;
            return new BenchmarkResult(urlCount, elapsed);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * One unit of recursive crawl work: fetch a page, fork subtasks for each link.
     *
     * WHY this is an inner class:
     * CrawlRecursiveTask needs access to fetcher, parser, and config from the outer
     * BenchmarkRunner. Making it an inner class avoids passing these as constructor
     * arguments to every recursively created instance.
     *
     * HOW RecursiveTask works:
     * 1. compute() is called by ForkJoinPool on a worker thread.
     * 2. Create subtasks for child URLs.
     * 3. invokeAll(subtasks) forks them onto the pool and BLOCKS until all complete.
     *    Crucially, "blocks" in ForkJoinPool means: the current thread helps process
     *    other queued tasks while waiting — no thread sits idle. This is work-stealing.
     * 4. join() retrieves each subtask's computed Integer result (already done by invokeAll).
     */
    private class CrawlRecursiveTask extends RecursiveTask<Integer> {

        private final String url;
        private final int    depth;
        private final ConcurrentHashMap<String, Boolean> visited;
        private final String domain;

        CrawlRecursiveTask(String url, int depth,
                           ConcurrentHashMap<String, Boolean> visited, String domain) {
            this.url     = url;
            this.depth   = depth;
            this.visited = visited;
            this.domain  = domain;
        }

        @Override
        protected Integer compute() {
            // Atomic deduplication — same mechanism as CrawlFrontier.enqueue()
            // putIfAbsent returns null only if this thread was the first to insert this URL
            if (visited.putIfAbsent(url, Boolean.TRUE) != null) {
                return 0; // already visited by another task — stop this branch
            }

            if (depth > config.getMaxDepth()) {
                return 0;
            }

            CrawlResult result = fetcher.fetch(new CrawlTask(url, depth));
            if (!result.isSuccess()) {
                return 1; // count the attempt even if it failed
            }

            List<String> links = parser.extractLinks(
                    result.getHtml() != null ? result.getHtml() : "",
                    url, domain, depth, config.getMaxDepth()
            );

            // Create a RecursiveTask for each child link
            List<CrawlRecursiveTask> subtasks = links.stream()
                    .map(link -> new CrawlRecursiveTask(link, depth + 1, visited, domain))
                    .toList();

            // invokeAll: forks all subtasks onto the ForkJoinPool simultaneously,
            // then blocks (using work-stealing) until ALL of them complete.
            invokeAll(subtasks);

            // join() retrieves the already-computed result — no blocking here
            int childCount = subtasks.stream().mapToInt(CrawlRecursiveTask::join).sum();
            return 1 + childCount;
        }
    }
}
