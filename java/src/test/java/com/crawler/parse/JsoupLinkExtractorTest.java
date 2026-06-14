package com.crawler.parse;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsoupLinkExtractorTest {

    private final LinkExtractor extractor = new JsoupLinkExtractor();

    @Test
    void resolvesRelativeAndAbsoluteLinksAgainstBase() {
        String html = """
                <a href="a">rel</a>
                <a href="/b">root-rel</a>
                <a href="../c">parent-rel</a>
                <a href="https://example.com/d">abs</a>
                <a href="https://other.com/e">off-domain</a>
                """;
        assertThat(extractor.extract(html, URI.create("https://example.com/docs/"))).containsExactly(
                URI.create("https://example.com/docs/a"),
                URI.create("https://example.com/b"),
                URI.create("https://example.com/c"),
                URI.create("https://example.com/d"),
                URI.create("https://other.com/e")); // off-domain kept: scope is NOT the extractor's job
    }

    @Test
    void resolvesProtocolRelativeUsingBaseScheme() {
        String html = "<a href=\"//cdn.example.com/app.js\">pr</a>";
        assertThat(extractor.extract(html, URI.create("https://example.com/")))
                .containsExactly(URI.create("https://cdn.example.com/app.js"));
    }

    @Test
    void honoursBaseHref() {
        String html = """
                <head><base href="https://cdn.example.com/assets/"></head>
                <body><a href="x.css">link</a></body>
                """;
        // resolves against <base href>, not the parse base URL
        assertThat(extractor.extract(html, URI.create("https://example.com/docs/page")))
                .containsExactly(URI.create("https://cdn.example.com/assets/x.css"));
    }

    @Test
    void deduplicatesPreservingFirstSeenOrder() {
        String html = """
                <a href="/a">1</a>
                <a href="/b">2</a>
                <a href="/a">dup-of-1</a>
                <a href="https://example.com/b">abs-dup-of-2</a>
                """;
        assertThat(extractor.extract(html, URI.create("https://example.com/")))
                .containsExactly(URI.create("https://example.com/a"), URI.create("https://example.com/b"));
    }

    @Test
    void keepsSamePageFragments() { // decision: no filtering here; the normalizer drops the fragment later
        String html = "<a href=\"#section\">jump</a>";
        assertThat(extractor.extract(html, URI.create("https://example.com/page")))
                .containsExactly(URI.create("https://example.com/page#section"));
    }

    @Test
    void returnsNonHttpSchemesFaithfully() { // decision: faithful — every resolved href, any scheme
        String html = """
                <a href="https://example.com/ok">web</a>
                <a href="mailto:hello@example.com">mail</a>
                <a href="tel:+15551234">phone</a>
                <a href="javascript:void(0)">js</a>
                """;
        assertThat(extractor.extract(html, URI.create("https://example.com/"))).containsExactly(
                URI.create("https://example.com/ok"),
                URI.create("mailto:hello@example.com"),
                URI.create("tel:+15551234"),
                URI.create("javascript:void(0)"));
    }

    @Test
    void skipsUnparseableHrefWithoutThrowing() { // decision: skip on URI failure (prod: log a warning)
        String html = """
                <a href="/ok">good</a>
                <a href="/a b">bad: the space makes URI.create throw</a>
                """;
        assertThat(extractor.extract(html, URI.create("https://example.com/")))
                .containsExactly(URI.create("https://example.com/ok"));
    }

    @Test
    void returnsEmptyListWhenNoLinks() {
        assertThat(extractor.extract("<p>no links here</p>", URI.create("https://example.com/"))).isEmpty();
    }

    @Test
    void returnsEmptyForAnchorsWithoutHref() {
        assertThat(extractor.extract("<a name=\"top\">anchor</a>", URI.create("https://example.com/"))).isEmpty();
    }

    @Test
    void returnsImmutableList() { // decision: immutable list out
        List<URI> links = extractor.extract("<a href=\"/a\">x</a>", URI.create("https://example.com/"));
        assertThatThrownBy(() -> links.add(URI.create("https://example.com/b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}