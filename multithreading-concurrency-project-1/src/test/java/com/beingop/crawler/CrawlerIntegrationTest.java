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
