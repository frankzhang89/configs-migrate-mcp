package com.configsmigrate.transport;

import com.configsmigrate.MigrationMcpApplication;
import com.configsmigrate.support.UpstreamFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Keeps real SSE connections open while shutting down the full HTTP application. */
@ExtendWith(OutputCaptureExtension.class)
@Timeout(40)
class StreamableShutdownTest {
    private final JsonMapper json = new JsonMapper();

    /** An idle MCP listening stream must close before Tomcat starts draining requests. */
    @Test void closesIdleSseWithoutWaitingForTheTomcatGracePeriod(CapturedOutput output) throws Exception {
        try (var upstream = new UpstreamFixture(); var application = start(upstream);
                var client = HttpClient.newHttpClient()) {
            URI endpoint = endpoint(application);
            String session = initialize(client, endpoint);
            try (InputStream stream = listen(client, endpoint, session)) {
                var eof = CompletableFuture.supplyAsync(() -> {
                    try { return stream.readAllBytes(); }
                    catch (Exception error) { throw new CompletionException(error); }
                });
                long started = System.nanoTime();
                application.close();
                long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                System.out.println("Shutdown with an open MCP SSE stream took " + millis + " ms");
                assertThat(millis).isLessThan(3000);
                eof.get(3, TimeUnit.SECONDS);
                assertThat(output.getAll()).doesNotContain("Graceful shutdown aborted");
            }
        }
    }

    /** Closing idle listening streams must still allow an already-running publication to finish. */
    @Test void letsAnInFlightPublishCompleteDuringShutdown(CapturedOutput output) throws Exception {
        try (var upstream = new UpstreamFixture(); var application = start(upstream);
                var client = HttpClient.newHttpClient()) {
            URI endpoint = endpoint(application);
            String session = initialize(client, endpoint);
            try (InputStream stream = listen(client, endpoint, session)) {
                upstream.delayMillis = 600;
                var request = request(endpoint).header("mcp-session-id", session)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                                "jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", Map.of(
                                        "name", "nacos_publish_config", "arguments", Map.of("dataId", "shutdown.yaml",
                                                "fileType", "yaml", "content", "app:\n  name: 'shutdown-test'\n")))))).build();
                var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                await().atMost(Duration.ofSeconds(5)).until(() -> upstream.writes.get() == 1);
                application.close();
                var response = pending.get(3, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"published\":true").doesNotContain("\"isError\":true");
                assertThat(upstream.configurations).containsValue("app:\n  name: 'shutdown-test'\n");
                assertThat(output.getAll()).doesNotContain("Graceful shutdown aborted");
            }
        }
    }

    /** Uses only local peers and a shorter grace period so the original regression fails quickly. */
    private ConfigurableApplicationContext start(UpstreamFixture upstream) {
        return SpringApplication.run(MigrationMcpApplication.class,
                "--spring.config.location=classpath:/application-demo.yml", "--spring.main.banner-mode=off",
                "--server.port=0", "--server.shutdown=graceful", "--spring.lifecycle.timeout-per-shutdown-phase=4s",
                "--spring.ai.mcp.server.stdio=false", "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp",
                "--spring.ai.mcp.server.streamable-http.keep-alive-interval=100ms",
                "--migrate.from.apollo.meta=" + upstream.apollo(),
                "--migrate.to.nacos.server-addr=" + upstream.base(), "--migrate.to.nacos.context-path=/nacos",
                "--migrate.to.nacos.namespace=", "--migrate.to.nacos.username=user", "--migrate.to.nacos.password=password");
    }

    /** Resolves the actual primary listening port, including port-zero deployments. */
    private URI endpoint(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    /** Supplies the mandatory Streamable HTTP headers for JSON-RPC POST requests. */
    private HttpRequest.Builder request(URI endpoint) {
        return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json");
    }

    /** Creates a server session without a client that might proactively close SSE during shutdown. */
    private String initialize(HttpClient client, URI endpoint) throws Exception {
        var response = client.send(request(endpoint).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                        "protocolVersion", "2025-11-25", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "shutdown-test", "version", "1")))))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("mcp-session-id").orElseThrow();
    }

    /** Opens and retains the indefinite GET stream; keep-alive traffic flushes its headers. */
    private InputStream listen(HttpClient client, URI endpoint, String session) throws Exception {
        var response = client.sendAsync(HttpRequest.newBuilder(endpoint).header("Accept", "text/event-stream")
                .header("mcp-session-id", session).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                .get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
