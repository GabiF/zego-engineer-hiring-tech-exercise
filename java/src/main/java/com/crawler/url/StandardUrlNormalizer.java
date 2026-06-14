package com.crawler.url;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Stubbed standard normalizer. Implement algorithm described in learning.md.
 */
public class StandardUrlNormalizer implements UrlNormalizer {
    private static final int HTTP_DEFAULT_PORT = 80;
    private static final int HTTPS_DEFAULT_PORT = 443;

    @Override
    public Optional<URI> normalize(URI url) throws URISyntaxException {
        // no scheme or host => drop
        if (url.getScheme() == null || url.getHost() == null) {
            return Optional.empty();
        }

        // lowercase scheme
        String normalizedScheme = url.getScheme().toLowerCase();

        // non-http scheme => drop
        if (!(normalizedScheme.equals("http") || normalizedScheme.equals("https"))) {
            return Optional.empty();
        }

        // normalize path (handles ./ and ../ segments)
        URI urlWithNormalizedPath = url.normalize();

        // lowercase host
        String normalizedHost = urlWithNormalizedPath.getHost().toLowerCase();


        // remove default ports for http and https; keep if non-matching (80 for HTTPS or 443 for HTTP)
        String normalizedPort;
        if (urlWithNormalizedPath.getPort() == -1
                || ("http".equals(normalizedScheme) && urlWithNormalizedPath.getPort() == HTTP_DEFAULT_PORT)
                || ("https".equals(normalizedScheme) && urlWithNormalizedPath.getPort() == HTTPS_DEFAULT_PORT)) {
            normalizedPort = "";
        } else {
            normalizedPort = String.valueOf(urlWithNormalizedPath.getPort());
        }

        String normalizedPath = urlWithNormalizedPath.getPath();

        // Reconstruct url from individual parts, explicitly dropping fragments but keeping query params
        StringBuilder reconstructedUrlBuilder = new StringBuilder(normalizedScheme);
        reconstructedUrlBuilder.append("://");
        reconstructedUrlBuilder.append(normalizedHost);
        if (!normalizedPort.isEmpty()) {
            reconstructedUrlBuilder.append(":");
            reconstructedUrlBuilder.append(normalizedPort);
        }
        if (!normalizedPath.isEmpty()) {
            reconstructedUrlBuilder.append(normalizedPath);
        } else {
            reconstructedUrlBuilder.append("/");
        }
        if (urlWithNormalizedPath.getQuery() != null) {
            reconstructedUrlBuilder.append("?");
            reconstructedUrlBuilder.append(urlWithNormalizedPath.getQuery());
        }

        return Optional.of(new URI(reconstructedUrlBuilder.toString()));
    }
}

