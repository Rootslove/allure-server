package ru.iopump.qa.allure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Bridges the {@code PrintStream} the allure-ai core writes its progress to (ping, per-cluster
 * answers, summary) into slf4j, one log record per line, so the analysis leaves a trace in the
 * server log instead of the container stdout. Category is fixed to {@code ru.iopump.qa.allure.ai}
 * so it can be raised or silenced independently of the classes that produce it.
 */
final class AiLog extends OutputStream {

    private static final Logger LOG = LoggerFactory.getLogger("ru.iopump.qa.allure.ai");

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    private AiLog() {
    }

    /** Auto-flushing UTF-8 stream; safe to hand to the core and to close afterwards. */
    static PrintStream printStream() {
        return new PrintStream(new AiLog(), true, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            flushLine();
        } else if (b != '\r') {
            line.write(b);
        }
    }

    @Override
    public synchronized void close() {
        flushLine();
    }

    private void flushLine() {
        if (line.size() == 0) {
            return;
        }
        LOG.info(line.toString(StandardCharsets.UTF_8));
        line.reset();
    }
}
