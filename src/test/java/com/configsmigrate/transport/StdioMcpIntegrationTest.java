package com.configsmigrate.transport;

import com.configsmigrate.MigrationMcpApplication;
import com.configsmigrate.support.UpstreamFixture;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** Uses real child JVMs and OS pipes, without producing an application JAR. */
@Timeout(120)
class StdioMcpIntegrationTest {
    private static final JsonMapper JSON = new JsonMapper();

    /** Exercises every tool, explicit conversion, unchanged publication, schemas, and tool errors. */
    @Test void stdioRunsBothMigrationPathsWithoutHttpOrProtocolPollution() throws Exception {
        try (var upstream = new UpstreamFixture();
                var occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var server = start(configuration(upstream), true,
                        "--server.port=" + occupied.getLocalPort(),
                        "--spring.main.web-application-type=servlet",
                        "--logging.config=file:/missing-stdio-logging.xml",
                        "--logging.level.com.configsmigrate=DEBUG")) {
            server.initialize();
            var tools = server.rpc("tools/list", Map.of()).path("result").path("tools");
            var names = new ArrayList<String>();
            for (var tool : tools) names.add(tool.path("name").asString());
            assertThat(names).containsExactlyInAnyOrder("apollo_read_config", "nacos_read_config",
                    "nacos_publish_config", "convert_config");
            for (var tool : tools) if (tool.path("name").asString().equals("nacos_publish_config")) {
                assertThat(tool.path("inputSchema").path("required").toString()).contains("dataId", "content");
                assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isTrue();
            }
            var source = server.call("apollo_read_config", Map.of());
            var converted = server.call("convert_config", Map.of("content", source.path("content").asString()));
            assertThat(converted.path("converted").asBoolean()).isTrue();
            var published = server.call("nacos_publish_config", Map.of("dataId", "stdio-converted.yaml",
                    "content", converted.path("content").asString()));
            var read = server.call("nacos_read_config", Map.of("dataId", "stdio-converted.yaml"));
            assertThat(read.path("sha256").asString()).isEqualTo(published.path("sha256").asString());
            server.call("nacos_publish_config", Map.of("dataId", "stdio-agent.yaml", "content", UpstreamFixture.YAML));
            assertThat(server.call("nacos_read_config", Map.of("dataId", "stdio-agent.yaml"))
                    .path("content").asString()).isEqualTo(UpstreamFixture.YAML);
            server.error("nacos_publish_config", Map.of("dataId", "invalid.yaml", "content", "key=value"), "INVALID_FORMAT");
            server.error("nacos_read_config", Map.of("dataId", "missing.yaml"), "NOT_FOUND");
            var invalid = server.rpc("tools/call", Map.of("name", "nacos_publish_config", "arguments", Map.of()));
            assertThat(invalid.has("error") || invalid.path("result").path("isError").asBoolean()).isTrue();
            if (System.getProperty("migrate.simulation-url") == null) assertThat(upstream.writes).hasValue(2);
            assertThat(server.process.isAlive()).isTrue();
            server.endInputAndAwaitExit(0);
            server.assertProtocolOnly();
            assertThat(server.stderr()).contains("Starting MigrationMcpApplication", "DEBUG")
                    .doesNotContain("Tomcat started", ":: Spring Boot ::", "PRIVATE_SECRET");
            Path log = StdioEnvironmentPostProcessor.deploymentDirectory().resolve("log")
                    .resolve("configs-migrate-stdio-" + server.process.pid() + ".log");
            assertThat(log).isRegularFile();
            assertThat(Files.readString(log)).contains("Starting MigrationMcpApplication");
            if (System.getProperty("migrate.simulation-url") == null) {
                assertThat(upstream.lastPublish.get("content")).isEqualTo(UpstreamFixture.YAML);
            }
        }
    }

    /** Verifies EOF before MCP initialization still releases the process. */
    @Test void eofDuringStartupExitsCleanly() throws Exception {
        try (var upstream = new UpstreamFixture(); var server = start(configuration(upstream), true)) {
            server.endInputAndAwaitExit(0);
            server.assertProtocolOnly();
            assertThat(server.stdout).isEmpty();
        }
    }

