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
        // Fair=false (default) is intentional: fair queuing adds overhead and the
        // order in which waiting threads get permits doesn't matter for a crawler.
        this.semaphore      = new Semaphore(config.getMaxConcurrentRequests());
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
                    .ignoreHttpErrors(true)
                    .timeout(10_000)
                    .execute();

            return CrawlResult.builder()
                    .url(task.url())
                    .depth(task.depth())
                    .statusCode(response.statusCode())
                    .html(response.body())
                    .discoveredLinks(List.of())
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
