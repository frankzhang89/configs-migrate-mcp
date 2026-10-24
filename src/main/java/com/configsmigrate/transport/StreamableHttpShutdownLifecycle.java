package com.configsmigrate.transport;

import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/** Ends MCP listening streams before Tomcat waits for active HTTP requests to drain. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(prefix = "spring.ai.mcp.server", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "STREAMABLE")
public final class StreamableHttpShutdownLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(StreamableHttpShutdownLifecycle.class);
    private final Mono<Void> shutdown;
    private volatile boolean running;

    /** Bounds stream cleanup independently from the normal HTTP request grace period. */
    @Autowired
    public StreamableHttpShutdownLifecycle(McpStreamableServerTransportProvider transport) {
        this(transport, Duration.ofSeconds(5));
    }

    /** Allows deterministic timeout checks without slowing the lifecycle tests. */
    StreamableHttpShutdownLifecycle(McpStreamableServerTransportProvider transport, Duration timeout) {
        // SDK cleanup may block while subscribing; move it off the lifecycle thread before applying a timeout.
        this.shutdown = Mono.defer(transport::closeGracefully).subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout).doOnError(error -> log.warn("MCP stream cleanup did not complete normally ({})",
                        error.getClass().getSimpleName()))
                .onErrorComplete().doFinally(signal -> running = false).cache();
    }

    /** Marks the HTTP transport ready for lifecycle-managed shutdown. */
    @Override public void start() { running = true; }

    /** Supports callers that require synchronous lifecycle cleanup. */
    @Override public void stop() { shutdown.block(); }

    /** Releases Spring's shutdown phase after cleanup, including timeout or transport failure. */
    @Override public void stop(Runnable callback) { shutdown.doFinally(signal -> callback.run()).subscribe(); }

    /** Reports whether cleanup has completed. */
    @Override public boolean isRunning() { return running; }

    /** Higher stop phases run first, so SSE is closed before Tomcat starts graceful shutdown. */
    @Override public int getPhase() { return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1; }
}
