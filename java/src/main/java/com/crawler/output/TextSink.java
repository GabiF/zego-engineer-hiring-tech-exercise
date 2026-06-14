package com.crawler.output;

import com.crawler.core.model.CrawlResult;

import java.io.PrintStream;
import java.net.URI;

/**
 * Thread-safe sink that emits one page-block atomically to stdout.
 */
public class TextSink implements ResultSink {

    private final PrintStream out;

    public TextSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void accept(CrawlResult result) {
        StringBuilder block = new StringBuilder(result.pageUrl().toString());
        for (URI link : result.links()) {
            block.append(System.lineSeparator()).append(" -> ").append(link);
        }
        block.append(System.lineSeparator());

        synchronized (out) {
            out.print(block);
        }
    }

    @Override
    public void accept(String outputLine) {
        synchronized (out) {
            out.println(outputLine);
        }
    }
}

