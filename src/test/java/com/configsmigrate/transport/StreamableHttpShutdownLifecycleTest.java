package com.configsmigrate.transport;

import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class StreamableHttpShutdownLifecycleTest {
    /** Orders cleanup ahead of Tomcat and shares one close operation across repeated stop calls. */
    @Test void closesBeforeTomcatAndOnlyOnce() {
        var transport = mock(McpStreamableServerTransportProvider.class);
        when(transport.closeGracefully()).thenReturn(Mono.empty());
        var lifecycle = new StreamableHttpShutdownLifecycle(transport);
        lifecycle.start();
        assertThat(lifecycle.getPhase()).isGreaterThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
        var callbacks = new AtomicInteger();
        lifecycle.stop(callbacks::incrementAndGet);
        lifecycle.stop(callbacks::incrementAndGet);
        lifecycle.stop();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(callbacks).hasValue(2);
            assertThat(lifecycle.isRunning()).isFalse();
        });
        verify(transport, times(1)).closeGracefully();
    }

    /** A transport that never completes cannot indefinitely block application shutdown. */
    @Test void releasesShutdownCallbackOnTimeout() {
        var transport = mock(McpStreamableServerTransportProvider.class);
        when(transport.closeGracefully()).thenReturn(Mono.never());
        var lifecycle = new StreamableHttpShutdownLifecycle(transport, Duration.ofMillis(50));
        lifecycle.start();
        var callbacks = new AtomicInteger();
        lifecycle.stop(callbacks::incrementAndGet);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(callbacks).hasValue(1);
            assertThat(lifecycle.isRunning()).isFalse();
        });
    }

    /** Synchronous SDK subscription failures still release the lifecycle phase safely. */
    @Test void releasesShutdownCallbackOnTransportFailure() {
        var transport = mock(McpStreamableServerTransportProvider.class);
        when(transport.closeGracefully()).thenThrow(new IllegalStateException("test failure"));
        var lifecycle = new StreamableHttpShutdownLifecycle(transport);
        lifecycle.start();
        var callbacks = new AtomicInteger();
        lifecycle.stop(callbacks::incrementAndGet);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(callbacks).hasValue(1);
            assertThat(lifecycle.isRunning()).isFalse();
        });
    }
}
