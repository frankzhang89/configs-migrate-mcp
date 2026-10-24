package com.configsmigrate.transport;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Closes one stdio application after EOF or input failure, including EOF during startup. */
final class StdioLifecycle implements ApplicationListener<ApplicationReadyEvent> {
    private final ConfigurableApplicationContext context;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean inputEnded = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();

    /** Retains the context owned by the current stdio process. */
    StdioLifecycle(ConfigurableApplicationContext context) { this.context = context; }

    /** Wraps input without reading ahead or changing its protocol bytes. */
    InputStream observe(InputStream input) {
        return new FilterInputStream(input) {
            /** Observes single-byte EOF and failures. */
            @Override public int read() throws IOException {
                try { return checkEnd(in.read()); }
                catch (IOException error) { endInput(); throw error; }
            }

            /** Observes the buffered reads used by the SDK. */
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                try { return checkEnd(in.read(bytes, offset, length)); }
                catch (IOException error) { endInput(); throw error; }
            }

            /** Records EOF while returning the original read result unchanged. */
            private int checkEnd(int count) {
                if (count == -1) endInput();
                return count;
            }
        };
    }

    /** Defers shutdown until startup completes so early EOF cannot race bean initialization. */
    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        if (event.getApplicationContext() == context) {
            ready.set(true);
            closeIfEnded();
        }
    }

    /** Records a terminal input condition, whether it happens before or after readiness. */
    private void endInput() {
        inputEnded.set(true);
        closeIfEnded();
    }

    /** Starts cleanup once, outside the SDK reader thread to avoid closing it from itself. */
    private void closeIfEnded() {
        if (ready.get() && inputEnded.get() && closing.compareAndSet(false, true)) {
            Thread.ofPlatform().name("stdio-shutdown").start(context::close);
        }
    }
}
