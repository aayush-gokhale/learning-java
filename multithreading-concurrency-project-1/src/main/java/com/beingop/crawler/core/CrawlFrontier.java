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
 * - ConcurrentLinkedQueue is unbounded and non-blocking but has no blocking poll()
 *   with timeout — we'd need a busy-wait loop, wasting CPU when the queue is empty
 *   but work might still arrive from in-flight tasks.
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
