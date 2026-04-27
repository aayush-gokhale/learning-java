# High-Concurrency Web Crawler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a real-world web crawler that teaches every major Java concurrency concept through in-depth comments, a 3-phase architecture (Crawl → Benchmark → Report), and configurable rate limiting.

**Architecture:** A `Phaser` coordinates three phases. Phase 0 crawls with Virtual Threads via `CompletableFuture` chains backed by a `BlockingQueue` frontier. Phase 1 re-crawls using `ForkJoinPool` for a Platform Thread benchmark comparison. Phase 2 generates console and file reports.

**Tech Stack:** Java 25, Spring Boot 4.0.6, jsoup 1.17.2, Lombok, JUnit 5

---

## File Map

| File | Responsibility |
|---|---|
| `src/main/resources/application.yaml` | All config values (seed URL, depth, concurrency, delay) |
| `config/CrawlerConfig.java` | `@ConfigurationProperties` binding |
| `model/CrawlTask.java` | Record: unit of work placed in the frontier queue |
| `model/CrawlResult.java` | `@Builder` result of one page fetch |
| `model/CrawlReport.java` | `@Component` thread-safe aggregated report (`CopyOnWriteArrayList`) |
| `model/BenchmarkResult.java` | Record: Platform Thread run output |
| `core/StatsCollector.java` | `ConcurrentHashMap<String, AtomicInteger>` live counters + active task tracking |
| `core/CrawlFrontier.java` | `LinkedBlockingQueue` frontier + `ConcurrentHashMap` visited-URL deduplication |
| `core/PageParser.java` | Stateless jsoup link extractor — same-domain + depth filter |
| `core/PageFetcher.java` | jsoup HTTP fetch guarded by `Semaphore` + configurable delay |
| `pipeline/CrawlPipeline.java` | `CompletableFuture` chain (fetch→parse→enqueue) on `VirtualThreadPerTaskExecutor` |
| `benchmark/BenchmarkRunner.java` | `ForkJoinPool` + `RecursiveTask` Platform Thread re-crawl |
| `report/ConsoleReporter.java` | Live stats + final summary printed to stdout |
| `report/FileReporter.java` | Writes `crawl-report.txt` guarded by `ReentrantReadWriteLock` |
| `core/CrawlerOrchestrator.java` | `Phaser` phase controller, `CommandLineRunner` entry point |

All files live under `src/main/java/com/beingop/crawler/` and tests under `src/test/java/com/beingop/crawler/`.

---

## Task 1: Project Foundation — Config + application.yaml

**Files:**
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/beingop/crawler/config/CrawlerConfig.java`

- [ ] **Step 1: Update application.yaml**

```yaml
spring:
  main:
    # This is a CLI app — no embedded web server needed
    web-application-type: none

crawler:
  seed-url: https://wikipedia.org   # Starting URL — change this to test different sites
  max-depth: 2                      # How many link-hops deep to crawl from the seed
  max-concurrent-requests: 10       # Semaphore permits: max simultaneous HTTP requests
  request-delay-ms: 200             # Milliseconds to wait between requests (politeness)
  output-file: crawl-report.txt     # Where to write the final file report
  benchmark-enabled: true           # Set false to skip the Platform Thread benchmark
```

- [ ] **Step 2: Create CrawlerConfig.java**

```java
package com.beingop.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds all values under the "crawler" prefix in application.yaml to this object.
 *
 * WHY @ConfigurationProperties over @Value:
 * @Value injects one property at a time — scattered across classes, hard to see
 * all config in one place. @ConfigurationProperties binds an entire namespace into
 * a single typed object, making the configuration surface explicit and testable.
 *
 * WHY @Component here:
 * Makes Spring manage this bean so it can be injected everywhere without
 * needing @EnableConfigurationProperties on the main class.
 */
@Component
@ConfigurationProperties(prefix = "crawler")
@Data
public class CrawlerConfig {

    private String seedUrl = "https://wikipedia.org";
    private int maxDepth = 2;
    private int maxConcurrentRequests = 10;
    private long requestDelayMs = 200;
    private String outputFile = "crawl-report.txt";
    private boolean benchmarkEnabled = true;
}
```

- [ ] **Step 3: Verify the app starts with no errors**

```bash
mvn spring-boot:run
```

Expected: App starts and immediately exits (no `CommandLineRunner` yet). No `BeanCreationException`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yaml src/main/java/com/beingop/crawler/config/CrawlerConfig.java
git commit -m "feat: add CrawlerConfig and application.yaml"
```

---

## Task 2: Model Classes

**Files:**
- Create: `src/main/java/com/beingop/crawler/model/CrawlTask.java`
- Create: `src/main/java/com/beingop/crawler/model/CrawlResult.java`
- Create: `src/main/java/com/beingop/crawler/model/CrawlReport.java`
- Create: `src/main/java/com/beingop/crawler/model/BenchmarkResult.java`

