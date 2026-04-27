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

        CrawlFrontier  frontier = new CrawlFrontier();
        StatsCollector stats    = new StatsCollector();
        CrawlReport    report   = new CrawlReport();
        PageParser     parser   = new PageParser();

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

        long deadline = System.currentTimeMillis() + 5000;
        while (!pipeline.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(1, stats.get(StatsCollector.URLS_VISITED));
        assertEquals(1, report.getResults().size());
        assertTrue(report.getResults().get(0).isSuccess());
        assertEquals(1, frontier.size()); // child link enqueued
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
