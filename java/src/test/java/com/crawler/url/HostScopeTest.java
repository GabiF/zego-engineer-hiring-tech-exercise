package com.crawler.url;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class HostScopeTest {

    private final Scope scope = new HostScope(URI.create("https://example.com/"));

    @Test
    void sameHostIsInScope() {
        assertThat(scope.inScope(URI.create("https://example.com/about"))).isTrue();
    }

    @Test
    void subdomainsAreOutOfScope() {
        assertThat(scope.inScope(URI.create("https://www.example.com/"))).isFalse();
        assertThat(scope.inScope(URI.create("https://blog.example.com/posts"))).isFalse();
    }

    @Test
    void lookalikeDomainIsOutOfScope() {
        // Guards against the classic host.endsWith("example.com") bug.
        assertThat(scope.inScope(URI.create("https://notexample.com/"))).isFalse();
    }

    @Test
    void differentTldIsOutOfScope() {
        assertThat(scope.inScope(URI.create("https://example.org/"))).isFalse();
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        assertThat(scope.inScope(URI.create("https://EXAMPLE.com/x"))).isTrue();
    }
}