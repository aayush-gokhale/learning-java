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
