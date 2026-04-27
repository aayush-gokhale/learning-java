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
