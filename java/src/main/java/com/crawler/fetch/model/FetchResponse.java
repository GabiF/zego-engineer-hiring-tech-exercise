package com.crawler.fetch.model;

import java.net.URI;

public sealed interface FetchResponse permits FetchResponse.Html, FetchResponse.Skipped, FetchResponse.Failed {
    record Html(URI finalUrl, String body) implements FetchResponse {}
    record Skipped(URI finalUrl, String contentType) implements FetchResponse {}
    record Failed(URI url, String reason) implements FetchResponse {}
}

