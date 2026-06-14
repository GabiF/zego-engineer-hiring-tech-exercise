package com.crawler.core.model;

/**
 * Outcome counts for one crawl run, returned by {@link com.crawler.core.Crawler#crawl()}.
 *
 * @param pagesCrawled successfully fetched in-scope HTML pages reported to the sink
 * @param skipped      2xx responses that were not HTML and thus not parsed
 * @param failed       non-2xx responses, timeouts and transport errors
 */
public record CrawlSummary(int pagesCrawled, int skipped, int failed) {
}
