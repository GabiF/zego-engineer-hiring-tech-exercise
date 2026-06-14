package com.crawler.url;

import java.net.URI;

public final class HostScope implements Scope {
    private final String seedHost;

    public HostScope(URI seed) {
        String host = seed.getHost();
        this.seedHost = host == null ? "" : host.toLowerCase();
    }

    @Override
    public boolean inScope(URI url) {
        if (url == null) return false;
        String host = url.getHost();
        return host != null && seedHost.equals(host.toLowerCase());
    }
}

