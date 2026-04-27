package com.beingop.crawler.model;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CrawlReportTest {

    @Test
    void addResult_concurrentWrites_noEntriesLost() throws InterruptedException {
        CrawlReport report = new CrawlReport();
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    start.await();
                    report.addResult(CrawlResult.builder()
                            .url("https://example.com/page" + idx)
                            .depth(1).statusCode(200).build());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(threadCount, report.getResults().size());
    }

    @Test
    void getBrokenLinks_returnsOnlyNon2xx() {
        CrawlReport report = new CrawlReport();
        report.addResult(CrawlResult.builder().url("a").statusCode(200).build());
        report.addResult(CrawlResult.builder().url("b").statusCode(404).build());
        report.addResult(CrawlResult.builder().url("c").statusCode(500).build());
        assertEquals(2, report.getBrokenLinks().size());
    }
}
