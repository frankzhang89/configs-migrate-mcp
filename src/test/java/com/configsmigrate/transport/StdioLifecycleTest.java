package com.configsmigrate.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class StdioLifecycleTest {
    /** Retains protocol bytes, waits for readiness on early EOF, and closes only once. */
    @Test void earlyBufferedEofClosesAfterReadinessOnlyOnce() throws Exception {
        var closes = new AtomicInteger();
        try (var context = context(closes)) {
            var lifecycle = new StdioLifecycle(context);
            var input = lifecycle.observe(new ByteArrayInputStream(new byte[]{1, 2}));
            assertThat(input.readNBytes(2)).containsExactly(1, 2);
            assertThat(input.read(new byte[8], 0, 8)).isEqualTo(-1);
            assertThat(closes).hasValue(0);
            lifecycle.onApplicationEvent(ready(context));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(closes).hasValue(1));
            lifecycle.onApplicationEvent(ready(context));
            assertThat(input.read()).isEqualTo(-1);
            assertThat(closes).hasValue(1);
        }
    }

    /** Exercises both input failure paths before and after readiness. */
    @Test void inputFailuresCloseWithoutConsumingAnotherReadersInput() throws Exception {
        for (boolean buffered : new boolean[]{false, true}) {
            var closes = new AtomicInteger();
            try (var context = context(closes)) {
                var lifecycle = new StdioLifecycle(context);
                InputStream input = lifecycle.observe(new InputStream() {
                    /** Models a broken client pipe. */
                    @Override public int read() throws IOException { throw new IOException("test input failure"); }
                });
                if (buffered) lifecycle.onApplicationEvent(ready(context));
                assertThatThrownBy(() -> {
                    if (buffered) input.read(new byte[8], 0, 8); else input.read();
                }).isInstanceOf(IOException.class);
                if (!buffered) {
                    assertThat(closes).hasValue(0);
                    lifecycle.onApplicationEvent(ready(context));
                }
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertThat(closes).hasValue(1);
                    assertThat(context.isActive()).isFalse();
                });
            }
        }
    }

    /** Counts lifecycle shutdowns while allowing ordinary context resource cleanup. */
    private GenericApplicationContext context(AtomicInteger closes) {
        var context = new GenericApplicationContext() {
            /** Counts active shutdowns rather than the test's final defensive close. */
            @Override public void close() {
                if (isActive()) closes.incrementAndGet();
                super.close();
            }
        };
        context.refresh();
        return context;
    }

    /** Builds the readiness event for the exact context owned by the lifecycle. */
    private ApplicationReadyEvent ready(GenericApplicationContext context) {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO);
    }
}
