package com.crawler.fetch;

import com.crawler.fetch.model.FetchResponse;

import java.net.URI;

public interface Fetcher {
    FetchResponse fetch(URI url);
}

