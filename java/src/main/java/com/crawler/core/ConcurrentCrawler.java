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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Concurrent crawl engine using <strong>one virtual thread per claimed URL</strong>.
 *
 * <p>Every URL passes through {@link #submit(URI)} exactly once: it is normalized, scope-checked, and
 * atomically claimed in the {@code visited} set. A {@link Semaphore} bounds the number of concurrent
 * HTTP requests (decoupled from the thread count); a {@code pending} counter plus a {@link CountDownLatch}
 * give precise termination — we stop only when the last in-flight task finishes, never while a
 * worker might still enqueue children.
 *
 * <p>The engine depends only on the injected interfaces, so it can be unit-tested with fakes and never
 * touches the real network.
 */
public final class ConcurrentCrawler implements Crawler {

    private final CrawlerConfig config;
    private final Fetcher fetcher;
    private final LinkExtractor extractor;
    private final UrlNormalizer normalizer;
    private final Scope scope;
    private final ResultSink sink;
    private final Consumer<String> diagnostics;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight;
    private final Set<URI> visited = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger pagesClaimed = new AtomicInteger();
    private final AtomicInteger pagesCrawled = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final CountDownLatch done = new CountDownLatch(1);

    /** Convenience constructor: discards diagnostics (non-HTML/failed fetches are silently counted only). */
    public ConcurrentCrawler(CrawlerConfig config,
                             Fetcher fetcher,
                             LinkExtractor extractor,
                             UrlNormalizer normalizer,
                             Scope scope,
                             ResultSink sink) {
        this(config, fetcher, extractor, normalizer, scope, sink, line -> {
        });
    }

    /**
     * @param diagnostics receives one human-readable line per non-reported fetch outcome (skipped, failed,
     *                    or off-domain redirect). Must be thread-safe; the CLI routes it to {@code stderr}.
     */
    public ConcurrentCrawler(CrawlerConfig config,
                             Fetcher fetcher,
                             LinkExtractor extractor,
                             UrlNormalizer normalizer,
                             Scope scope,
                             ResultSink sink,
                             Consumer<String> diagnostics) {
        this.config = config;
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.normalizer = normalizer;
        this.scope = scope;
        this.sink = sink;
        this.diagnostics = diagnostics;
        this.inFlight = new Semaphore(config.maxConcurrentRequests());
    }

    @Override
    public CrawlSummary crawl() throws InterruptedException {
        try {
            submit(config.seed());
            if (pending.get() != 0) { // 0 => seed was invalid, out of scope, or already capped out
                done.await();
            }
        } finally {
            pool.shutdown();
        }
        return new CrawlSummary(pagesCrawled.get(), skipped.get(), failed.get());
    }

    /** The single gate every candidate URL passes through; claims a URL for processing at most once. */
    private void submit(URI raw) {
        normalizer.normalize(raw)
                .filter(scope::inScope)
                .filter(visited::add) // ConcurrentHashMap key-set add() is atomic: false if already seen
                .ifPresent(this::dispatch);
    }

    private void dispatch(URI url) {
        if (config.maxPages() > 0 && pagesClaimed.incrementAndGet() > config.maxPages()) {
            return; // safety cap reached
        }
        pending.incrementAndGet();
        pool.execute(() -> process(url));
    }

    private void process(URI url) {
        try {
            switch (fetchWithLimit(url)) {
                case FetchResponse.Html html -> handleHtml(url, html);
                case FetchResponse.Skipped s -> {
                    skipped.incrementAndGet();
                    diagnostics.accept("skipped (not HTML): " + s.finalUrl() + " [" + s.contentType() + "]");
                }
                case FetchResponse.Failed f -> {
                    failed.incrementAndGet();
                    diagnostics.accept("failed: " + f.url() + " (" + f.reason() + ")");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // A single bad page must never bring down the whole crawl.
        } finally {
            if (pending.decrementAndGet() == 0) {
                done.countDown();
            }
        }
    }

    /** Reports an in-scope HTML page and enqueues its links; drops off-domain redirect targets */
    private void handleHtml(URI requested, FetchResponse.Html html) {
        // Re-check scope on the POST-redirect URL — the fetch may have been redirected off-domain.
        // Normalize so the page identity matches the visited set and scope check.
        URI finalUrl = normalizer.normalize(html.finalUrl()).filter(scope::inScope).orElse(null);
        if (finalUrl == null) {
            diagnostics.accept("off-domain redirect, not crawled: " + requested + " -> " + html.finalUrl());
            return;
        }
        // Proceed only if there was no effective redirect (this URL was already claimed at submit), or we
        // can newly claim the redirect target (so the same page is not also crawled via a direct link).
        // A target already claimed elsewhere is skipped (crawled via another path).
        if (finalUrl.equals(requested) || visited.add(finalUrl)) {
            List<URI> links = extractor.extract(html.body(), finalUrl);
            pagesCrawled.incrementAndGet();
            sink.accept(new CrawlResult(finalUrl, links));
            links.forEach(this::submit);
        }
    }

    private FetchResponse fetchWithLimit(URI url) throws InterruptedException {
        inFlight.acquire();
        try {
            return fetcher.fetch(url);
        } finally {
            inFlight.release();
        }
    }
}