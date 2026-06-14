package com.crawler.fetch;

import com.crawler.core.config.CrawlerConfig;
import com.crawler.fetch.model.FetchResponse;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class HttpClientFetcherTest {

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private Fetcher fetcher() {
        CrawlerConfig config = new CrawlerConfig(
                URI.create(WM.baseUrl()), 4, Duration.ofSeconds(10), "test-agent", 0, false, Duration.ZERO);
        return new HttpClientFetcher(config);
    }

    private URI url(String path) {
        return URI.create(WM.baseUrl() + path);
    }

    @Test
    void htmlResponseBecomesHtml() {
        WM.stubFor(get("/page").willReturn(ok()
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody("<html><body><a href=\"/x\">x</a></body></html>")));

        FetchResponse response = fetcher().fetch(url("/page"));

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Html.class, html -> {
            assertThat(html.finalUrl()).isEqualTo(url("/page"));
            assertThat(html.body()).contains("<a href=\"/x\">");
        });
    }

    @Test
    void nonHtmlBecomesSkipped() {
        WM.stubFor(get("/file.pdf").willReturn(ok()
                .withHeader("Content-Type", "application/pdf")
                .withBody("%PDF-1.4")));

        FetchResponse response = fetcher().fetch(url("/file.pdf"));

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Skipped.class, skipped ->
                assertThat(skipped.contentType()).contains("application/pdf"));
    }

    @Test
    void notFoundBecomesFailed() {
        WM.stubFor(get("/missing").willReturn(aResponse().withStatus(404)));

        FetchResponse response = fetcher().fetch(url("/missing"));

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Failed.class, failed ->
                assertThat(failed.reason()).contains("404"));
    }

    @Test
    void followsRedirectAndReportsFinalUrl() {
        WM.stubFor(get("/old").willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "/new")));
        WM.stubFor(get("/new").willReturn(ok()
                .withHeader("Content-Type", "text/html")
                .withBody("<html>moved</html>")));

        FetchResponse response = fetcher().fetch(url("/old"));

        assertThat(response).isInstanceOfSatisfying(FetchResponse.Html.class, html -> {
            assertThat(html.finalUrl()).isEqualTo(url("/new")); // post-redirect URL
            assertThat(html.body()).contains("moved");
        });
    }
}