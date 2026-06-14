package com.crawler.core;

import com.crawler.core.config.CrawlerConfig;
import com.crawler.core.model.CrawlResult;
import com.crawler.fetch.model.FetchResponse;
import com.crawler.fetch.Fetcher;
import com.crawler.output.ResultSink;
import com.crawler.output.TextSink;
import com.crawler.parse.LinkExtractor;
import com.crawler.url.HostScope;
import com.crawler.url.UrlNormalizer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SingleThreadedCrawlerTest {

    private final Map<URI, FetchResponse> responses = new HashMap<>();
    private final Map<URI, List<URI>> linksByPage = new HashMap<>();
    private final List<CrawlResult> printed = new ArrayList<>();

    // --- test doubles (NOT the real implementations) ---
    private final Fetcher fetcher =
            url -> responses.getOrDefault(url, new FetchResponse.Failed(url, "404"));
    private final LinkExtractor extractor =
            (html, baseUrl) -> linksByPage.getOrDefault(baseUrl, List.of());
    private final UrlNormalizer normalizer = uri -> {              // identity for http/https here;
        String s = uri.getScheme();                               // the REAL StandardUrlNormalizer
        return ("http".equals(s) || "https".equals(s))            // yields the same result for these
                ? Optional.of(uri) : Optional.empty();            // already-canonical URLs.
    };
    private final ResultSink sink = new TextSink(System.out) {;
        @Override
        public void accept(CrawlResult result) {
            printed.add(result);
        }
    };

    private static URI u(String s) { return URI.create(s); }

    private void html(String page, String... links) {
        URI p = u(page);
        responses.put(p, new FetchResponse.Html(p, "<html/>"));
        linksByPage.put(p, Arrays.stream(links).map(SingleThreadedCrawlerTest::u).toList());
    }

    @Test
    void crawlsOneDomainBreadthFirst() throws Exception {
        URI seed = u("https://example.com/");
        html("https://example.com/",
                "https://example.com/", "https://example.com/about",
                "https://example.com/products", "https://twitter.com/example");
        html("https://example.com/about",
                "https://example.com/", "https://example.com/contact");
        html("https://example.com/products",
                "https://example.com/products/1", "https://example.com/products/2",
                "https://example.com/about");
        html("https://example.com/contact",
                "https://example.com/", "mailto:hello@example.com");
        html("https://example.com/products/1",
                "https://example.com/products", "https://example.com/files/spec.pdf");
        html("https://example.com/products/2",
                "https://example.com/missing", "https://example.com/products/1");
        responses.put(u("https://example.com/files/spec.pdf"),
                new FetchResponse.Skipped(u("https://example.com/files/spec.pdf"), "application/pdf"));
        // "/missing" intentionally absent → fetcher returns Failed

        CrawlerConfig config = new CrawlerConfig(
                seed, 1, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        new SingleThreadedCrawler(config, fetcher, extractor, normalizer, new HostScope(seed), sink)
                .crawl();

        // 6 HTML pages, in BFS order; pdf + missing fetched but not printed
        assertThat(printed).extracting(CrawlResult::pageUrl).containsExactly(
                u("https://example.com/"),
                u("https://example.com/about"),
                u("https://example.com/products"),
                u("https://example.com/contact"),
                u("https://example.com/products/1"),
                u("https://example.com/products/2"));

        // page 1 prints ALL links, including the off-domain one
        assertThat(printed.get(0).links()).containsExactly(
                u("https://example.com/"), u("https://example.com/about"),
                u("https://example.com/products"), u("https://twitter.com/example"));
    }

    @Test
    void pagesFetchedCountsEveryProcessedPageRegardlessOfResponseType() throws Exception {
        URI seed = u("https://example.com/");

        // Seed page links to three pages
        html("https://example.com/",
                "https://example.com/a",
                "https://example.com/b",
                "https://example.com/c");

        // Make /a a skipped (non-HTML), /b a failed, /c an HTML page
        responses.put(u("https://example.com/a"),
                new FetchResponse.Skipped(u("https://example.com/a"), "application/pdf"));
        responses.put(u("https://example.com/b"),
                new FetchResponse.Failed(u("https://example.com/b"), "404"));
        responses.put(u("https://example.com/c"),
                new FetchResponse.Html(u("https://example.com/c"), "<html/>"));

        // Configure maxPages to 2 so crawler should stop after processing two pages
        CrawlerConfig config = new CrawlerConfig(seed, 1, Duration.ofSeconds(10), "test", 2, false, Duration.ZERO);

        SingleThreadedCrawler crawler =
                new SingleThreadedCrawler(config, fetcher, extractor, normalizer, new HostScope(seed), sink);

        crawler.crawl();

        // Assert that pagesCrawled was incremented for each processed URL (including skipped/failed)
        org.assertj.core.api.Assertions.assertThat(crawler.getPagesFetched()).isEqualTo(2);
    }
}