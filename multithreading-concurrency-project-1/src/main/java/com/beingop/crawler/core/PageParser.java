package com.beingop.crawler.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Stateless HTML link extractor. Has NO concurrency concerns of its own because:
 * - It takes inputs as parameters and returns new values (no shared state).
 * - Every Virtual Thread calls it with its own local variables.
 * - Stateless components are inherently thread-safe — this is why statelessness
 *   is the first concurrency best practice: eliminate shared mutable state entirely.
 *
 * WHY jsoup for parsing:
 * jsoup handles malformed HTML gracefully (real-world pages are rarely valid HTML),
 * resolves relative URLs to absolute via the baseUri parameter, and provides a
 * CSS-selector API that makes link extraction a one-liner.
 */
@Component
public class PageParser {

    /**
     * Extracts valid, same-domain links from the given HTML body.
     *
     * @param html         Raw HTML content of the fetched page
     * @param baseUrl      Absolute URL of the page (used to resolve relative hrefs)
     * @param domain       Only links whose host matches this domain are returned
     * @param currentDepth Depth of the page being parsed
     * @param maxDepth     Links are not extracted when currentDepth >= maxDepth
     */
    public List<String> extractLinks(String html, String baseUrl,
                                     String domain, int currentDepth, int maxDepth) {
        // WHY return early at maxDepth:
        // Avoids wasting CPU parsing pages whose children will never be crawled.
        if (currentDepth >= maxDepth) {
            return List.of();
        }

        Document doc = Jsoup.parse(html, baseUrl);

        return doc.select("a[href]").stream()
                // absUrl resolves relative hrefs (e.g., "/about") to absolute URLs
                // using the baseUrl we passed to Jsoup.parse()
                .map(el -> el.absUrl("href"))
                .filter(url -> !url.isBlank())
                // Only http/https — skip mailto:, javascript:, ftp:, etc.
                .filter(this::isHttpUrl)
                // Stay within the same domain — the core boundary rule of a polite crawler
                .filter(url -> isSameDomain(url, domain))
                // Strip URLs that are just anchors on the current page (e.g., page.html#section)
                // They resolve to the same page content and would waste a request.
                .filter(url -> !url.contains("#"))
                // Remove duplicates found on the same page (e.g., nav links appearing multiple times)
                .distinct()
                .toList();
    }

    private boolean isHttpUrl(String url) {
        try {
            String scheme = new URI(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private boolean isSameDomain(String url, String domain) {
        try {
            String host = new URI(url).getHost();
            return host != null && host.equalsIgnoreCase(domain);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Extracts the hostname from a full URL.
     * Used by CrawlPipeline and BenchmarkRunner to determine the crawl boundary domain.
     */
    public String extractDomain(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot extract domain from URL: " + url, e);
        }
    }
}
