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
     * We subclass PageFetcher to intercept the fetch — instead of making
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

        PageFetcher fetcher = new PageFetcher(config) {
            @Override
            protected CrawlResult doFetch(CrawlTask task) {
                int current = currentConcurrent.incrementAndGet();
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

        PageFetcher fetcher = new PageFetcher(config);
        CrawlResult result = fetcher.fetch(new CrawlTask("https://this-domain-does-not-exist-xyz-abc.com", 0));

        assertEquals(0, result.getStatusCode());
        assertNotNull(result.getErrorMessage());
        assertFalse(result.isSuccess());
    }
}
