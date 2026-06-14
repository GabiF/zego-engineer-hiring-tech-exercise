package com.crawler.output;

import com.crawler.core.model.CrawlResult;

import java.io.PrintStream;

/**
 * Stub for JsonLinesSink.
 */
public class JsonLinesSink implements ResultSink {

    private final PrintStream out;

    public JsonLinesSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void accept(CrawlResult result) {
        throw new UnsupportedOperationException("JsonLinesSink.accept() not implemented yet");
    }

    @Override
    public void accept(String outputLine) {
        throw new UnsupportedOperationException("JsonLinesSink.accept() not implemented yet");
    }
}

