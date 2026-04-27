package com.beingop.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds all values under the "crawler" prefix in application.yaml to this object.
 *
 * WHY @ConfigurationProperties over @Value:
 * @Value injects one property at a time — scattered across classes, hard to see
 * all config in one place. @ConfigurationProperties binds an entire namespace into
 * a single typed object, making the configuration surface explicit and testable.
 *
 * WHY @Component here:
 * Makes Spring manage this bean so it can be injected everywhere without
 * needing @EnableConfigurationProperties on the main class.
 */
@Component
@ConfigurationProperties(prefix = "crawler")
@Data
public class CrawlerConfig {

    private String seedUrl = "https://wikipedia.org";
    private int maxDepth = 2;
    private int maxConcurrentRequests = 10;
    private long requestDelayMs = 200;
    private String outputFile = "crawl-report.txt";
    private boolean benchmarkEnabled = true;
}
