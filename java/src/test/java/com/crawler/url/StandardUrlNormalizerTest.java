package com.crawler.url;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class StandardUrlNormalizerTest {

    private final UrlNormalizer normalizer = new StandardUrlNormalizer();

    static Stream<Arguments> canonicalisations() {
        return Stream.of(
                // scheme + host lower-cased (path case preserved)
                arguments("HTTP://Example.COM/About", "http://example.com/About"),
                // default port stripped (scheme-aware)
                arguments("http://example.com:80/", "http://example.com/"),
                arguments("https://example.com:443/", "https://example.com/"),
                arguments("http://example.com:8080/", "http://example.com:8080/"),
                arguments("https://example.com:80/", "https://example.com:80/"), // 80 ≠ https default
                // fragment dropped
                arguments("https://example.com/page#section", "https://example.com/page"),
                arguments("https://example.com/#top", "https://example.com/"),
                // empty path -> "/"
                arguments("https://example.com", "https://example.com/"),
                arguments("https://example.com#x", "https://example.com/"),
                // path normalised (./..)
                arguments("https://example.com/a/../b", "https://example.com/b"),
                arguments("https://example.com/a/./b", "https://example.com/a/b"),
                arguments("https://example.com/a/b/..", "https://example.com/a/"),
                // trailing slash preserved (so /about and /about/ stay distinct)
                arguments("https://example.com/about", "https://example.com/about"),
                arguments("https://example.com/about/", "https://example.com/about/"),
                // query kept verbatim
                arguments("https://example.com/s?q=cats&p=2", "https://example.com/s?q=cats&p=2"),
                arguments("https://example.com/x?utm_source=foo", "https://example.com/x?utm_source=foo"),
                // everything at once
                arguments("HTTP://Example.com:80/a/../b/?x=1#frag", "http://example.com/b/?x=1"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("canonicalisations")
    void canonicalises(String input, String expected) throws URISyntaxException {
        assertThat(normalizer.normalize(URI.create(input)))
                .contains(URI.create(expected));
    }

    @ParameterizedTest(name = "drops {0}")
    @ValueSource(strings = {
            "mailto:hello@example.com",
            "tel:+1234567",
            "javascript:void(0)",
            "data:text/plain,hi",
            "ftp://example.com/file.txt",
            "//example.com/protocol-relative", // no scheme (resolution is the extractor's job)
            "/relative/path"                    // no scheme/host
    })
    void dropsNonHttpOrUnresolved(String input) throws URISyntaxException {
        assertThat(normalizer.normalize(URI.create(input))).isEmpty();
    }

    @Test
    void isIdempotent() throws URISyntaxException {
        URI messy = URI.create("HTTP://Example.com:80/a/../b/?x=1#frag");
        Optional<URI> once = normalizer.normalize(messy);
        assertThat(once).isPresent();
        assertThat(normalizer.normalize(once.get())).isEqualTo(once);
    }
}