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
