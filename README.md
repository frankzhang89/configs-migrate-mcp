# configs-migrate-mcp

An MCP bridge for agent-assisted migration of published Apollo configurations to Nacos 2.x. Built with Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, and Maven. The default Streamable HTTP endpoint is **`http://localhost:8284/mcp`**.

The same JAR also supports agent-managed **stdio**. Select one transport per process using `spring.ai.mcp.server.stdio` (default `false`). HTTP and stdio may run simultaneously as two independent processes, with the same four tools and configuration semantics.

The service exposes four tools. Agents choose the migration sequence and target dataId. Background synchronization, batch tasks, searches, and deletion are outside the first release. Apollo writes and etcd access have extension points but are not implemented.

Load [`SKILL.md`](SKILL.md) for agent operating instructions: tool selection, conversion decisions, publication, verification, and recovery.

## Build and run

Use JDK 25 and Maven 3.6.3 or later. On Windows, select JDK 25 if the terminal defaults to an older installation:

The repository contains demo configuration only. Before the first application launch, copy [`application-demo.yml`](application-demo.yml) to `application.yml` in the project root and replace the example addresses, app ID, and credentials. Both root and classpath `application.yml` files are ignored by Git; keep your real settings local. The demo values do not connect to a real configuration center. Tests load the checked-in demo resource and use local HTTP fixtures.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
Copy-Item application-demo.yml application.yml
mvn -B -ntp -DskipTests=false test
$env:NACOS_PASSWORD = '<actual-password>'
mvn spring-boot:run
```

On Linux or macOS:

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
cp application-demo.yml application.yml
mvn -B -ntp -DskipTests=false test
export NACOS_PASSWORD='<actual-password>'
mvn spring-boot:run
```

The default HTTP port is `8284`; override it with `SERVER_PORT` or `--server.port`. This release has no MCP endpoint authentication. Upstream connections are opened on tool calls, not at startup. No LLM API key is needed because the service does not call a language model.

### Runnable JAR and output directory

The POM configures the main class and Spring Boot `repackage`. Test execution is skipped by default. Run `mvn package` when a runnable JAR is needed; the default output is `target/configs-migrate-mcp-0.1.0.jar`, which supports `java -jar`.

Use `base.export.path` to select the JAR output directory. Intermediate compilation files remain in `target`:

```powershell
mvn -B -ntp package '-Dbase.export.path=E:/deploy/configs-migrate-mcp'
```

This produces `E:/deploy/configs-migrate-mcp/configs-migrate-mcp-0.1.0.jar`. To run tests before packaging, use `mvn -DskipTests=false package`.

## Windows and Linux launchers

Keep `application.yml`, one `configs-migrate-mcp*.jar`, and the launchers in the **same directory**. The root `application.yml` is the external configuration used by these scripts. Windows requires both `configs-migrate.cmd` and its `configs-migrate.ps1` helper; Linux uses `configs-migrate.sh`. Scripts locate files relative to their own directory, even when invoked from another working directory. Logs and runtime state are written to that directory's `log` subdirectory.

On Windows:

```powershell
.\configs-migrate.cmd start --server.port=8284
.\configs-migrate.cmd stop
.\configs-migrate.cmd restart --server.port=8285 "--migrate.from.apollo.app-id=orders"
.\configs-migrate.cmd --server.port=8285
```

The last example omits the command and defaults to `restart`. Double-click `configs-migrate.cmd` without arguments to open a vertical menu ordered **restart, stop, start**. Up/Down and number keys 1/2/3 move the highlighted selection. Navigation does not pause or reset the five-second countdown; expiry executes the currently selected action. Enter executes the current selection immediately. The initial selection is restart.

On Linux, use Bash 4.4 or later with `flock`, `setsid`, `nohup`, `readlink`, and `ss` available (normally provided by util-linux, coreutils, and iproute2):

```bash
chmod +x configs-migrate.sh
./configs-migrate.sh start --server.port=8284
./configs-migrate.sh stop
./configs-migrate.sh restart --server.port=8285 '--migrate.from.apollo.app-id=orders'
./configs-migrate.sh --server.port=8285
./configs-migrate.sh
```

Both no-command Linux forms default to restart. Pass Spring Boot overrides as individual `--key=value` arguments; quote a whole argument when its value contains spaces or shell metacharacters. Arguments are passed directly to Java without shell evaluation and take precedence over YAML settings. The launchers always set `--spring.ai.mcp.server.stdio=false`, including when YAML or environment variables enable stdio. They reject conflicting stdio arguments before stopping an existing service. Other Spring overrides remain supported. The launchers let Spring Boot resolve YAML placeholders, environment variables, and profiles rather than attempting to parse configuration themselves. A dynamic port (`--server.port=0`) is supported and its actual bound value is reported.

