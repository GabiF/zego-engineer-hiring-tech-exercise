package com.crawler.core;

import com.crawler.core.config.CrawlerConfig;
import com.crawler.core.model.CrawlResult;
import com.crawler.core.model.CrawlSummary;
import com.crawler.fetch.model.FetchResponse;
import com.crawler.fetch.Fetcher;
import com.crawler.output.ResultSink;
import com.crawler.parse.LinkExtractor;
import com.crawler.url.Scope;
import com.crawler.url.UrlNormalizer;

import java.net.URI;
import java.util.*;

public class SingleThreadedCrawler implements Crawler {

    private final CrawlerConfig config;
    private final Fetcher fetcher;
    private final LinkExtractor extractor;
    private final UrlNormalizer normalizer;
    private final Scope scope;
    private final ResultSink sink;

    private final Deque<URI> frontier = new ArrayDeque<>(); // FIFO: addLast / pollFirst -> breadth-first search
    private final Set<URI> visited = new HashSet<>();       // canonical URLs already claimed
    private int pagesFetched = 0;                           // for the maxPages cap

    private int pagesCrawled = 0;
    private int skipped = 0;
    private int failed = 0;

    /**
     * Stores the collaborators; performs no crawling itself. The crawl() method must be called to start the crawl.
     */
    public SingleThreadedCrawler(CrawlerConfig config, Fetcher fetcher, LinkExtractor extractor, UrlNormalizer normalizer, Scope scope, ResultSink sink) {
        this.config = config;
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.normalizer = normalizer;
        this.scope = scope;
        this.sink = sink;
    }

    /**
     * Seeds the frontier with {@code config.seed}, then drains it until empty.
     * Returns when there is no more work. Overrides Crawler.crawl() but can drop the
     * {@code throws InterruptedException} clause since this implementation is single-threaded and does not block.
     */
    @Override
    public CrawlSummary crawl() {
        try {
            Optional<URI> normalizedSeedOptional = normalizer.normalize(config.seed());

            if (normalizedSeedOptional.isPresent()) {
                URI  normalizedSeed = normalizedSeedOptional.get();
                visited.add(normalizedSeed);
                frontier.addLast(normalizedSeed);

                while (!frontier.isEmpty() && (config.maxPages() == 0 || pagesFetched < config.maxPages())) {
                    final URI next = frontier.pollFirst();
                    pagesFetched++;

                    process(next);
                }

                return new CrawlSummary(pagesCrawled, skipped, failed);
            } else {
                failed++;
                System.err.println("Seed URL could not be normalized: " + config.seed());

                return new CrawlSummary(pagesCrawled, skipped, failed);
            }
        } catch (Exception e) {
            System.err.println("Unexpected error occurred: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * The enqueue gate every candidate URL passes through exactly once:
     * normalize -> in scope? -> claim in {@code visited} -> add to {@code frontier}
     */
    private void enqueue(URI candidate) {
        normalizer.normalize(candidate)
                .filter(scope::inScope)
                .filter(visited::add)
                .ifPresent(frontier::addLast);
    }

    /**
     * Method that is executed for each URL from the frontier.
     * Fetches the page for the given URL
     * -> ensures it is HTML; skips if not
     * -> records page crawled
     * -> extracts all URLs found on that page
     * -> reports to the sink (text or json)
     * -> enqueues each URL found on the page (executes gate filtering process)
     *
     * @param url the URL to process
     */
    private void process(URI url) {
        final FetchResponse fetchResponse = fetcher.fetch(url);

        switch (fetchResponse) {
            case FetchResponse.Html html -> {
                pagesCrawled++;
                final List<URI> pageLinks = extractor.extract(html.body(), html.finalUrl());

                sink.accept(new CrawlResult(html.finalUrl(), pageLinks));

                for (URI link : pageLinks) {
                    enqueue(link);
                }
            }
            case FetchResponse.Skipped s -> {
                skipped++;
                System.err.println("Skipped URL: " + s.finalUrl() + " (content-type: " + s.contentType() + ")");
            }
            case FetchResponse.Failed f -> {
                failed++;
                System.err.println("Failed URL: " + f.url() + " (reason: " + f.reason() + ")");
            }
        }
    }

    int getPagesFetched() {
        return pagesFetched;
    }
}
