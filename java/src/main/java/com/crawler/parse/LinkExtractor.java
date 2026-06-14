package com.crawler.parse;

import java.net.URI;
import java.util.List;

public interface LinkExtractor {
    List<URI> extract(String html, URI baseUrl);
}