    /** Keeps input open so startup failure itself must end the process with stderr diagnostics. */
    @Test void startupFailureHasNoStdoutDiagnostics() throws Exception {
        try (var upstream = new UpstreamFixture();
                var server = start(configuration(upstream), true, "--migrate.http.read-timeout=0s")) {
            assertThat(server.process.waitFor(40, TimeUnit.SECONDS)).isTrue();
            assertThat(server.process.exitValue()).isNotZero();
            server.joinReaders();
            server.assertProtocolOnly();
            assertThat(server.stderr()).contains("APPLICATION FAILED TO START");
        }
    }

    /** Runs both transports with the same external YAML and checks shutdown independence in both directions. */
    @Test void httpAndStdioRunSimultaneouslyAndStopIndependently() throws Exception {
        try (var upstream = new UpstreamFixture()) {
            Path config = configuration(upstream);
            try (var http = start(config, false); var stdio = start(config, true)) {
                int port = http.httpPort();
                var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
                try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
                    client.initialize();
                    stdio.initialize();
                    assertThat(client.listTools().tools()).hasSize(4);
                    assertThat(stdio.call("apollo_read_config", Map.of()).path("releaseKey").asString()).isEqualTo("release-1");
                    var source = JSON.valueToTree(client.callTool(new CallToolRequest("apollo_read_config", Map.of())).structuredContent());
                    var converted = JSON.valueToTree(client.callTool(new CallToolRequest("convert_config",
                            Map.of("content", source.path("content").asString()))).structuredContent());
                    client.callTool(new CallToolRequest("nacos_publish_config",
                            Map.of("dataId", "shared.yaml", "content", converted.path("content").asString())));
                    assertThat(stdio.call("nacos_read_config", Map.of("dataId", "shared.yaml"))
                            .path("content").asString()).isEqualTo(converted.path("content").asString());
                    stdio.endInputAndAwaitExit(0);
                    assertThat(http.process.isAlive()).isTrue();
                    assertThat(client.listTools().tools()).hasSize(4);
                }
                try (var secondStdio = start(config, true)) {
                    secondStdio.initialize();
                    http.close();
                    assertThat(secondStdio.process.isAlive()).isTrue();
                    assertThat(secondStdio.call("nacos_read_config", Map.of("dataId", "shared.yaml"))
                            .path("content").asString()).isNotEmpty();
                    secondStdio.endInputAndAwaitExit(0);
                    secondStdio.assertProtocolOnly();
                }
                stdio.assertProtocolOnly();
            }
        }
    }

    /** Writes a shared external configuration in a path containing spaces, away from the child's cwd. */
    private Path configuration(UpstreamFixture upstream) throws IOException {
        // The optional checked-in Postman simulation exercises the same real child-process path.
        String simulation = System.getProperty("migrate.simulation-url");
        String apollo = simulation == null ? upstream.apollo() : simulation + "/apollo";
        String nacos = simulation == null ? upstream.base() : simulation;
        Path directory = Files.createTempDirectory(Path.of("target"), "stdio config ").toAbsolutePath();
        Path config = directory.resolve("application.yml");
        Files.writeString(config, """
                server:
                  port: 0
                spring:
                  ai:
                    mcp:
                      server:
                        name: configs-migrate-mcp
                        version: 0.1.0
                        type: SYNC
                        protocol: STREAMABLE
                        stdio: false
                migrate:
                  from:
                    apollo:
                      meta: %s
                      app-id: source-app
                  to:
                    nacos:
                      server-addr: %s
                      username: user
                      password: password
                """.formatted(apollo, nacos), StandardCharsets.UTF_8);
        return config;
    }

    /** Launches the real entry point with absolute classpath/config paths and live stdin/stdout pipes. */
    private Server start(Path config, boolean stdio, String... overrides) throws IOException {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String absoluteClasspath = String.join(File.pathSeparator, Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().toString()).toList());
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dconfigs.migrate.launcher=integration-test", "-cp", absoluteClasspath,
                MigrationMcpApplication.class.getName(), "--spring.config.location=" + config.toUri(),
                "--spring.ai.mcp.server.stdio=" + stdio, "--spring.output.ansi.enabled=never"));
        command.addAll(List.of(overrides));
        Path working = Files.createDirectories(config.getParent().resolve("different working directory"));
        return new Server(new ProcessBuilder(command).directory(working.toFile()).start());
    }

    /** Minimal JSON-RPC client that also records every byte-line emitted by the child. */
    private static final class Server implements AutoCloseable {
        final Process process;
        final List<String> stdout = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final BlockingQueue<JsonNode> responses = new LinkedBlockingQueue<>();
        final BufferedWriter input;
        final Thread outputReader;
        final Thread errorReader;
        int nextId;

        /** Starts independent pipe drainers so verbose diagnostics cannot deadlock the child. */
        Server(Process process) {
            this.process = process;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            outputReader = drain(process.getInputStream(), stdout, true);
            errorReader = drain(process.getErrorStream(), errors, false);
        }

        /** Records stdout and queues only valid JSON objects as candidate RPC responses. */
        Thread drain(InputStream stream, List<String> lines, boolean protocol) {
            return Thread.ofPlatform().daemon().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        if (protocol) try { responses.add(JSON.readTree(line)); } catch (RuntimeException ignored) { }
                    }
                } catch (IOException error) { errors.add("Pipe read failed: " + error.getClass().getSimpleName()); }
            });
        }

        /** Completes the MCP initialize handshake on this process's own connection. */
        void initialize() throws Exception {
            var initialized = rpc("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "stdio-test", "version", "1")));
            assertThat(initialized.path("result").path("serverInfo").path("name").asString()).isEqualTo("configs-migrate-mcp");
            send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        }

        /** Sends an RPC request and waits for its matching response while ignoring notifications. */
        JsonNode rpc(String method, Map<String, ?> params) throws Exception {
            int id = ++nextId;
            send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline) {
                JsonNode response = responses.poll(100, TimeUnit.MILLISECONDS);
                if (response != null && response.path("id").asInt(-1) == id) return response;
                assertThat(process.isAlive()).as(stderr()).isTrue();
            }
            throw new AssertionError("No response for " + method + ": " + stderr());
        }

        /** Writes one UTF-8 JSON-RPC line without shell or terminal mediation. */
        void send(Map<String, ?> message) throws IOException {
            input.write(JSON.writeValueAsString(message));
            input.newLine();
            input.flush();
        }

        /** Calls a tool and returns the structured payload only after checking its error flag. */
        JsonNode call(String name, Map<String, ?> arguments) throws Exception {
            var response = rpc("tools/call", Map.of("name", name, "arguments", arguments));
            assertThat(response.has("error")).as(response.toString()).isFalse();
            assertThat(response.path("result").path("isError").asBoolean()).as(response.toString()).isFalse();
            return response.path("result").path("structuredContent");
        }

        /** Checks that service failures remain stable MCP tool errors over stdio. */
        void error(String name, Map<String, ?> arguments, String code) throws Exception {
            var result = rpc("tools/call", Map.of("name", name, "arguments", arguments)).path("result");
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.path("structuredContent").path("code").asString()).isEqualTo(code);
        }

        /** Waits for the HTTP launcher's explicit ready marker, emitted only by the HTTP process. */
        int httpPort() throws InterruptedException {
            var pattern = Pattern.compile("CONFIGS_MIGRATE_READY pid=" + process.pid() + " port=(\\d+)");
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline) {
                for (String line : stdout) {
                    var match = pattern.matcher(line);
                    if (match.matches()) return Integer.parseInt(match.group(1));
                }
                assertThat(process.isAlive()).as(stderr()).isTrue();
                Thread.sleep(50);
            }
            throw new AssertionError("HTTP startup timed out: " + stderr());
        }

        /** Closes stdin and requires the application to exit without external termination. */
        void endInputAndAwaitExit(int code) throws Exception {
            input.close();
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as(stderr()).isTrue();
            joinReaders();
            assertThat(process.exitValue()).as(stderr()).isEqualTo(code);
        }

        /** Joins both pipe drainers after process exit so trailing output participates in assertions. */
        void joinReaders() throws InterruptedException { outputReader.join(3000); errorReader.join(3000); }

        /** Requires every stdout line, including startup/shutdown output, to be a JSON-RPC message. */
        void assertProtocolOnly() {
            for (String line : stdout) assertThat(JSON.readTree(line).path("jsonrpc").asString()).as(line).isEqualTo("2.0");
        }

        /** Returns diagnostics for assertion failures without mixing them into the protocol queue. */
        String stderr() { return String.join("\n", errors); }

        /** Always reaps test-owned children, including when an assertion fails. */
        @Override public void close() throws Exception {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                }
            }
            joinReaders();
        }
    }
}
