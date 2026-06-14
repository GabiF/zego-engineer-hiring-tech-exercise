package com.crawler.core.config;

import java.net.URI;
import java.time.Duration;

/**
 * Immutable configuration for a single crawl run, assembled from CLI arguments.
 *
 * @param seed                      the starting seed URL
 * @param maxConcurrentRequests     the maximum number of concurrent HTTP requests to make (Semaphore permits)
 * @param requestTimeout            the connect/read timeout for each HTTP request
 * @param userAgent                 the @{code User-Agent} header to send in HTTP requests
 * @param maxPages                  the maximum number of pages to crawl (0 = unlimited)
 * @param respectRobotsTxt          whether to honour robots.txt
 * @param politenessDelay           the delay between requests, in milliseconds
 */
public record CrawlerConfig(
        URI seed,
        int maxConcurrentRequests,
        Duration requestTimeout,
        String userAgent,
        int maxPages,
        boolean respectRobotsTxt,
        Duration politenessDelay
) {
}

