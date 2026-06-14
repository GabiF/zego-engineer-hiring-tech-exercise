package com.crawler.fetch;

import com.crawler.core.config.CrawlerConfig;
import com.crawler.fetch.model.FetchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientFetcherUnitTest {

    private static final URI REQUESTED = URI.create("https://example.com/page");
    private static final URI FINAL_URL = URI.create("https://example.com/page/"); // as if a redirect happened

    // --- classify(): content-type branches (all 2xx) ---

    @ParameterizedTest(name = "Content-Type \"{0}\" -> Html")
    @ValueSource(strings = {"text/html", "text/html; charset=utf-8", "TEXT/HTML", "application/xhtml+xml"})
    void htmlContentTypesBecomeHtml(String contentType) {
        FetchResponse response = HttpClientFetcher.classifyResponse(REQUESTED, FINAL_URL, 200, contentType, "<html/>");

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Html.class, html -> {
            assertThat(html.finalUrl()).isEqualTo(FINAL_URL); // keyed by the post-redirect URL
            assertThat(html.body()).isEqualTo("<html/>");
        });
    }

    @ParameterizedTest(name = "Content-Type \"{0}\" -> Skipped")
    @ValueSource(strings = {"application/pdf", "image/png", "application/json", ""}) // "" = header absent
    void nonHtmlContentTypesBecomeSkipped(String contentType) {
        FetchResponse response = HttpClientFetcher.classifyResponse(REQUESTED, FINAL_URL, 200, contentType, "body");

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Skipped.class, skipped -> {
            assertThat(skipped.finalUrl()).isEqualTo(FINAL_URL);
            assertThat(skipped.contentType()).isEqualTo(contentType);
        });
    }

    // --- classify(): status branches ---

    @ParameterizedTest(name = "status {0} -> Failed")
    @ValueSource(ints = {301, 304, 400, 404, 500, 503})
    void nonSuccessStatusesBecomeFailed(int status) {
        FetchResponse response = HttpClientFetcher.classifyResponse(REQUESTED, FINAL_URL, status, "text/html", "x");

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Failed.class, failed -> {
            assertThat(failed.url()).isEqualTo(REQUESTED); // Failed is keyed by the requested URL
            assertThat(failed.reason()).contains(String.valueOf(status));
        });
    }

    // --- fetch(): exception handling via the injected send seam (no network) ---

    @Test
    void ioExceptionBecomesFailed() {
        FetchResponse response = fetcherThatThrows(new IOException("connection reset")).fetch(REQUESTED);

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Failed.class, failed -> {
            assertThat(failed.url()).isEqualTo(REQUESTED);
            assertThat(failed.reason()).isEqualTo("IOException");
        });
    }

    @Test
    void timeoutBecomesFailed() {
        FetchResponse response = fetcherThatThrows(new HttpTimeoutException("timed out")).fetch(REQUESTED);

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Failed.class, failed ->
                assertThat(failed.reason()).isEqualTo("HttpTimeoutException"));
    }

    @Test
    void interruptIsReFlaggedAndBecomesFailed() {
        FetchResponse response = fetcherThatThrows(new InterruptedException()).fetch(REQUESTED);

        assertThat(response).isInstanceOf(FetchResponse.Failed.class);
        assertThat(Thread.interrupted()).isTrue(); // the interrupt flag was set (and is cleared by this read)
    }

    /** A fetcher whose send seam always throws {@code toThrow} — exercises {@code fetch}'s catch blocks. */
    private static Fetcher fetcherThatThrows(Exception toThrow) {
        CrawlerConfig config = new CrawlerConfig(
                REQUESTED, 1, Duration.ofSeconds(1), "test", 0, false, Duration.ZERO);
        return new HttpClientFetcher(config, request -> {
            if (toThrow instanceof IOException io) {
                throw io;
            }
            throw (InterruptedException) toThrow;
        });
    }
}