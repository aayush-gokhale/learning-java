# High-Concurrency Web Crawler — Design Spec
**Date:** 2026-04-27
**Project:** multithreading-concurrency-project-1
**Stack:** Java 25, Spring Boot 4.0.6, jsoup 1.17.2, Lombok

---

## 1. Goal

Build a real-world, production-quality web crawler that serves as a hands-on learning vehicle for every major Java concurrency concept. The crawler must:

- Start from a seed URL, stay within the same domain, and respect a configurable max depth
- Use Virtual Threads for I/O-bound crawling and compare their performance against Platform Threads (ForkJoinPool)
- Apply rate limiting via a Semaphore and a configurable per-request delay
- Produce console output (live stats + benchmark comparison) and a file-based crawl report
- Include in-depth educational comments on every class and method explaining the *why* behind each concurrency decision

---

## 2. Package Structure

```
com.beingop.crawler
├── config/
│   └── CrawlerConfig.java          ← Spring @ConfigurationProperties from application.yaml
├── model/
│   ├── CrawlTask.java              ← A URL + its current depth (unit of work in the queue)
│   ├── CrawlResult.java            ← Result of one page fetch (status, links found, errors)
│   └── CrawlReport.java            ← Final aggregated report (all results combined)
├── core/
│   ├── CrawlerOrchestrator.java    ← Phaser-based phase controller, main entry point
│   ├── CrawlFrontier.java          ← BlockingQueue frontier + ConcurrentHashMap visited set
│   ├── PageFetcher.java            ← HTTP fetch via jsoup, guarded by Semaphore + delay
│   └── PageParser.java             ← Extracts links from HTML, filters by domain and depth
├── pipeline/
│   └── CrawlPipeline.java          ← CompletableFuture chain: fetch → parse → enqueue
├── benchmark/
│   └── BenchmarkRunner.java        ← ForkJoinPool + RecursiveTask (platform thread re-crawl)
├── report/
│   ├── StatsCollector.java         ← ConcurrentHashMap<String, AtomicInteger> live stats
│   ├── ConsoleReporter.java        ← Prints formatted stats table to console
│   └── FileReporter.java           ← Writes crawl-report.txt, guarded by ReadWriteLock
└── MultithreadingConcurrencyProject1Application.java
```

---

## 3. Concurrency Concept Map

Every concurrency concept used in this project maps to a specific real-world problem it solves:

