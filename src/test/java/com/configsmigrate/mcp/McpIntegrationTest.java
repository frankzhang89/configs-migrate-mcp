package com.configsmigrate.mcp;

import com.configsmigrate.MigrationMcpApplication;
import com.configsmigrate.support.UpstreamFixture;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpIntegrationTest {
    private UpstreamFixture upstream;
    private ConfigurableApplicationContext application;
    private McpSyncClient client;
    private final JsonMapper json = new JsonMapper();

    /** Starts a real MCP server against local HTTP peers or the optional Postman simulation. */
    @BeforeAll void start() throws Exception {
        // Set -Dmigrate.simulation-url=http://127.0.0.1:14900 to use the checked-in Postman simulation.
        String simulation = System.getProperty("migrate.simulation-url");
        String apollo;
        String nacos;
        if (simulation == null) {
            upstream = new UpstreamFixture();
            apollo = upstream.apollo();
            nacos = upstream.base();
        } else {
            apollo = simulation + "/apollo";
            nacos = simulation;
        }
        application = SpringApplication.run(MigrationMcpApplication.class,
                "--spring.config.location=classpath:/application-demo.yml",
                "--server.port=0", "--spring.main.banner-mode=off",
                // Isolate the fixture endpoint from user-edited deployment configuration.
                "--spring.ai.mcp.server.stdio=false", "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp",
                "--migrate.from.apollo.file-type=properties", "--migrate.to.nacos.file-type=yaml",
                "--migrate.from.apollo.meta=" + apollo,
                "--migrate.from.apollo.app-id=source-app",
                "--migrate.from.apollo.access-key=",
                "--migrate.to.nacos.server-addr=" + nacos,
                "--migrate.to.nacos.context-path=/nacos",
                "--migrate.to.nacos.username=user", "--migrate.to.nacos.password=password");
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        assertThat(client.initialize().serverInfo().name()).isEqualTo("configs-migrate-mcp");
    }

    /** Closes the MCP client, application server, and any locally owned HTTP peers. */
    @AfterAll void stop() {
        if (client != null) client.closeGracefully();
        if (application != null) application.close();
        if (upstream != null) upstream.close();
    }

    /** Checks tool discovery, required arguments, and the publication mutation hint over MCP. */
    @Test void listsExactlyFourToolsWithRequiredInputsAndMutationHints() {
        var tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder(
                "apollo_read_config", "nacos_read_config", "nacos_publish_config", "convert_config");
        Tool publish = tools.stream().filter(t -> t.name().equals("nacos_publish_config")).findFirst().orElseThrow();
        assertThat(((List<?>) publish.inputSchema().get("required")).stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("dataId", "content");
        assertThat(publish.annotations().destructiveHint()).isTrue();
        assertThat(publish.description()).contains("never converts");
        assertThat(((Map<?, ?>) publish.inputSchema().get("properties")).keySet().stream().map(Object::toString).toList())
                .contains("fileType", "group", "namespace");
    }

    /** Runs the complete read-convert-publish-verify workflow through the MCP protocol. */
    @Test void readsConvertsPublishesAndReadsBackThroughMcp() {
        JsonNode source = call("apollo_read_config", Map.of());
        assertThat(source.path("releaseKey").asString()).isEqualTo("release-1");
        JsonNode converted = call("convert_config", Map.of("content", source.path("content").asString()));
        assertThat(converted.path("converted").asBoolean()).isTrue();
        String dataId = "integration-" + UUID.randomUUID() + ".yaml";
        JsonNode published = call("nacos_publish_config", Map.of("dataId", dataId, "content", converted.path("content").asString()));
        JsonNode read = call("nacos_read_config", Map.of("dataId", dataId));
        assertThat(read.path("sha256").asString()).isEqualTo(published.path("sha256").asString());
        assertThat(read.path("content").asString()).isEqualTo(converted.path("content").asString());
    }

    /** Checks direct publication, overwrite, and same-format preservation without double conversion. */
    @Test void publishesAgentConvertedContentWithoutConvertingAndSupportsOverwrite() {
        String id = "agent-" + UUID.randomUUID() + ".yaml";
        call("nacos_publish_config", Map.of("dataId", id, "content", UpstreamFixture.YAML));
        assertThat(call("nacos_read_config", Map.of("dataId", id)).path("content").asString()).isEqualTo(UpstreamFixture.YAML);
        String changed = "# exact content\napp:\n  name: 'second'\n";
        call("nacos_publish_config", Map.of("dataId", id, "content", changed));
        assertThat(call("nacos_read_config", Map.of("dataId", id)).path("content").asString()).isEqualTo(changed);
        var same = call("convert_config", Map.of("content", changed, "sourceType", "yml", "targetType", "yaml"));
        assertThat(same.path("converted").asBoolean()).isFalse();
        assertThat(same.path("content").asString()).isEqualTo(changed);
    }

    /** Checks MCP error envelopes, sensitive-message suppression, and valid empty documents. */
    @Test void reportsStableMcpToolErrorsAndSupportsEmptyDocuments() {
        assertError("nacos_publish_config", Map.of("dataId", "invalid.yaml", "content", "app.name=value"), "INVALID_FORMAT");
        assertError("nacos_read_config", Map.of("dataId", "missing-" + UUID.randomUUID()), "NOT_FOUND");
        assertError("convert_config", Map.of("content", "a=x\na.b=y"), "CONVERSION_CONFLICT");
        assertError("convert_config", Map.of("content", "password: [PRIVATE_SECRET", "sourceType", "yaml"), "INVALID_FORMAT");
        String id = "empty-" + UUID.randomUUID();
        call("nacos_publish_config", Map.of("dataId", id, "content", ""));
        assertThat(call("nacos_read_config", Map.of("dataId", id)).path("content").asString()).isEmpty();
    }

    /** Invokes a tool, requires success, and returns its structured payload for scenario assertions. */
    private JsonNode call(String name, Map<String, Object> arguments) {
        var result = client.callTool(new CallToolRequest(name, arguments));
        assertThat(result.isError()).as(name + " error: " + result.content()).isFalse();
        return json.valueToTree(result.structuredContent());
    }

    /** Requires an MCP tool error with the expected stable code and no sensitive parser excerpt. */
    private void assertError(String name, Map<String, Object> arguments, String code) {
        var result = client.callTool(new CallToolRequest(name, arguments));
        assertThat(result.isError()).isTrue();
        JsonNode error = json.valueToTree(result.structuredContent());
        assertThat(error.path("code").asString()).isEqualTo(code);
        assertThat(error.toString()).doesNotContain("PRIVATE_SECRET");
    }
}