- [ ] **Step 1: Create CrawlTask.java**

```java
package com.beingop.crawler.model;

/**
 * The unit of work placed into the frontier BlockingQueue.
 *
 * WHY a record:
 * Records are immutable value objects — perfect for tasks passed between threads.
 * Immutability eliminates a whole class of concurrency bugs: once a CrawlTask is
 * created, no thread can modify it, so no synchronization is needed to read it.
 *
 * WHY carry depth:
 * Each task knows how deep it is from the seed URL. Workers use this to decide
 * whether to enqueue child links (depth < maxDepth) without needing shared state.
 */
public record CrawlTask(String url, int depth) {}
```

- [ ] **Step 2: Create CrawlResult.java**

```java
package com.beingop.crawler.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * The result of processing one CrawlTask through the full pipeline:
 * fetch → parse → (links extracted).
 *
 * WHY @Builder with toBuilder=true:
 * The CompletableFuture pipeline builds this object in stages — PageFetcher
 * sets the HTTP status and html body, then PageParser adds discoveredLinks.
 * toBuilder=true lets each stage create a new immutable copy with additional
 * fields set, preserving thread safety (no mutation after creation).
 *
 * WHY include html as a field:
 * The raw HTML body is needed by PageParser in the next pipeline stage. It is
 * set to null before storing in the report to avoid holding large strings in memory.
 */
@Data
@Builder(toBuilder = true)
public class CrawlResult {

    private String url;
    private int depth;

    /** HTTP status code. 0 means a network-level error (timeout, DNS failure). */
    private int statusCode;

    /** Raw HTML body. Used only during the pipeline — nulled out before saving to report. */
    private String html;

    private List<String> discoveredLinks;

    /** Non-null only when statusCode == 0 (network error). */
    private String errorMessage;

    private long responseTimeMs;

    /**
     * WHY a derived method instead of a stored boolean:
     * Avoids the state getting out of sync. The truth is always derived from statusCode.
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
```

- [ ] **Step 3: Create CrawlReport.java**

```java
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
```

- [ ] **Step 4: Create BenchmarkResult.java**

```java
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
        return urlCount / (elapsedMs / 1000.0);
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/beingop/crawler/model/
git commit -m "feat: add model classes (CrawlTask, CrawlResult, CrawlReport, BenchmarkResult)"
```

---

## Task 3: StatsCollector

**Files:**
- Create: `src/main/java/com/beingop/crawler/core/StatsCollector.java`
- Create: `src/test/java/com/beingop/crawler/core/StatsCollectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=StatsCollectorTest
```

Expected: `COMPILATION ERROR` — `StatsCollector` does not exist yet.

- [ ] **Step 3: Create StatsCollector.java**

```java
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
     *   frontier.isEmpty() && activeTasks == 0  →  all work is done.
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
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
mvn test -Dtest=StatsCollectorTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/beingop/crawler/core/StatsCollector.java src/test/java/com/beingop/crawler/core/StatsCollectorTest.java
git commit -m "feat: add StatsCollector with concurrent increment tests"
```

---

## Task 4: CrawlFrontier

**Files:**
- Create: `src/main/java/com/beingop/crawler/core/CrawlFrontier.java`
- Create: `src/test/java/com/beingop/crawler/core/CrawlFrontierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.beingop.crawler.core;

import com.beingop.crawler.model.CrawlTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CrawlFrontierTest {

    private CrawlFrontier frontier;

    @BeforeEach
    void setUp() {
        frontier = new CrawlFrontier();
    }

    @Test
    void enqueue_newUrl_returnsTrue() {
        assertTrue(frontier.enqueue("https://example.com/page1", 1));
        assertEquals(1, frontier.size());
    }

    /**
     * This is the most important test: verifies that putIfAbsent correctly
     * deduplicates URLs across concurrent threads. A crawler that visits the
     * same page twice wastes resources and can loop infinitely.
     */
    @Test
    void enqueue_duplicateUrl_returnsFalse() {
        assertTrue(frontier.enqueue("https://example.com/page1", 1));
        assertFalse(frontier.enqueue("https://example.com/page1", 1));
        assertEquals(1, frontier.size());
    }

    @Test
    void seed_addsUrlAtDepthZero() throws InterruptedException {
        frontier.seed("https://example.com");
        CrawlTask task = frontier.poll(1, TimeUnit.SECONDS);
        assertNotNull(task);
        assertEquals("https://example.com", task.url());
        assertEquals(0, task.depth());
    }

    @Test
    void poll_onEmptyQueue_returnsNullAfterTimeout() throws InterruptedException {
        CrawlTask task = frontier.poll(100, TimeUnit.MILLISECONDS);
        assertNull(task);
    }

    @Test
    void visitedCount_tracksAllSeenUrls() {
        frontier.seed("https://example.com");
        frontier.enqueue("https://example.com/a", 1);
        frontier.enqueue("https://example.com/a", 1); // duplicate
        assertEquals(2, frontier.visitedCount()); // seed + one unique enqueue
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=CrawlFrontierTest
```