| Concept | Class | Problem It Solves |
|---|---|---|
| `BlockingQueue` (Producer-Consumer) | `CrawlFrontier` | Decouples URL discovery from URL processing; blocks producer when queue is empty |
| `ConcurrentHashMap` | `CrawlFrontier`, `StatsCollector` | Thread-safe visited URL tracking and live counters without explicit locking |
| `VirtualThreadPerTaskExecutor` | `CrawlPipeline` | Spawns thousands of cheap threads for I/O-bound HTTP fetches without exhausting CPU |
| `CompletableFuture` | `CrawlPipeline` | Chains fetch → parse → enqueue as non-blocking async stages; avoids callback hell |
| `Semaphore` | `PageFetcher` | Caps concurrent HTTP requests to a target site (e.g., max 10 at once) |
| `ReentrantReadWriteLock` | `FileReporter` | Allows many threads to read crawl stats concurrently; exclusive lock only for file write |
| `Phaser` | `CrawlerOrchestrator` | Synchronizes 3 distinct phases (Crawl → Benchmark → Report) with dynamic party registration |
| `ForkJoinPool` + `RecursiveTask` | `BenchmarkRunner` | Divides the crawl work recursively across platform threads for performance comparison |
| `AtomicInteger` | `StatsCollector` | Lock-free, CAS-based increment for high-frequency counters (URLs visited, errors) |
| `AtomicInteger` (active task counter) | `CrawlPipeline` | Tracks in-flight CompletableFuture tasks; crawl ends when counter hits 0 AND queue is empty (CountDownLatch can't do this — it needs a fixed count at creation, but new tasks are discovered dynamically) |
| Thread lifecycle management | `CrawlerOrchestrator` | Graceful startup, running, and shutdown of all thread pools and executors |
| Inter-thread communication | `CrawlFrontier` | Producer blocks on empty queue via `take()`; workers unblock it by calling `put()` |

---

## 4. Configuration (`application.yaml`)

All behaviour is driven by configuration. No hardcoded values in any class.

```yaml
crawler:
  seed-url: https://wikipedia.org   # Starting URL for the crawl
  max-depth: 3                      # Maximum link depth from seed (0 = seed only)
  max-concurrent-requests: 10       # Semaphore permits (max simultaneous HTTP requests)
  request-delay-ms: 200             # Politeness delay between requests per thread (ms)
  output-file: crawl-report.txt     # Path to the file-based crawl report
  benchmark-enabled: true           # Whether to run the Platform Thread benchmark (Phase 1)
```

---

## 5. Data Flow

### Phase 0 — CRAWL (Virtual Threads)

```
App starts
  └─► CrawlerOrchestrator initializes Phaser(1), seeds CrawlFrontier with root URL at depth 0

Producer loop (main coordinating thread):
  └─► Pulls CrawlTask from BlockingQueue (blocks if empty)
  └─► Checks: is URL already visited? (ConcurrentHashMap.putIfAbsent)
  └─► Acquires Semaphore permit (blocks if max concurrent requests reached)
  └─► Submits CompletableFuture chain to VirtualThreadPerTaskExecutor:

      fetchPage()     → jsoup HTTP GET, releases Semaphore permit in finally block
          │
      parsePage()     → extracts all <a href> links from HTML
          │             filters: same domain only, depth <= max-depth
          │
      enqueueLinks()  → offers valid new URLs back to BlockingQueue as CrawlTask(depth+1)
          │
      recordStats()   → increments AtomicInteger counters in StatsCollector

Completion detection (AtomicInteger active counter):
  - Incremented before each CompletableFuture task is submitted
  - Decremented in the task's finally block when it completes
  - Main thread polls: queue.isEmpty() && activeCounter.get() == 0 → crawl done
  - (A fixed CountDownLatch cannot be used here because tasks are discovered dynamically;
     we don't know the total task count upfront)

Phaser.arriveAndAwaitAdvance() → Phase 0 complete, advance to Phase 1
```

### Phase 1 — BENCHMARK (Platform Threads)

```
BenchmarkRunner.run():
  └─► Creates a ForkJoinPool sized to available CPU cores
  └─► Submits CrawlRecursiveTask (extends RecursiveTask) for the seed URL
  └─► RecursiveTask fetches page, forks subtasks for each discovered link
  └─► Records wall-clock time and total URLs crawled
  └─► Compares results against Phase 0 (Virtual Thread) metrics

Phaser.arriveAndAwaitAdvance() → Phase 1 complete, advance to Phase 2
```

### Phase 2 — REPORT

```
ConsoleReporter.print():
  └─► Prints: total URLs crawled, errors, depth distribution, response time avg
  └─► Prints: benchmark table — Virtual Threads vs Platform Threads (time, throughput)

FileReporter.write():
  └─► Acquires WriteLock (ReentrantReadWriteLock)
  └─► Writes crawl-report.txt: all visited URLs, broken links (4xx/5xx), depth map
  └─► Releases WriteLock

Phaser.arriveAndDeregister() → crawl complete
```

---

## 6. Error Handling

| Error Type | Handling Strategy |
|---|---|
| HTTP 4xx / 5xx responses | Recorded in StatsCollector as broken links with status code. Worker continues. |
| Network timeout | jsoup configured with connect/read timeout. Caught, URL marked as failed, worker continues. |
| Malformed / relative URLs | Caught during `parsePage()`. Silently skipped, never enqueued. |
| `InterruptedException` | All blocking calls (`BlockingQueue.take()`, `Semaphore.acquire()`) restore interrupt flag and shut down cleanly. |
| Crawl termination | Frontier empty AND `AtomicInteger` active counter at zero → natural termination, no forced shutdown needed. |
| Benchmark disabled | If `crawler.benchmark-enabled=false`, Phase 1 is skipped entirely; Phaser advances automatically. |

---

## 7. Output

### Console Output (live + summary)
```
[CRAWL]  Visited: 142 | Errors: 3 | In-flight: 7 | Depth: 2/3
[DONE]   Total URLs: 142 | Broken: 3 | Time: 4.2s

--- BENCHMARK RESULTS ---
Mode               | URLs Crawled | Time    | Throughput
Virtual Threads    | 142          | 4.2s    | 33.8 URLs/s
Platform Threads   | 142          | 11.7s   | 12.1 URLs/s
```

### File Output (`crawl-report.txt`)
- Full list of visited URLs with depth and HTTP status
- Broken links section (URL + error/status code)
- Depth distribution map
- Benchmark comparison table

---

## 8. Educational Comments Strategy

Every class and method will include:
- **Class-level Javadoc:** Which concurrency concept this class demonstrates and why it was chosen over alternatives
- **Method-level comments:** What the critical section is, why a specific lock/queue/future was used
- **Inline comments on non-obvious lines:** Explaining subtle concurrency behaviour (e.g., why `putIfAbsent` is atomic, why `Semaphore` must be released in `finally`)

No comments on obvious code (getters, simple assignments). Comments are for the *why*, not the *what*.
