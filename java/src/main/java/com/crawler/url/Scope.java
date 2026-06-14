package com.crawler.url;

import java.net.URI;

public interface Scope {
    boolean inScope(URI normalizedUrl);
}

