package com.configsmigrate.mcp;

import com.configsmigrate.core.ConfigService;
import com.configsmigrate.core.MigrationException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.function.Supplier;

@Component
public class ConfigTools {
    private final ConfigService service;
    private final JsonMapper json = new JsonMapper();

    /** Connects the MCP-facing tools to the provider-neutral configuration service. */
    public ConfigTools(ConfigService service) { this.service = service; }

    /** Exposes published Apollo reads with optional overrides for source identifiers and format. */
    @McpTool(name = "apollo_read_config", description = "Read a published Apollo namespace. Optional parameters override server defaults. YAML namespace names include .yaml/.yml; a missing suffix is appended. Returns content, location, releaseKey and SHA-256. Does not read unpublished edits.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult apolloRead(
            @McpToolParam(description = "Apollo app ID; defaults to migrate.from.apollo.app-id", required = false) String appId,
            @McpToolParam(description = "Apollo cluster; defaults to configured cluster or default", required = false) String cluster,
            @McpToolParam(description = "Apollo namespace; defaults to configured namespace or application", required = false) String namespace,
            @McpToolParam(description = "properties, yaml or yml; defaults to Apollo source format", required = false) String fileType) {
        return result(() -> service.apolloRead(appId, cluster, namespace, fileType));
    }

    /** Exposes a Nacos read while preserving the stored document and its exact content hash. */
    @McpTool(name = "nacos_read_config", description = "Read one Nacos configuration by dataId, group and namespace ID. Returns unmodified UTF-8 content, location, declared format and SHA-256. Empty content differs from a missing configuration.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public CallToolResult nacosRead(
            @McpToolParam(description = "Exact target dataId; never inferred from Apollo", required = true) String dataId,
            @McpToolParam(description = "Nacos group; defaults to configured group or DEFAULT_GROUP", required = false) String group,
            @McpToolParam(description = "Nacos namespace ID, not display name; empty string/public selects public; omitted uses configured ID", required = false) String namespace,
            @McpToolParam(description = "Declared properties/yaml/yml format; defaults to configured target format; does not convert content", required = false) String fileType) {
        return result(() -> service.nacosRead(dataId, group, namespace, fileType));
    }

    /** Exposes whole-document publication; content must already match the target format. */
    @McpTool(name = "nacos_publish_config", description = "Create or OVERWRITE the ENTIRE Nacos configuration. Input must already match fileType (default: configured target format). Validates and publishes content UNMODIFIED; never converts, even when source and target formats differ. If the agent already converted the document, publish it directly. Read back after publication; on WRITE_RESULT_UNKNOWN read before any retry.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true))
    public CallToolResult nacosPublish(
            @McpToolParam(description = "Exact target dataId", required = true) String dataId,
            @McpToolParam(description = "Complete target-format document, already converted if necessary; empty string allowed", required = true) String content,
            @McpToolParam(description = "Nacos group; omitted uses configured default", required = false) String group,
            @McpToolParam(description = "Nacos namespace ID; empty string/public selects public; omitted uses configured ID", required = false) String namespace,
            @McpToolParam(description = "properties, yaml or yml; defaults to migrate.to.nacos.file-type", required = false) String fileType) {
        return result(() -> service.nacosPublish(dataId, content, group, namespace, fileType));
    }

    /** Exposes explicit offline conversion and same-format validation without any upstream mutation. */
    @McpTool(name = "convert_config", description = "Explicit properties/YAML conversion without network access. Source/target formats default to server from/to file-type. Dotted keys and [index] paths expand into YAML containers; properties values stay strings. Conflicts fail. Equal formats (yaml=yml) validate and return the original content. Skip this tool if input already has the target format.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult convert(
            @McpToolParam(description = "Document to convert", required = true) String content,
            @McpToolParam(description = "Actual input properties/yaml/yml format; defaults to source format", required = false) String sourceType,
            @McpToolParam(description = "Desired properties/yaml/yml format; defaults to target format", required = false) String targetType) {
        return result(() -> service.convert(content, sourceType, targetType));
    }

    /** Converts service results and safe failures into structured and text MCP responses. */
    private CallToolResult result(Supplier<?> operation) {
        try {
            Object value = operation.get();
            // Support clients that consume structured results and clients that only consume text content.
            return CallToolResult.builder().isError(false).structuredContent(json.convertValue(value, Map.class))
                    .addTextContent(json.writeValueAsString(value)).build();
        } catch (MigrationException e) {
            return error(e.code().name(), e.getMessage());
        } catch (RuntimeException e) {
            // Unexpected exceptions must not expose upstream URLs, credentials or configuration snippets.
            return error("INTERNAL_ERROR", "Unexpected configuration operation failure");
        }
    }

    /** Builds a stable MCP tool error without leaking internal exception or upstream response details. */
    private CallToolResult error(String code, String message) {
        Map<String, String> error = Map.of("code", code, "message", message);
        return CallToolResult.builder().isError(true).structuredContent(error)
                .addTextContent(json.writeValueAsString(error)).build();
    }
}