Expected: `COMPILATION ERROR` — `CrawlFrontier` does not exist yet.

- [ ] **Step 3: Create CrawlFrontier.java**

```java
package com.beingop.crawler.core;

import com.beingop.crawler.model.CrawlTask;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The frontier holds two data structures that work together:
 *   1. LinkedBlockingQueue  — the queue of URLs still to be visited
 *   2. ConcurrentHashMap    — the set of URLs already seen (visited guard)
 *
 * WHY LinkedBlockingQueue (not ArrayBlockingQueue or ConcurrentLinkedQueue):
 * - ArrayBlockingQueue has a fixed capacity — full queue would block producers
 *   even when workers are idle. A crawler doesn't know how many URLs it will find.
 * - ConcurrentLinkedQueue is unbounded and non-blocking but has no blocking take()
 *   — we'd need a busy-wait loop, wasting CPU when the queue is empty but work
 *   might still arrive from in-flight tasks.
 * - LinkedBlockingQueue is unbounded AND supports blocking poll(timeout) — the
 *   orchestrator can wait efficiently without spinning when the queue is momentarily
 *   empty but tasks are still running.
 *
 * WHY ConcurrentHashMap for the visited set (not a Set):
 * Java has no ConcurrentHashSet. The standard pattern is to use a ConcurrentHashMap
 * with dummy Boolean values and rely on putIfAbsent() for atomic check-and-add.
 * putIfAbsent() is a single atomic operation — it is impossible for two threads to
 * both see the map as not containing a URL and both enqueue it. This is the key
 * correctness guarantee.
 */
@Component
public class CrawlFrontier {

    private final LinkedBlockingQueue<CrawlTask> queue   = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Boolean> visited = new ConcurrentHashMap<>();

    /**
     * Seeds the frontier with the starting URL at depth 0.
     * Called once by CrawlerOrchestrator before the crawl begins.
     */
    public void seed(String url) {
        visited.put(url, Boolean.TRUE);
        queue.offer(new CrawlTask(url, 0));
    }

    /**
     * Atomically checks if a URL has been visited and enqueues it if not.
     *
     * WHY putIfAbsent is the right tool:
     * The naive approach — check map → if absent, add to map, add to queue — has a
     * race condition: two threads can both check, both find the URL absent, and both
     * enqueue it. putIfAbsent collapses check-and-set into ONE atomic operation,
     * making it impossible for two threads to both enqueue the same URL.
     *
     * @return true if the URL was new and successfully enqueued, false if duplicate.
     */
    public boolean enqueue(String url, int depth) {
        // putIfAbsent returns null ONLY if the key was absent (i.e., we just inserted it)
        if (visited.putIfAbsent(url, Boolean.TRUE) == null) {
            queue.offer(new CrawlTask(url, depth));
            return true;
        }
        return false;
    }

    /**
     * Retrieves and removes the next task, waiting up to the given timeout.
     * Returns null if no task arrives within the timeout.
     *
     * WHY poll(timeout) over take():
     * take() blocks indefinitely — the orchestrator would hang forever if the queue
     * is empty but tasks are still in-flight (they might add more URLs soon).
     * poll(timeout) lets the orchestrator periodically re-check the isDone() condition
     * even when the queue is temporarily empty.
     */
    public CrawlTask poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public boolean isEmpty()    { return queue.isEmpty(); }
    public int size()           { return queue.size(); }
    public int visitedCount()   { return visited.size(); }

    public void reset() {
        queue.clear();
        visited.clear();
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
mvn test -Dtest=CrawlFrontierTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/beingop/crawler/core/CrawlFrontier.java src/test/java/com/beingop/crawler/core/CrawlFrontierTest.java
git commit -m "feat: add CrawlFrontier with BlockingQueue + ConcurrentHashMap deduplication"
```

---

## Task 5: PageParser

