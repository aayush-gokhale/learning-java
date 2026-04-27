package com.beingop.crawler.model;

import lombok.Builder;
import lombok.Value;
import java.util.List;

/**
 * The result of processing one CrawlTask through the full pipeline:
 * fetch → parse → (links extracted).
 *
 * WHY @Builder with toBuilder=true:
 * The CompletableFuture pipeline builds this object in stages — PageFetcher
 * sets the HTTP status and html body, then PageParser adds discoveredLinks.
 * toBuilder=true lets each stage create a new immutable copy with additional
 * fields set, preserving thread safety (no mutation after creation).
 *
 * WHY include html as a field:
 * The raw HTML body is needed by PageParser in the next pipeline stage. It is
 * set to null before storing in the report to avoid holding large strings in memory.
 */
@Value
@Builder(toBuilder = true)
public class CrawlResult {

    String url;
    int depth;

    /** HTTP status code. 0 means a network-level error (timeout, DNS failure). */
    int statusCode;

    /** Raw HTML body. Used only during the pipeline — nulled out before saving to report. */
    String html;

    List<String> discoveredLinks;

    /** Non-null only when statusCode == 0 (network error). */
    String errorMessage;

    long responseTimeMs;

    /**
     * WHY a derived method instead of a stored boolean:
     * Avoids the state getting out of sync. The truth is always derived from statusCode.
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
