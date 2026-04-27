package com.beingop.crawler.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CrawlResultTest {

    @Test
    void isSuccess_returnsTrueFor2xx() {
        assertTrue(CrawlResult.builder().statusCode(200).build().isSuccess());
        assertTrue(CrawlResult.builder().statusCode(201).build().isSuccess());
        assertTrue(CrawlResult.builder().statusCode(299).build().isSuccess());
    }

    @Test
    void isSuccess_returnsFalseForNon2xx() {
        assertFalse(CrawlResult.builder().statusCode(199).build().isSuccess());
        assertFalse(CrawlResult.builder().statusCode(300).build().isSuccess());
        assertFalse(CrawlResult.builder().statusCode(404).build().isSuccess());
        assertFalse(CrawlResult.builder().statusCode(0).build().isSuccess());
    }

    @Test
    void toBuilder_createsNewInstanceWithUpdatedFields() {
        CrawlResult original = CrawlResult.builder()
                .url("https://example.com").depth(0).statusCode(200).html("<html/>").build();
        CrawlResult updated = original.toBuilder().html(null).build();
        assertNotNull(original.getHtml());
        assertNull(updated.getHtml());
        assertEquals("https://example.com", updated.getUrl()); // copied from original
    }
}