**Files:**
- Create: `src/main/java/com/beingop/crawler/core/PageParser.java`
- Create: `src/test/java/com/beingop/crawler/core/PageParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.beingop.crawler.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PageParserTest {

    private PageParser parser;

    @BeforeEach
    void setUp() {
        parser = new PageParser();
    }

    @Test
    void extractLinks_filtersExternalDomains() {
        String html = "<a href='https://example.com/page1'>internal</a>" +
                      "<a href='https://other.com/page'>external</a>";
        List<String> links = parser.extractLinks(html, "https://example.com", "example.com", 0, 3);
        assertEquals(1, links.size());
        assertEquals("https://example.com/page1", links.get(0));
    }

    @Test
    void extractLinks_returnsEmpty_whenAtMaxDepth() {
        String html = "<a href='https://example.com/page1'>link</a>";
        List<String> links = parser.extractLinks(html, "https://example.com", "example.com", 3, 3);
        assertTrue(links.isEmpty());
    }

    @Test
    void extractLinks_deduplicatesLinks() {
        String html = "<a href='https://example.com/page1'>a</a>" +
                      "<a href='https://example.com/page1'>b</a>";
        List<String> links = parser.extractLinks(html, "https://example.com", "example.com", 0, 3);
        assertEquals(1, links.size());
    }

    @Test
    void extractLinks_stripsAnchorFragments() {
        String html = "<a href='https://example.com/page1#section'>link</a>";
        List<String> links = parser.extractLinks(html, "https://example.com", "example.com", 0, 3);
        assertTrue(links.isEmpty(), "Fragment-only URLs should be excluded to avoid duplicate crawling");
    }

    @Test
    void extractDomain_returnsHost() {
        assertEquals("example.com", parser.extractDomain("https://example.com/path?q=1"));
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=PageParserTest
```

Expected: `COMPILATION ERROR`

- [ ] **Step 3: Create PageParser.java**

```java
package com.beingop.crawler.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Stateless HTML link extractor. Has NO concurrency concerns of its own because:
 * - It takes inputs as parameters and returns new values (no shared state).
 * - Every Virtual Thread calls it with its own local variables.
 * - Stateless components are inherently thread-safe — this is why statelessness
 *   is the first concurrency best practice: eliminate shared mutable state entirely.
 *
 * WHY jsoup for parsing:
 * jsoup handles malformed HTML gracefully (real-world pages are rarely valid HTML),
 * resolves relative URLs to absolute via the baseUri parameter, and provides a
 * CSS-selector API that makes link extraction a one-liner.
 */
@Component
public class PageParser {

    /**
     * Extracts valid, same-domain links from the given HTML body.
     *
     * @param html         Raw HTML content of the fetched page
     * @param baseUrl      Absolute URL of the page (used to resolve relative hrefs)
     * @param domain       Only links whose host matches this domain are returned
     * @param currentDepth Depth of the page being parsed
     * @param maxDepth     Links are not extracted when currentDepth >= maxDepth
     */
    public List<String> extractLinks(String html, String baseUrl,
                                     String domain, int currentDepth, int maxDepth) {
        // WHY return early at maxDepth:
        // Avoids wasting CPU parsing pages whose children will never be crawled.
        if (currentDepth >= maxDepth) {
            return List.of();
        }

        Document doc = Jsoup.parse(html, baseUrl);

        return doc.select("a[href]").stream()
                // absUrl resolves relative hrefs (e.g., "/about") to absolute URLs
                // using the baseUrl we passed to Jsoup.parse()
                .map(el -> el.absUrl("href"))
                .filter(url -> !url.isBlank())
                // Only http/https — skip mailto:, javascript:, ftp:, etc.
                .filter(this::isHttpUrl)
                // Stay within the same domain — the core boundary rule of a polite crawler
                .filter(url -> isSameDomain(url, domain))
                // Strip URLs that are just anchors on the current page (e.g., page.html#section)
                // They resolve to the same page content and would waste a request.
                .filter(url -> !url.contains("#"))
                // Remove duplicates found on the same page (e.g., nav links appearing multiple times)
                .distinct()
                .toList();
    }

    private boolean isHttpUrl(String url) {
        try {
            String scheme = new URI(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private boolean isSameDomain(String url, String domain) {
        try {
            String host = new URI(url).getHost();
            return host != null && host.equalsIgnoreCase(domain);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Extracts the hostname from a full URL.
     * Used by CrawlPipeline and BenchmarkRunner to determine the crawl boundary domain.
     */
    public String extractDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot extract domain from URL: " + url, e);
        }
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
mvn test -Dtest=PageParserTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/beingop/crawler/core/PageParser.java src/test/java/com/beingop/crawler/core/PageParserTest.java
git commit -m "feat: add PageParser with domain filtering and depth limit"
```

---

## Task 6: PageFetcher