### Select a JDK

By default, launchers use `JAVA_HOME/bin/java`, falling back to `java` on PATH when JAVA_HOME is unset. Java 25 or newer is required. To select a JDK for this service only, use an environment variable or an invocation argument:

```powershell
$env:MIGRATE_JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.1'
.\configs-migrate.cmd restart --server.port=8284
.\configs-migrate.cmd restart "--java-home=C:\Program Files\Java\jdk-25.0.1" --server.port=8284
```

```bash
MIGRATE_JAVA_HOME=/opt/jdk-25 ./configs-migrate.sh restart --server.port=8284
./configs-migrate.sh restart --java-home=/opt/jdk-25 --server.port=8284
```

Alternatively, edit `$CustomJavaHome` in `configs-migrate.ps1` or `CUSTOM_JAVA_HOME` in `configs-migrate.sh`. Selection order is `--java-home`, the in-script override, `MIGRATE_JAVA_HOME`, `JAVA_HOME`, then PATH. In-script `$JvmOptions` / `JVM_OPTIONS` arrays accept optional JVM arguments. `--java-home` is a launcher option and is not forwarded to Spring Boot. Stop works without a JDK or application JAR.

### Lifecycle and diagnostics

- Restart validates the replacement JAR, configuration, and JDK before stopping an existing managed instance. A missing process prints an informational message and continues with start; a standalone stop is also successful when nothing is running.
- Process ownership is scoped to the launcher's directory using an exact JVM marker. Stale state cannot cause an unrelated process to be terminated. A service started manually without the marker is not managed by these scripts.
- Start is idempotent for a managed instance. Only one lifecycle operation per directory may run at a time. Multiple deployed copies in separate directories are independent.
- Readiness requires Spring Boot startup completion and a listening socket owned by the new Java process. Launcher-only lifecycle signals report the actual port even with INFO logging disabled. The console reports PID, service port, and output/error log paths. In an interactive terminal it waits five seconds or until any key is pressed; closing the launcher leaves Java running. Running from an existing terminal returns to that terminal rather than closing it.
- A port bind failure reports the conflicting PID, process name, and executable path, then prompts for any key to exit. Ownership details can be unavailable if the owner exits or the OS denies access. Conflict handling does not terminate the occupying process.
- Redirected/noninteractive invocations do not wait for keyboard input. Failures return a nonzero exit code. A readiness timeout retains the marked process for a later stop/restart and prints its PID and logs.
- Set `MIGRATE_START_TIMEOUT` (default 60 seconds), `MIGRATE_STOP_TIMEOUT` (default 30 seconds), or `MIGRATE_JAR` (a colocated JAR filename) when needed. The latter resolves ambiguity if multiple application JARs are present. Linux sends SIGTERM first and may send SIGKILL to the same managed process after its shutdown deadline; Windows terminates only the marked Java process.

