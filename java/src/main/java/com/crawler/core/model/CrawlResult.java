package com.crawler.core.model;

import java.net.URI;
import java.util.List;

/**
 * The output unit for one crawled pge: the page's URL and every link found on it.
 *
 * <p>NOTE: @{code links} contains all discovered links (including off-domain ones), per the brief.
 * Only in-scope links are followed for further crawling.
 *
 * @param pageUrl
 * @param links
 */
public record CrawlResult(URI pageUrl, List<URI> links) {}

