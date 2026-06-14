package com.crawler.fetch;

import com.crawler.core.config.CrawlerConfig;
import com.crawler.fetch.model.FetchResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HttpClientFetcher is an implementation of the Fetcher interface that uses Java's built-in HttpClient to fetch web pages.
 * It handles HTTP requests and responses, including status codes and content types, and returns appropriate FetchResponse
 * objects based on the outcome of the fetch operation.
 */
public class HttpClientFetcher implements Fetcher {

    private final CrawlerConfig config;
    private final HttpSend send;

    public HttpClientFetcher(CrawlerConfig config) {
        this(config, defaultSend(config));
    }

    /**
     * Package-private constructor. Lets unit tests inject a fake send so that no I/O is involved.
     * @param config
     * @param send
     */
    HttpClientFetcher(CrawlerConfig config, HttpSend send) {
        this.config = config;
        this.send = send;
    }

    /**
     * Returns the result of the fetch operation, as a {@code FetchResponse} object.
     * Only returns {@code FetchResponse.Html} if the Content-Type of the response is "text/html".
     *
     * @param url the URL to fetch
     */
    @Override
    public FetchResponse fetch(URI url) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(url)
                .header("User-Agent", config.userAgent())
                .timeout(config.requestTimeout())
                .GET()
                .build();
        try {
            HttpResponse<String> response = send.send(request);
            final String contentType = response.headers().firstValue("Content-Type").orElse("");

            return classifyResponse(url, response.uri(), response.statusCode(), contentType, response.body());
        } catch (IOException e) {
            return new FetchResponse.Failed(url, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResponse.Failed(url, "Interrupted");
        }
    }

    static FetchResponse classifyResponse(URI requestedUrl, URI finalUrl, int statusCode, String contentType, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            return new FetchResponse.Failed(requestedUrl, "HTTP error: " + statusCode);
        }

        if (!isHtml(contentType)) {
            return new FetchResponse.Skipped(finalUrl, contentType);
        }

        return new FetchResponse.Html(finalUrl, body);
    }

    private static boolean isHtml(String contentType) {
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();

        return mediaType.equalsIgnoreCase("text/html") || mediaType.equalsIgnoreCase("application/xhtml+xml");
    }

    private static HttpSend defaultSend(CrawlerConfig config) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return request -> client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Seam over the blocking HTTP exchange, so mapping and error handling can be unit tested without I/O
     */
    @FunctionalInterface
    interface HttpSend {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }
}