**Files:**
- Create: `src/main/java/com/beingop/crawler/core/PageFetcher.java`
- Create: `src/test/java/com/beingop/crawler/core/PageFetcherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.beingop.crawler.core;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.model.CrawlResult;
import com.beingop.crawler.model.CrawlTask;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PageFetcherTest {

    /**
     * Verifies that the Semaphore correctly limits concurrency.
     *
     * HOW: We subclass PageFetcher to intercept the fetch — instead of making
     * a real HTTP call, we track peak concurrency. With a Semaphore of 2 permits
     * and 10 threads, peak concurrent executions should never exceed 2.
     */
    @Test
    void semaphore_limitsConcurrentRequests() throws InterruptedException {
        CrawlerConfig config = new CrawlerConfig();
        config.setMaxConcurrentRequests(2);
        config.setRequestDelayMs(0);

        AtomicInteger currentConcurrent = new AtomicInteger(0);
        AtomicInteger peakConcurrent    = new AtomicInteger(0);

        // Subclass to replace the HTTP call with a concurrency probe
        PageFetcher fetcher = new PageFetcher(config) {
            @Override
            protected CrawlResult doFetch(CrawlTask task) {
                int current = currentConcurrent.incrementAndGet();
                // Track the maximum we've seen
                peakConcurrent.updateAndGet(peak -> Math.max(peak, current));
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                currentConcurrent.decrementAndGet();
                return CrawlResult.builder().url(task.url()).depth(task.depth()).statusCode(200).build();
            }
        };

        int threadCount = 10;
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                fetcher.fetch(new CrawlTask("https://example.com/page" + idx, 0));
                done.countDown();
            }).start();
        }

        done.await(10, TimeUnit.SECONDS);
        assertTrue(peakConcurrent.get() <= 2,
                "Peak concurrent requests was " + peakConcurrent.get() + " but Semaphore limit is 2");
    }

    @Test
    void fetch_returnsErrorResult_onNetworkFailure() {
        CrawlerConfig config = new CrawlerConfig();
        config.setMaxConcurrentRequests(5);
        config.setRequestDelayMs(0);
        config.setSeedUrl("https://this-domain-does-not-exist-xyz.com");

        PageFetcher fetcher = new PageFetcher(config);
        CrawlResult result = fetcher.fetch(new CrawlTask("https://this-domain-does-not-exist-xyz.com", 0));

        assertEquals(0, result.getStatusCode());
        assertNotNull(result.getErrorMessage());
        assertFalse(result.isSuccess());
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=PageFetcherTest
```

Expected: `COMPILATION ERROR`

- [ ] **Step 3: Create PageFetcher.java**

```java
package com.beingop.crawler.core;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.model.CrawlResult;
import com.beingop.crawler.model.CrawlTask;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Makes HTTP requests to fetch page content, enforcing two rate-limiting mechanisms:
 *   1. Semaphore  — caps how many threads are actively making requests simultaneously
 *   2. Fixed delay — introduces a pause before each request (politeness crawl delay)
 *
 * WHY Semaphore over a fixed thread pool for rate limiting:
 * A fixed thread pool limits concurrency at the scheduling level — excess tasks queue
 * up waiting for a thread. A Semaphore limits concurrency at the resource level —
 * threads run freely but must "check out" a permit before touching the shared resource
 * (the target website). This lets Virtual Threads (which are cheap) exist in large
 * numbers while only a bounded number actually make network calls at any moment.
 * It is more granular and composable than a thread pool size limit.
 *
 * WHY ignoreHttpErrors(true) on the jsoup connection:
 * By default, jsoup throws an IOException for HTTP 4xx/5xx responses. We want to
 * record those status codes in the report (they are broken links), not crash the
 * worker thread. ignoreHttpErrors(true) makes jsoup return the response normally,
 * letting us inspect the status code ourselves.
 */
@Component
public class PageFetcher {

    private final Semaphore semaphore;
    private final long requestDelayMs;

    public PageFetcher(CrawlerConfig config) {
        // The number of permits equals the max concurrent requests from config.
        // Fair=false (default) is intentional: fair queuing adds overhead and the
        // order in which waiting threads get permits doesn't matter for a crawler.
        this.semaphore     = new Semaphore(config.getMaxConcurrentRequests());
        this.requestDelayMs = config.getRequestDelayMs();
    }

    /**
     * Fetches a single page. Blocks until a Semaphore permit is available, then
     * waits requestDelayMs before making the HTTP call.
     *
     * WHY semaphore.acquire() is called BEFORE the HTTP call (not after):
     * We want to limit the number of simultaneous in-progress requests. If we
     * acquired after, we'd have unlimited concurrent requests with throttled responses.
     *
     * WHY semaphore.release() MUST be in a finally block:
     * If the HTTP call throws, the catch block returns an error result — but the
     * Semaphore permit must still be released. Without finally, a network error
     * would leak a permit. After enough errors, all permits would be leaked and the
     * crawler would deadlock with zero available permits, hanging forever.
     */
    public CrawlResult fetch(CrawlTask task) {
        long startTime = System.currentTimeMillis();

        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            // Interrupted while waiting for a permit — respect the interrupt and exit cleanly
            Thread.currentThread().interrupt();
            return buildErrorResult(task, "Interrupted while waiting for Semaphore permit", startTime);
        }

        // Semaphore permit is now held — MUST release in finally, no matter what happens next
        try {
            if (requestDelayMs > 0) {
                Thread.sleep(requestDelayMs);
            }
            return doFetch(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildErrorResult(task, "Interrupted during request delay", startTime);
        } finally {
            // Always runs — even if doFetch() throws an unchecked exception
            semaphore.release();
        }
    }

    /**
     * Protected so tests can override this method to probe concurrency behaviour
     * without making real HTTP calls.
     */
    protected CrawlResult doFetch(CrawlTask task) {
        long startTime = System.currentTimeMillis();
        try {
            Connection.Response response = Jsoup.connect(task.url())
                    .ignoreHttpErrors(true)   // don't throw on 4xx/5xx
                    .timeout(10_000)          // 10-second connect+read timeout
                    .execute();

            return CrawlResult.builder()
                    .url(task.url())
                    .depth(task.depth())
                    .statusCode(response.statusCode())
                    .html(response.body())
                    .discoveredLinks(List.of()) // populated later by PageParser
                    .responseTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (IOException e) {
            return buildErrorResult(task, e.getMessage(), startTime);
        }
    }

    private CrawlResult buildErrorResult(CrawlTask task, String error, long startTime) {
        return CrawlResult.builder()
                .url(task.url())
                .depth(task.depth())
                .statusCode(0)
                .errorMessage(error)
                .responseTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
mvn test -Dtest=PageFetcherTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/beingop/crawler/core/PageFetcher.java src/test/java/com/beingop/crawler/core/PageFetcherTest.java
git commit -m "feat: add PageFetcher with Semaphore rate limiting"
```

