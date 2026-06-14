package com.crawler.core;

import com.crawler.core.config.CrawlerConfig;
import com.crawler.core.model.CrawlResult;
import com.crawler.core.model.CrawlSummary;
import com.crawler.fetch.model.FetchResponse;
import com.crawler.fetch.Fetcher;
import com.crawler.output.ResultSink;
import com.crawler.output.TextSink;
import com.crawler.parse.LinkExtractor;
import com.crawler.url.HostScope;
import com.crawler.url.StandardUrlNormalizer;
import com.crawler.url.UrlNormalizer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real {@link ConcurrentCrawler} with in-memory fakes (no network). Because workers run on
 * virtual threads, completion order is non-deterministic, so assertions are order-independent: every
 * in-scope page is reported exactly once, off-domain links are printed but never crawled, cycles don't
 * loop, and the crawl terminates.
 */
class ConcurrentCrawlerTest {

    private final Map<URI, FetchResponse> responses = new HashMap<>();
    private final Map<URI, List<URI>> linksByPage = new HashMap<>();
    private final Collection<CrawlResult> printed = new ConcurrentLinkedQueue<>();
    private final Collection<String> diagnostics = new ConcurrentLinkedQueue<>();

    private final Fetcher fetcher =
            url -> responses.getOrDefault(url, new FetchResponse.Failed(url, "404"));
    private final LinkExtractor extractor =
            (html, baseUrl) -> linksByPage.getOrDefault(baseUrl, List.of());
    private final UrlNormalizer normalizer = new StandardUrlNormalizer();
    private final ResultSink sink = new TextSink(System.out) {;
        @Override
        public void accept(CrawlResult result) {
            printed.add(result);
        }
    };

    private static URI u(String s) {
        return URI.create(s);
    }

    private void page(String url, String... links) {
        URI p = u(url);
        responses.put(p, new FetchResponse.Html(p, "<html/>"));
        linksByPage.put(p, Arrays.stream(links).map(ConcurrentCrawlerTest::u).toList());
    }

    /** Registers a fetch of {@code from} that, after following redirects, lands on {@code finalUrl}. */
    private void redirectsTo(String from, String finalUrl) {
        responses.put(u(from), new FetchResponse.Html(u(finalUrl), "<html/>"));
    }

    @Test
    void crawlsSingleDomainDedupingCyclesAndRespectingScope() throws Exception {
        URI seed = u("https://example.com/");
        page("https://example.com/",
                "https://example.com/", "https://example.com/about",
                "https://example.com/products", "https://twitter.com/example"); // off-domain
        page("https://example.com/about",
                "https://example.com/", "https://example.com/contact");
        page("https://example.com/products",
                "https://example.com/products/1", "https://example.com/about");
        page("https://example.com/contact", "https://example.com/");
        page("https://example.com/products/1", "https://example.com/products"); // cycle back

        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        new ConcurrentCrawler(config, fetcher, extractor, normalizer, new HostScope(seed), sink).crawl();

        // every in-scope page reported exactly once, regardless of completion order
        assertThat(printed).extracting(CrawlResult::pageUrl).containsExactlyInAnyOrder(
                u("https://example.com/"),
                u("https://example.com/about"),
                u("https://example.com/products"),
                u("https://example.com/contact"),
                u("https://example.com/products/1"));

        // the off-domain link is printed among the seed's links, but never crawled
        CrawlResult seedResult = printed.stream()
                .filter(r -> r.pageUrl().equals(seed)).findFirst().orElseThrow();
        assertThat(seedResult.links()).contains(u("https://twitter.com/example"));
        assertThat(printed).extracting(CrawlResult::pageUrl)
                .doesNotContain(u("https://twitter.com/example"));
    }

    @Test
    void maxPagesCapsTheCrawl() throws Exception {
        URI seed = u("https://example.com/");
        page("https://example.com/", "https://example.com/a", "https://example.com/b");
        page("https://example.com/a", "https://example.com/c");
        page("https://example.com/b");
        page("https://example.com/c");

        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 2, false, Duration.ZERO); // cap = 2

        new ConcurrentCrawler(config, fetcher, extractor, normalizer, new HostScope(seed), sink).crawl();

        assertThat(printed).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void offDomainRedirectTargetIsNotReportedAndItsLinksAreNotFollowed() throws Exception {
        URI seed = u("https://example.com/");
        page("https://example.com/", "https://example.com/gone");
        // fetching /gone lands (after a redirect) on an off-domain page that links back in-scope
        redirectsTo("https://example.com/gone", "https://evil.com/");
        linksByPage.put(u("https://evil.com/"), List.of(u("https://example.com/trap")));
        page("https://example.com/trap"); // would be crawled + printed IF off-domain links were followed

        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        new ConcurrentCrawler(config, fetcher, extractor, normalizer, new HostScope(seed), sink).crawl();

        // only the seed is reported: the off-domain redirect target is dropped, and the in-scope link
        // discovered on that off-domain page is never followed
        assertThat(printed).extracting(CrawlResult::pageUrl).containsExactly(seed);
        assertThat(printed).extracting(CrawlResult::pageUrl)
                .doesNotContain(u("https://evil.com/"), u("https://example.com/trap"));
    }

