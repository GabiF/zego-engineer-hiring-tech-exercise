package com.crawler.core;

import com.crawler.core.model.CrawlResult;
import com.crawler.core.model.CrawlSummary;

public interface Crawler {

    /**
     * Crawls from the configured seed until the in-scope frontier is exhausted, emitting a
     * {@link CrawlResult} for every successfully fetched HTML page.
     *
     * @throws InterruptedException if interrupted while waiting for in-flight work to finish
     */
    CrawlSummary crawl() throws InterruptedException;
}