---

## Task 7: CrawlPipeline

**Files:**
- Create: `src/main/java/com/beingop/crawler/pipeline/CrawlPipeline.java`
- Create: `src/test/java/com/beingop/crawler/pipeline/CrawlPipelineTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.beingop.crawler.pipeline;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.core.CrawlFrontier;
import com.beingop.crawler.core.PageFetcher;
import com.beingop.crawler.core.PageParser;
import com.beingop.crawler.core.StatsCollector;
import com.beingop.crawler.model.CrawlReport;
import com.beingop.crawler.model.CrawlResult;
import com.beingop.crawler.model.CrawlTask;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CrawlPipelineTest {

    @Test
    void submit_successfulFetch_recordsResultAndEnqueuesLinks() throws InterruptedException {
        CrawlerConfig config = new CrawlerConfig();
        config.setSeedUrl("https://example.com");
        config.setMaxDepth(2);
        config.setMaxConcurrentRequests(5);
        config.setRequestDelayMs(0);

        CrawlFrontier frontier   = new CrawlFrontier();
        StatsCollector stats     = new StatsCollector();
        CrawlReport report       = new CrawlReport();
        PageParser parser        = new PageParser();

        // Stub fetcher: returns a successful result with one discovered link
        PageFetcher stubFetcher = new PageFetcher(config) {
            @Override
            protected CrawlResult doFetch(CrawlTask task) {
                return CrawlResult.builder()
                        .url(task.url()).depth(task.depth())
                        .statusCode(200)
                        .html("<a href='https://example.com/child'>link</a>")
                        .build();
            }
        };

        CrawlPipeline pipeline = new CrawlPipeline(stubFetcher, parser, frontier, stats, report, config);
        frontier.seed("https://example.com");

        CrawlTask task = frontier.poll(1, TimeUnit.SECONDS);
        pipeline.submit(task);

        // Wait for the async pipeline to finish
        long deadline = System.currentTimeMillis() + 5000;
        while (!pipeline.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(1, stats.get(StatsCollector.URLS_VISITED));
        assertEquals(1, report.getResults().size());
        assertTrue(report.getResults().get(0).isSuccess());
        // Child link should have been enqueued
        assertEquals(1, frontier.size());
    }

    @Test
    void submit_failedFetch_recordsErrorAndDoesNotEnqueueLinks() throws InterruptedException {
        CrawlerConfig config = new CrawlerConfig();
        config.setSeedUrl("https://example.com");
        config.setMaxDepth(2);
        config.setMaxConcurrentRequests(5);
        config.setRequestDelayMs(0);

        CrawlFrontier frontier = new CrawlFrontier();
        StatsCollector stats   = new StatsCollector();
        CrawlReport report     = new CrawlReport();
        PageParser parser      = new PageParser();

        PageFetcher stubFetcher = new PageFetcher(config) {
            @Override
            protected CrawlResult doFetch(CrawlTask task) {
                return CrawlResult.builder()
                        .url(task.url()).depth(task.depth())
                        .statusCode(404)
                        .html("")
                        .build();
            }
        };

        CrawlPipeline pipeline = new CrawlPipeline(stubFetcher, parser, frontier, stats, report, config);
        frontier.seed("https://example.com");
        CrawlTask task = frontier.poll(1, TimeUnit.SECONDS);
        pipeline.submit(task);

        long deadline = System.currentTimeMillis() + 5000;
        while (!pipeline.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(1, stats.get(StatsCollector.URLS_FAILED));
        assertEquals(0, frontier.size());
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=CrawlPipelineTest
```

