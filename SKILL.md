---
name: configs-migrate-mcp
description: Read published Apollo configurations, explicitly convert properties and YAML, and read or publish Nacos 2.x configurations through the configs-migrate MCP service. Use for agent-assisted configuration migration and verification.
---

# Configuration migration with MCP

Use the connected configs-migrate MCP service to read, convert, publish, and verify configurations. The default Streamable HTTP endpoint is `http://localhost:8284/mcp`; a deployment may use another address. The same JAR supports stdio when the client launches Java directly with `--spring.ai.mcp.server.stdio=true`. Use absolute Java/JAR/config paths and keep stdin/stdout attached to the client; background scripts manage HTTP only. Stdio has no service port, writes diagnostics to stderr and a PID-specific deployment `log` file, and exits when stdin closes. See [README.md](README.md) for connection examples, configuration defaults, and detailed format limits.

HTTP and stdio may run as independent processes with identical tools. They have separate token caches and lifecycle state, even with the same YAML. Select the intended client connection explicitly. Stopping the scripted HTTP process does not stop stdio. Concurrent publication to the same target remains whole-document replacement without CAS protection.

## Available tools

Discover tools on the connected server. Client-specific prefixes may differ; server names and arguments are:

| Tool | Required arguments | Optional arguments |
| --- | --- | --- |
| `apollo_read_config` | None | `appId`, `cluster`, `namespace`, `fileType` |
| `convert_config` | `content` | `sourceType`, `targetType` |
| `nacos_read_config` | `dataId` | `group`, `namespace`, `fileType` |
| `nacos_publish_config` | `dataId`, `content` | `group`, `namespace`, `fileType` |

Use the available MCP connection. If it is missing, identify that requirement rather than asking for upstream credentials as tool arguments. Addresses and credentials are managed by server configuration.

## Resolve source and target

- Read published content with `apollo_read_config`. It does not enumerate namespaces or read unpublished edits. For YAML, prefer the exact namespace including `.yaml` or `.yml`.
- Resolve target `dataId` from the user's task; do not infer it from the Apollo app ID. Use the intended group and namespace **ID**, not its display name. An explicit empty namespace or `public` selects public; omission uses configured defaults.
- Respect existing task authorization. Publication immediately creates or replaces the whole target document; the server does not merge documents or add an approval step. Read the current target when comparison is relevant to the task.
- Prefer explicit actual source and intended target formats when known; `yaml` and `yml` are equivalent. Omitted formats use server defaults.

## Convert only when needed

Decide from the **actual input format**, not merely from configured `from` and `to` values:

- If content already matches the target format, publish directly. This includes content converted by the calling agent even when configured source and target formats differ.
- Otherwise call `convert_config` with complete content, actual `sourceType`, and intended `targetType`. Pass the returned content onward without reconstructing it from a summary.
- Equal-format conversion validates and returns the original content with `converted=false`.

Conversion expands dotted keys and continuous `[index]` paths into YAML containers and keeps properties values as strings. It rejects duplicate keys, path conflicts, unsupported YAML, and ambiguous or lossy structures. Correct or report such problems rather than dropping keys or coercing values silently. Cross-format conversion does not retain comments, formatting, or YAML type metadata.

## Publish and verify

1. Call `nacos_publish_config` with exact `dataId`, complete target-format `content`, and intended `fileType`, group, and namespace. Publication validates and sends content unchanged; it never converts.
2. Check `isError` before using each result. Successful data is in `structuredContent`; JSON text content carries the same result for clients without structured-content support.
3. Call `nacos_read_config` with the **same target identity** and compare its `sha256` with the publish result. Empty content is a valid document, not a missing configuration.
4. Report source location, target location, conversion performed, and read-back hash agreement. Publication acknowledgement alone is not verification. Report a mismatch as unresolved rather than automatically overwriting concurrent changes.

Examples below are MCP `tools/call` parameter objects, not REST requests:

```json
{"name":"apollo_read_config","arguments":{"namespace":"application","fileType":"properties"}}
{"name":"convert_config","arguments":{"content":"app.name=orders\n","sourceType":"properties","targetType":"yaml"}}
```

Pass the actual conversion output into publication, or skip conversion when content is already YAML:

```json
{"name":"nacos_publish_config","arguments":{"dataId":"orders.yaml","group":"DEFAULT_GROUP","namespace":"","fileType":"yaml","content":"app:\n  name: 'orders'\n"}}
{"name":"nacos_read_config","arguments":{"dataId":"orders.yaml","group":"DEFAULT_GROUP","namespace":"","fileType":"yaml"}}
```

## Recover from errors

- `INVALID_ARGUMENT`, `INVALID_FORMAT`, `CONVERSION_CONFLICT`: correct identifiers, declared format, or document structure. Do not guess another format or discard content silently. Properties and YAML syntax overlap, so validation cannot establish intended format.
- `NOT_FOUND`: check exact identity and, for Apollo, whether the namespace was published. This does not mean empty content.
- `AUTHENTICATION_FAILED`: check the server's actual configuration location and whether its process was restarted after credential changes. YAML settings are loaded at startup; an existing stdio connection can retain old credentials until the server is relaunched. Report the service-side authentication problem without exposing credentials or repeatedly attempting publication.
- `TIMEOUT`, `NETWORK_ERROR`, `UPSTREAM_ERROR`: distinguish failed reads/logins from publication results. Retry reads within task scope once the cause is understood.
- `PUBLISH_FAILED`: Nacos did not acknowledge publication; report the failure and inspect the target when needed.
- `WRITE_RESULT_UNKNOWN`: a write may already have occurred. Read the same target before any retry. A matching hash verifies intended content; otherwise report current state and decide the next action within existing authorization. Never blindly replay the write.
- `UNSUPPORTED_CAPABILITY`: the requested provider capability is not implemented. Apollo writes and etcd access are reserved; searches, deletion, and batch orchestration are not exposed as tools.

Configuration content may contain secrets. Use full content in authorized tool calls, but avoid copying secrets into progress messages or final reports. Prefer reporting locations and hashes.
