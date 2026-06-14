package com.crawler.output;

import com.crawler.core.model.CrawlResult;

public interface ResultSink {
    void accept(CrawlResult result);

    void accept(String outputLine);
}

