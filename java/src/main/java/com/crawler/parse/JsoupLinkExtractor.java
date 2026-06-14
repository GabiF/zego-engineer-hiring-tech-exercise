package com.crawler.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class JsoupLinkExtractor implements LinkExtractor {

    public JsoupLinkExtractor() {}

    @Override
    public List<URI> extract(String html, URI baseUrl) {
        Set<URI> links = new LinkedHashSet<>();

        final Document doc = Jsoup.parse(html, baseUrl.toString());
        doc.select("a[href]").forEach(anchor -> {
            try {
                final String absoluteUrl = anchor.absUrl("href");
                if (!absoluteUrl.isBlank()) {
                    links.add(new URI(absoluteUrl));
                }
            } catch (Exception e) {
                // Explicitly deciding to ignore. In a production system, would at least log a warning
            }
        });
        return List.copyOf(links);
    }
}