On graceful HTTP shutdown (for example, an IDE stop that sends a termination signal), MCP listening streams are closed in a lifecycle phase before Tomcat starts draining requests. An idle SSE connection therefore does not consume the HTTP grace period. Already-running requests can still finish, including an in-flight Nacos publication. Stream cleanup is bounded to five seconds and normal request draining still follows `spring.lifecycle.timeout-per-shutdown-phase` (Spring Boot's default is 30 seconds). This ordering applies only to Streamable HTTP; stdio retains its EOF-based lifecycle. If a real tool request exceeds the HTTP grace period, shutdown can still report active requests. Reads and writes are not automatically replayed after interruption; verify the target before retrying an uncertain publication.

## Configuration and agent connection

The complete template is [`application-demo.yml`](application-demo.yml); a matching classpath copy is available as `src/main/resources/application-demo.yml`. Copy the complete template to your deployment's `application.yml`. For an IDE launch without a private configuration file, explicitly select `--spring.config.location=classpath:/application-demo.yml` and provide real connection settings through environment variables. A normal deployment must supply its own configuration; the demo profile is not enabled automatically.

The smaller [`config/application-example.yml`](config/application-example.yml) provides optional upstream overrides after the complete configuration is in place. Load it with:

```powershell
mvn spring-boot:run '-Dspring-boot.run.arguments=--spring.config.additional-location=file:./config/application-example.yml'
```

Environment variables include `APOLLO_META`, `APOLLO_APP_ID`, `APOLLO_ACCESS_KEY`, `NACOS_SERVER_ADDR`, `NACOS_USERNAME`, and `NACOS_PASSWORD`. For Nacos without authentication, configure both username and password as empty strings. Addresses and credentials remain server-side; agents can override business identifiers and file formats through tool arguments. Application logs omit passwords, access keys, tokens, and complete configuration bodies.

Server settings are loaded at process startup and are not hot-reloaded. After changing a YAML password, restart the affected server process; for stdio, reload the MCP connection or restart its owning client. Check the explicit `--spring.config.location` argument: editing resources does not update a process configured to read an external file. IDE launches without that argument use Spring Boot's normal precedence, where the working directory's `application.yml` overrides matching classpath values. To restrict IDE configuration files to resources, use `--spring.config.location=classpath:/application.yml`.

HTTP timeouts default to 3 seconds for connection and 10 seconds for reading. Override `migrate.http.connect-timeout` and `migrate.http.read-timeout` with duration values such as `3s`.

Connect a Streamable HTTP MCP client to `http://localhost:8284/mcp`. Generic client configuration (field names may vary by client):

```json
{
  "mcpServers": {
    "configs-migrate": {
      "type": "http",
      "url": "http://localhost:8284/mcp"
    }
  }
}
```

### Stdio connection

Configure the MCP client to start Java directly with `--spring.ai.mcp.server.stdio=true`. Use absolute Java, JAR, and external YAML paths; clients may have a different working directory. Generic configuration (field names may vary by client):

```json
{
  "mcpServers": {
    "configs-migrate-stdio": {
      "command": "C:/Program Files/Java/jdk-25.0.1/bin/java.exe",
      "args": [
        "-jar",
        "E:/deploy/configs-migrate-mcp/configs-migrate-mcp-0.1.0.jar",
        "--spring.config.location=file:///E:/deploy/configs-migrate-mcp/application.yml",
        "--spring.ai.mcp.server.stdio=true"
      ]
    }
  }
}
```

On Linux, use the absolute JDK executable (for example `/opt/jdk-25/bin/java`), an absolute JAR path, and `--spring.config.location=file:/opt/configs-migrate-mcp/application.yml`. Supply upstream credential environment variables through the client's supported environment configuration. A bare `java` command uses the executable on the client's PATH. The shell launchers' `--java-home` option is not an application argument; use the Java executable path to choose a stdio JDK.

Do not run stdio through the background management scripts, `nohup`, or stdout redirection: the client must own stdin/stdout. No URL or service port is used. The stdio process disables inbound HTTP even if a conflicting web application type is supplied. Existing Banner suppression remains in the application entry point. Diagnostics go to stderr and `log/configs-migrate-stdio-<PID>.log` beside the JAR; rolling archives retain up to seven days with a 100 MB per-process cap. In classpath development runs the log directory is beneath the application classes directory. Log levels remain configurable, while stdio selects its dedicated logging configuration to protect stdout.

The process stays alive between tool calls. Closing its stdin (EOF), including before initialization completes, closes the Spring context and releases the process. Input failures also trigger cleanup; startup failures exit with diagnostics on stderr. Clients can additionally terminate their own child process.

### Run both modes simultaneously

Start HTTP with the existing scripts and register its URL in one client entry. Register a separate stdio entry that starts the same JAR with `stdio=true`; both processes may read the same external YAML. No `stdio` Spring profile is required. Give the client entries distinct names so tool routing is explicit.

Each process has independent Spring state, HTTP clients, and Nacos token caches. The scripts' directory lock and PID ownership apply only to their HTTP process; script stop/restart does not manage client-owned stdio children. Disconnecting stdio does not stop HTTP, and stopping HTTP does not stop stdio. Both modes can access the same upstream configurations; whole-document publication still has no CAS protection or cross-process write coordination.

## Tools and results

| Tool | Required arguments | Optional arguments |
| --- | --- | --- |
| `apollo_read_config` | None | `appId`, `cluster`, `namespace`, `fileType` |
| `nacos_read_config` | `dataId` | `group`, `namespace`, `fileType` |
| `nacos_publish_config` | `dataId`, `content` | `group`, `namespace`, `fileType` |
| `convert_config` | `content` | `sourceType`, `targetType` |

Omitted arguments use configured defaults; an explicit empty string is not omission. For Nacos, an empty namespace or `public` selects public. Other values must be namespace **IDs**, not display names. The default group is `DEFAULT_GROUP`. The target dataId is never inferred from the Apollo app ID or namespace.

Apollo defaults to cluster `default` and namespace `application`. For YAML, prefer the exact namespace including `.yaml` or `.yml`; a missing suffix is appended according to fileType. `yaml` and `yml` are equivalent formats.

Successful results appear both in MCP `structuredContent` and as JSON text in `content`:

- Read: `location`, `fileType`, `content`, `sha256`, and `releaseKey` (only populated for Apollo). Nacos fileType is declared by the caller or configured default; it is not detected from content.
- Publish: `location`, `fileType`, `published`, and the submitted content's `sha256`. Acknowledgement should be followed by read-back verification.
- Convert: `sourceType`, `targetType`, `converted`, `content`, and `sha256`.

### Conversion and publication are independent

`migrate.from.apollo.file-type` and `migrate.to.nacos.file-type` are defaults; **they never trigger implicit conversion**. Publication validates the declared target format, then creates or replaces the entire document unchanged. It does not merge, reformat, convert, or add a confirmation step.

If the agent has already converted properties into YAML, publish that YAML directly, even if configured source and target formats differ. Comments, quoting, whitespace, and CRLF/LF line endings remain unchanged. Equal-format calls to `convert_config` validate and return the original content with `converted=false`.

Validation checks declared syntax and cannot establish a document's origin. Properties and YAML overlap: `key: value` is valid in both, and properties permits keys without separators. Callers must declare the actual format; the service does not guess.

## Migration examples

These JSON objects are MCP `tools/call` parameter objects after initialization, not standalone REST request bodies.

### Path A: Convert with MCP

Read the source, then pass its complete `structuredContent.content` into conversion:

```json
{"name":"apollo_read_config","arguments":{"namespace":"application"}}
{"name":"convert_config","arguments":{"content":"app.name=orders\napp.enabled=true\n","sourceType":"properties","targetType":"yaml"}}
```

Publish the actual conversion output (illustrative content below), then read the exact same target identity and compare its `sha256` with the publish result:

```json
{"name":"nacos_publish_config","arguments":{"dataId":"orders.yaml","group":"DEFAULT_GROUP","namespace":"","content":"app:\n  name: 'orders'\n  enabled: 'true'\n"}}
{"name":"nacos_read_config","arguments":{"dataId":"orders.yaml","group":"DEFAULT_GROUP","namespace":""}}
```

### Path B: The agent already converted the document

Call `nacos_publish_config` directly with complete target-format content, then read back to verify it. **Do not convert it again.** This also applies when the source and target formats are equal. Check `isError` on every tool result before proceeding.

## Conversion rules and limits

- Content uses UTF-8. Properties supports Java escapes, Unicode escapes, continuation lines, empty values, and non-ASCII text. Serialization adds no timestamps or comments.
- Dotted keys expand into nested YAML objects; Spring-style `[index]` paths expand into arrays. Indices must be continuous from zero; ambiguous indices such as `01` fail.
- Properties values stay strings in YAML. Values such as `00123`, `true`, dates, and `${HOST:localhost}` are not assigned inferred types.
- YAML mappings and arrays flatten to dotted keys and `[index]` paths; scalar text becomes properties values. Cross-format conversion does not preserve comments, layout, or type metadata.
- Duplicate keys, scalar/container conflicts, object/array conflicts, literal YAML keys containing dots or brackets, and empty keys fail conversion rather than silently losing data.
- YAML must have one document, a mapping root, and string mapping keys. Null, custom tags, merge keys, and cycles are rejected. Non-cyclic aliases are allowed within expansion limits.
- Empty content is valid. An empty document or root `{}` can become an empty configuration in the other format. Nested empty maps/lists fail YAML-to-properties conversion; same-format validation permits empty collections.
- Limits are 1,048,576 Java characters and a nesting depth of 64. Upstreams can impose additional size limits.
- Apollo properties is serialized from published pairs, so Portal comments, original order, and whitespace cannot be reconstructed. Apollo YAML retains the original `content` string.

## Upstream access and errors

Apollo discovers ConfigService instances through Meta `/services/config` and reads `/configs/{appId}/{cluster}/{namespace}` with release metadata. It does not read unpublished edits, use disk caching, or start long polling. Network failures, timeouts, and upstream errors can fail over to the next discovered instance. Optional access keys use Apollo HMAC-SHA1 signing.

Nacos uses the 2.x-compatible `/v1/auth/login` and `/v1/cs/configs` HTTP APIs. Namespace IDs are sent as `tenant`; publication specifies `type`. Tokens refresh before expiry. Reads refresh and retry once after 401/403; publication is never automatically replayed. `server-addr` may omit `http://`; configure the context path separately to avoid duplicating `/nacos`.

Errors set `isError=true` and return `{ "code": "...", "message": "..." }` in `structuredContent`:

| Error code | Meaning and response |
| --- | --- |
| `INVALID_ARGUMENT` | Missing argument, unsupported format, or excessive content size |
| `INVALID_FORMAT` | Invalid declared syntax or unsupported YAML structure |
| `CONVERSION_CONFLICT` | Duplicate keys, conflicting paths, or ambiguous/lossy conversion |
| `NOT_FOUND` | Missing configuration or upstream endpoint; an Apollo namespace may not have been published |
| `AUTHENTICATION_FAILED` | Login, token, or Apollo signature failure |
| `TIMEOUT` / `NETWORK_ERROR` | Read/login timeout or network failure |
| `UPSTREAM_ERROR` | Unexpected upstream status or response structure |
| `PUBLISH_FAILED` | Nacos did not acknowledge publication |
| `WRITE_RESULT_UNKNOWN` | A write may have occurred; read back and compare hashes before deciding whether to retry |
| `UNSUPPORTED_CAPABILITY` | The requested provider capability is not implemented |
| `INTERNAL_ERROR` | Unexpected failure, without internal exceptions or configuration snippets |

Empty content is distinct from a missing configuration. Upstream error bodies and YAML parser snippets are not included in tool error messages.

## Tests and local simulation

Maven skips test execution by default. Enable it explicitly:

```powershell
mvn -B -ntp -DskipTests=false test
```

Default tests use real HTTP connections to local JDK HttpServer peers. They cover conversion, Apollo discovery/signatures/failover, Nacos token refresh, creation/overwrite, unchanged publication, format rejection, empty content, errors, and timeouts. MCP tests verify Streamable HTTP initialization, tool schemas, and both migration paths. Stdio tests launch the real entry point in separate JVMs with OS pipes, checking all four tools, protocol-only stdout, stderr/file logging, an occupied HTTP port, early EOF, startup failure, and independent shutdown of simultaneous HTTP/stdio processes. These tests compile and run without packaging a runnable JAR.

HTTP shutdown regression tests keep an actual MCP SSE connection open during application close and verify that it closes before Tomcat's grace period. A separate case confirms that an already-running publish to a local Nacos peer completes while the application shuts down. Lifecycle unit tests cover shutdown ordering, repeated stop calls, transport failures, and bounded cleanup.

A local multi-service simulation is provided for Postman CLI 1.70 or later, without login. Its synthetic peers follow the official protocols; organization-owned mocks were not searched because the CLI was signed out. Simulation results do not establish live-service acceptance.

Terminal one:

```powershell
postman simulation run postman/simulations/migration.sim.yaml --port 14900 --output ndjson --no-history
```

Terminal two, using JDK 25:

```powershell
mvn -B -ntp -DskipTests=false -Dtest=McpIntegrationTest '-Dmigrate.simulation-url=http://127.0.0.1:14900' test
# Exercise the real stdio child process against the same simulation.
mvn -B -ntp -DskipTests=false '-Dtest=StdioMcpIntegrationTest#stdioRunsBothMigrationPathsWithoutHttpOrProtocolPollution' '-Dmigrate.simulation-url=http://127.0.0.1:14900' test
```

Check both members' `request` events in the simulation log. Simulated Nacos stores state so publication, read-back, and overwrite exercise the same identity. Unknown endpoints return 501. For a port other than 14900, set `APOLLO_SIM_PUBLIC_URL=http://127.0.0.1:<port>/apollo` for Postman so Meta advertises a reachable ConfigService URL. Stop the simulation with Ctrl+C.

For live acceptance, use a dedicated test dataId and verify the consuming application's loaded configuration. Repository tests do not access the example private-network addresses.

## Extension points

`ConfigStore` declares provider and capabilities, with separate `Readable` and `Writable` interfaces. Apollo implements reads, Nacos implements reads and writes, and the provider enum reserves ETCD. Register new adapter beans to use the shared service layer. Apollo writes require a separate Portal OpenAPI integration. Unimplemented capabilities are not exposed as MCP tools.

References: [Apollo HTTP protocol](https://github.com/apolloconfig/apollo/blob/master/docs/zh/client/other-language-client-user-guide.md), [Nacos configuration API](https://nacos.io/en/docs/v1/open-api/), [Nacos 2.x authentication](https://nacos.io/docs/v2.4/guide/user/auth/), and [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html).