    @Test
    void inScopeRedirectTargetIsReportedUnderItsFinalUrlAndFollowed() throws Exception {
        URI seed = u("https://example.com/");
        page("https://example.com/", "https://example.com/old");
        redirectsTo("https://example.com/old", "https://example.com/new"); // /old -> /new (in scope)
        page("https://example.com/new", "https://example.com/leaf");        // /new links onward to /leaf
        page("https://example.com/leaf");

        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        new ConcurrentCrawler(config, fetcher, extractor, normalizer, new HostScope(seed), sink).crawl();

        // reported under the post-redirect URL (/new, not /old), and links found on it are followed
        assertThat(printed).extracting(CrawlResult::pageUrl).containsExactlyInAnyOrder(
                u("https://example.com/"),
                u("https://example.com/new"),
                u("https://example.com/leaf"));
        assertThat(printed).extracting(CrawlResult::pageUrl)
                .doesNotContain(u("https://example.com/old"));
    }

    @Test
    void redirectTargetAlsoLinkedDirectlyIsCrawledOnce() throws Exception {
        URI seed = u("https://example.com/");
        // the seed links to both /a (which redirects to /canonical) and /canonical directly
        page("https://example.com/", "https://example.com/a", "https://example.com/canonical");
        redirectsTo("https://example.com/a", "https://example.com/canonical");
        page("https://example.com/canonical");

        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        new ConcurrentCrawler(config, fetcher, extractor, normalizer, new HostScope(seed), sink).crawl();

        // /canonical reported exactly once despite two paths to it (direct link + redirect from /a),
        // and /a itself is not reported as a page (it resolved to the already-claimed /canonical)
        assertThat(printed).extracting(CrawlResult::pageUrl).containsExactlyInAnyOrder(
                seed, u("https://example.com/canonical"));
    }

    @Test
    void failedSeedIsCountedAndReportedNotSilentlyDropped() throws Exception {
        URI seed = u("https://example.com/");
        // seed is never registered → the fake fetcher returns Failed(seed, "404")
        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        CrawlSummary summary = new ConcurrentCrawler(
                config, fetcher, extractor, normalizer, new HostScope(seed), sink, diagnostics::add).crawl();

        assertThat(printed).isEmpty();                 // nothing crawled...
        assertThat(summary.pagesCrawled()).isZero();   // ...so the run reports zero pages (→ non-zero exit)
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.skipped()).isZero();
        assertThat(diagnostics).anyMatch(line -> line.contains("failed") && line.contains("404"));
    }

    @Test
    void skippedNonHtmlSeedIsCountedAndReported() throws Exception {
        URI seed = u("https://example.com/");
        responses.put(seed, new FetchResponse.Skipped(seed, "application/pdf"));
        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        CrawlSummary summary = new ConcurrentCrawler(
                config, fetcher, extractor, normalizer, new HostScope(seed), sink, diagnostics::add).crawl();

        assertThat(printed).isEmpty();
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.pagesCrawled()).isZero();
        assertThat(diagnostics).anyMatch(line -> line.contains("application/pdf"));
    }

    @Test
    void summaryCountsCrawledSkippedAndFailedOutcomes() throws Exception {
        URI seed = u("https://example.com/");
        page("https://example.com/",
                "https://example.com/a",        // HTML  -> crawled
                "https://example.com/doc.pdf",  // 2xx non-HTML -> skipped
                "https://example.com/dead",     // unregistered -> failed (404)
                "https://other.com/x");         // off-domain -> printed as a link, never fetched
        page("https://example.com/a");
        responses.put(u("https://example.com/doc.pdf"),
                new FetchResponse.Skipped(u("https://example.com/doc.pdf"), "application/pdf"));

        CrawlerConfig config = new CrawlerConfig(
                seed, 8, Duration.ofSeconds(10), "test", 0, false, Duration.ZERO);

        CrawlSummary summary = new ConcurrentCrawler(
                config, fetcher, extractor, normalizer, new HostScope(seed), sink, diagnostics::add).crawl();

        assertThat(summary.pagesCrawled()).isEqualTo(2); // seed + /a
        assertThat(summary.skipped()).isEqualTo(1);      // /doc.pdf
        assertThat(summary.failed()).isEqualTo(1);       // /dead
        assertThat(printed).extracting(CrawlResult::pageUrl)
                .containsExactlyInAnyOrder(seed, u("https://example.com/a"));
    }
}