Expected: `COMPILATION ERROR`

- [ ] **Step 3: Create CrawlPipeline.java**

```java
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

        // newVirtualThreadPerTaskExecutor: creates a new Virtual Thread for EVERY submitted task.
        // Unlike a thread pool, there is no reuse — each task gets a fresh Virtual Thread.
        // This is safe because Virtual Threads are so cheap (~few KB of memory each) that
        // creating thousands of them has negligible cost compared to Platform Threads (~1MB each).
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
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
            // thenApply runs on the same thread as the previous stage (Virtual Thread)
            .thenApply(fetchResult -> {
                List<String> links = parser.extractLinks(
                        fetchResult.getHtml() != null ? fetchResult.getHtml() : "",
                        task.url(), domain, task.depth(), config.getMaxDepth()
                );
                // Build an updated CrawlResult with links set, html cleared to free memory
                return fetchResult.toBuilder()
                        .discoveredLinks(links)
                        .html(null) // discard raw HTML — not needed after parsing
                        .build();
            })

            // Stage 3: Record result and enqueue discovered links
            .thenAccept(result -> {
                report.addResult(result);

                if (result.isSuccess()) {
                    stats.increment(StatsCollector.URLS_VISITED);
                    // Offer each discovered link to the frontier (CrawlFrontier handles deduplication)
                    result.getDiscoveredLinks()
                          .forEach(url -> frontier.enqueue(url, task.depth() + 1));
                } else {
                    stats.increment(StatsCollector.URLS_FAILED);
                }
            })

            // Stage 4: Error recovery — if any stage throws an unchecked exception, log it
            .exceptionally(ex -> {
                stats.increment(StatsCollector.URLS_FAILED);
                System.err.printf("[ERROR] Pipeline failed for %s: %s%n", task.url(), ex.getMessage());
                return null;
            })

            // Stage 5: Always runs — decrement active counter so isDone() works correctly
            // whenComplete runs whether the pipeline succeeded or threw an exception
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
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
mvn test -Dtest=CrawlPipelineTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/beingop/crawler/pipeline/CrawlPipeline.java src/test/java/com/beingop/crawler/pipeline/CrawlPipelineTest.java
git commit -m "feat: add CrawlPipeline with CompletableFuture + VirtualThreadPerTaskExecutor"
```

---

## Task 8: BenchmarkRunner

**Files:**
- Create: `src/main/java/com/beingop/crawler/benchmark/BenchmarkRunner.java`

- [ ] **Step 1: Create BenchmarkRunner.java**

```java
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
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/beingop/crawler/benchmark/BenchmarkRunner.java
git commit -m "feat: add BenchmarkRunner with ForkJoinPool and RecursiveTask"
```

---

## Task 9: ConsoleReporter + FileReporter

**Files:**
- Create: `src/main/java/com/beingop/crawler/report/ConsoleReporter.java`
- Create: `src/main/java/com/beingop/crawler/report/FileReporter.java`

- [ ] **Step 1: Create ConsoleReporter.java**

```java
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
```

- [ ] **Step 2: Create FileReporter.java**

```java
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
```

- [ ] **Step 3: Compile**

```bash
mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/beingop/crawler/report/
git commit -m "feat: add ConsoleReporter and FileReporter with ReadWriteLock"
```

---

## Task 10: CrawlerOrchestrator

**Files:**
- Create: `src/main/java/com/beingop/crawler/core/CrawlerOrchestrator.java`

- [ ] **Step 1: Create CrawlerOrchestrator.java**

```java
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
```

- [ ] **Step 2: Compile**

```bash
mvn compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/beingop/crawler/core/CrawlerOrchestrator.java
git commit -m "feat: add CrawlerOrchestrator with Phaser-based 3-phase coordination"
```

---

## Task 11: Wire Up + Integration Smoke Test

**Files:**
- Modify: `src/main/java/com/beingop/multithreading_concurrency_project_1/MultithreadingConcurrencyProject1Application.java`
- Create: `src/test/java/com/beingop/crawler/CrawlerIntegrationTest.java`

- [ ] **Step 1: Update the main application class**

```java
package com.beingop.multithreading_concurrency_project_1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point. @SpringBootApplication enables:
 *   - @ComponentScan: finds all @Component classes under com.beingop.*
 *   - @EnableAutoConfiguration: configures Spring based on classpath
 *
 * The actual crawl logic lives in CrawlerOrchestrator (CommandLineRunner),
 * which Spring calls automatically after the context is ready.
 */
@SpringBootApplication(scanBasePackages = "com.beingop")
public class MultithreadingConcurrencyProject1Application {

    public static void main(String[] args) {
        SpringApplication.run(MultithreadingConcurrencyProject1Application.class, args);
    }
}
```

- [ ] **Step 2: Write the integration smoke test**

