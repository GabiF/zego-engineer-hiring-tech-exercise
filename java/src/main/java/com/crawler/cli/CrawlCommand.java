package com.crawler.cli;

import com.crawler.core.Crawler;
import com.crawler.core.config.CrawlerConfig;
import com.crawler.core.SingleThreadedCrawler;
import com.crawler.fetch.Fetcher;
import com.crawler.fetch.HttpClientFetcher;
import com.crawler.output.JsonLinesSink;
import com.crawler.output.ResultSink;
import com.crawler.output.TextSink;
import com.crawler.parse.JsoupLinkExtractor;
import com.crawler.parse.LinkExtractor;
import com.crawler.url.HostScope;
import com.crawler.url.Scope;
import com.crawler.url.StandardUrlNormalizer;
import com.crawler.url.UrlNormalizer;
import picocli.CommandLine;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "crawl", mixinStandardHelpOptions = true, description = "Simple crawler CLI (skeleton)")
public final class CrawlCommand implements Callable<Integer> {

    enum Format { text, json }

    @CommandLine.Parameters(index = "0", description = "Seed URL to crawl (must be an absolute http/https URL")
    private URI seed;

    @CommandLine.Option(names = {"--concurrency"}, defaultValue = "16",
            description = "Max concurrent HTTP requests (default: £{DEFAULT-VALUE})")
    private int concurrency;

    @CommandLine.Option(names = {"--timeout"}, defaultValue = "10",
            description = "Per-request timeout in seconds (default: £{DEFAULT-VALUE})")
    private long timeoutSeconds;

    @CommandLine.Option(names = {"--max-pages"}, defaultValue = "0",
            description = "Max pages to crawl (0 = unlimited, default: £{DEFAULT-VALUE})")
    private int maxPages;

    @CommandLine.Option(names = {"--format"}, defaultValue = "text",
            description = "Output format (text or json, default: £{DEFAULT-VALUE})")
    private Format format;

    @CommandLine.Option(names = {"--politeness"}, defaultValue = "0",
            description = "Politeness delay between requests, in milliseconds (default: £{DEFAULT-VALUE}) - parsed but not yet enforced")
    private long delayMillis;

    @CommandLine.Option(names = {"--respect-robots"}, defaultValue = "false",
            description = "Honour robots.txt (default: £{DEFAULT-VALUE}) - parsed but not yet enforced")
    private boolean respectRobots;

    @CommandLine.Option(names = {"--user-agent"}, defaultValue = "crawler/1.0",
            description = "User-Agent header to send in HTTP requests (default: £{DEFAULT-VALUE})")
    private String userAgent;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CrawlCommand()).execute(args);
        System.exit(exitCode);
    }

    private boolean isValid(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
            return false;
        }

        return true;
    }

    private List<String> validateParams(int concurrency, long timeoutSeconds, int maxPages, Format format, long delayMillis) {
        List<String> errors = new ArrayList<>();

        if (concurrency <= 0) {
            errors.add("concurrency must be a positive integer");
        }
        if (timeoutSeconds <= 0) {
            errors.add("timeout must be a positive integer");
        }
        if (maxPages < 0) {
            errors.add("max-pages must be a non-negative integer");
        }
        if (format == null) {
            errors.add("format must be either 'text' or 'json'");
        }
        if (delayMillis < 0) {
            errors.add("politeness delay must be a non-negative integer");
        }

        return errors;
    }

    @Override
    public Integer call() throws Exception {
        if (!isValid(seed)) {
            System.err.println("The seed must be an absolute http/https URL with a host: " + seed);
            return 2;
        }

        final List<String> validationErrors = validateParams(concurrency, timeoutSeconds, maxPages, format, delayMillis);
        if (!validationErrors.isEmpty()) {
            System.err.println(String.join("\n", validationErrors));
            return 3;
        }

        final CrawlerConfig config = new CrawlerConfig(
                seed,
                concurrency,
                Duration.ofSeconds(timeoutSeconds),
                userAgent,
                maxPages,
                respectRobots,
                Duration.ofMillis(delayMillis)
        );

        // Dependency wiring (the one place where concrete types are chosen)
        final UrlNormalizer normalizer = new StandardUrlNormalizer();
        final Scope scope = new HostScope(seed);
        final Fetcher fetcher = new HttpClientFetcher(config);
        final LinkExtractor extractor = new JsoupLinkExtractor();
        final ResultSink sink = switch(format) {
            case text -> new TextSink(System.out);
            case json -> new JsonLinesSink(System.out);
        };

        final Crawler crawler = new SingleThreadedCrawler(config, fetcher, extractor, normalizer, scope, sink);

        try {
            sink.accept("Started crawler with config: " + config);
            crawler.crawl();
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Crawler interrupted");
            return 1;
        }
    }
}

