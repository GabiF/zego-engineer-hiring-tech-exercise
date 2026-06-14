package com.crawler.url;

import java.net.URI;
import java.util.Optional;

public interface UrlNormalizer {
    Optional<URI> normalize(URI url);
}