```java
package com.beingop.crawler;

import com.beingop.crawler.config.CrawlerConfig;
import com.beingop.crawler.core.CrawlFrontier;
import com.beingop.crawler.core.PageFetcher;
import com.beingop.crawler.core.PageParser;
import com.beingop.crawler.core.StatsCollector;
import com.beingop.crawler.model.CrawlReport;
import com.beingop.crawler.model.CrawlResult;
import com.beingop.crawler.model.CrawlTask;
import com.beingop.crawler.pipeline.CrawlPipeline;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test using a stub fetcher — no real HTTP calls, no network dependency.
 * Verifies all components work together correctly.
 */
class CrawlerIntegrationTest {

    @Test
    void fullPipeline_crawlsTwoPagesAndStops() throws InterruptedException {
        CrawlerConfig config = new CrawlerConfig();
        config.setSeedUrl("https://example.com");
        config.setMaxDepth(1);
        config.setMaxConcurrentRequests(5);
        config.setRequestDelayMs(0);

        CrawlFrontier  frontier = new CrawlFrontier();
        StatsCollector stats    = new StatsCollector();
        CrawlReport    report   = new CrawlReport();
        PageParser     parser   = new PageParser();

        // Stub: seed page returns one child link; child page returns no links
        PageFetcher stubFetcher = new PageFetcher(config) {
            @Override
            protected CrawlResult doFetch(CrawlTask task) {
                if (task.url().equals("https://example.com")) {
                    return CrawlResult.builder()
                            .url(task.url()).depth(task.depth()).statusCode(200)
                            .html("<a href='https://example.com/child'>child</a>")
                            .build();
                }
                return CrawlResult.builder()
                        .url(task.url()).depth(task.depth()).statusCode(200)
                        .html("").discoveredLinks(List.of())
                        .build();
            }
        };

        CrawlPipeline pipeline = new CrawlPipeline(stubFetcher, parser, frontier, stats, report, config);
        frontier.seed("https://example.com");

        // Simulate the orchestrator's crawl loop
        long deadline = System.currentTimeMillis() + 10_000;
        while (!pipeline.isDone() && System.currentTimeMillis() < deadline) {
            CrawlTask task = frontier.poll(100, TimeUnit.MILLISECONDS);
            if (task != null) pipeline.submit(task);
        }

        assertTrue(pipeline.isDone(), "Crawl did not complete within 10 seconds");
        assertEquals(2, stats.get(StatsCollector.URLS_VISITED));
        assertEquals(2, report.getResults().size());
        assertEquals(0, stats.get(StatsCollector.URLS_FAILED));

        pipeline.shutdown();
    }
}
```

- [ ] **Step 3: Run all tests**

```bash
mvn test
```

Expected: All test classes pass. `BUILD SUCCESS`.

- [ ] **Step 4: Run the application against a real site**

In `application.yaml`, set:
```yaml
crawler:
  seed-url: https://example.com
  max-depth: 1
  benchmark-enabled: false
```

Then run:
```bash
mvn spring-boot:run
```

Expected output:
```
[PHASE 0] Crawling https://example.com (max depth: 1) with Virtual Threads...
[CRAWL]  Visited: ...
[PHASE 0] Complete.
[PHASE 2] Generating reports...
[PHASE 2] File report saved to: crawl-report.txt
```

- [ ] **Step 5: Final commit**

```bash
git add src/main/java/com/beingop/multithreading_concurrency_project_1/MultithreadingConcurrencyProject1Application.java src/test/java/com/beingop/crawler/CrawlerIntegrationTest.java
git commit -m "feat: wire application entry point and add integration smoke test"
```

---

## Self-Review Checklist

- **Spec coverage:**
  - BlockingQueue frontier: Task 4 (CrawlFrontier)
  - ConcurrentHashMap visited set: Task 4
  - VirtualThreadPerTaskExecutor: Task 7 (CrawlPipeline)
  - CompletableFuture chain: Task 7
  - Semaphore rate limiting: Task 6 (PageFetcher)
  - Configurable delay: Task 6
  - Phaser coordination: Task 10 (CrawlerOrchestrator)
  - ForkJoinPool + RecursiveTask benchmark: Task 8
  - AtomicInteger counters: Task 3 (StatsCollector)
  - ReadWriteLock file write: Task 9 (FileReporter)
  - Console output: Task 9 (ConsoleReporter)
  - File output: Task 9 (FileReporter)
  - application.yaml config: Task 1
  - Educational comments: every class in every task
- **No placeholders:** verified — every step has complete code
- **Type consistency:** `CrawlTask(String url, int depth)`, `CrawlResult` with `@Builder(toBuilder=true)`, `BenchmarkResult(int urlCount, long elapsedMs)`, `StatsCollector.URLS_VISITED/URLS_FAILED` constants used consistently across all tasks
