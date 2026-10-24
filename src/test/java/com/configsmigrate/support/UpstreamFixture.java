package com.configsmigrate.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP test peers, based on the documented Apollo and Nacos 2.x wire contracts. */
public final class UpstreamFixture implements AutoCloseable {
    public static final String YAML = "# agent-converted\r\napp:\r\n  name: '订单'\r\n  enabled: 'true'\r\n";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonMapper json = new JsonMapper();
    public final AtomicInteger logins = new AtomicInteger();
    public final AtomicInteger writes = new AtomicInteger();
    public final AtomicInteger configReads = new AtomicInteger();
    public final Map<String, String> configurations = new ConcurrentHashMap<>();
    public volatile String lastApolloPath;
    public volatile String lastAuthorization;
    public volatile String lastTimestamp;
    public volatile Map<String, String> lastPublish = Map.of();
    public volatile int apolloStatus = 200;
    public volatile int loginStatus = 200;
    public volatile int publishStatus = 200;
    public volatile int readStatus = 200;
    public volatile boolean rejectFirstRead;
    public volatile String publishReply = "true";
    public volatile long delayMillis;
    public volatile long tokenTtl = 18000;
    public volatile String discoveryBody;

    /** Starts synthetic Apollo and Nacos HTTP endpoints on an available loopback port. */
    public UpstreamFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Returns the fixture's loopback origin for configuring real HTTP clients. */
    public String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    /** Returns the synthetic Apollo Meta and ConfigService base URL. */
    public String apollo() { return base() + "/apollo"; }
    /** Returns the synthetic Nacos API base URL including its context path. */
    public String nacos() { return base() + "/nacos"; }

    /** Routes documented upstream calls and applies state, counters, delays, and failure settings. */
    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = decode(exchange.getRequestURI().getRawQuery());
        Map<String, String> form = decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        try {
            if (path.equals("/apollo/services/config")) {
                respond(exchange, 200, discoveryBody != null ? discoveryBody
                        : json.writeValueAsString(List.of(Map.of("homepageUrl", apollo()))), true);
            } else if (path.startsWith("/apollo/configs/")) {
                lastApolloPath = path;
                lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                lastTimestamp = exchange.getRequestHeaders().getFirst("Timestamp");
                if (delayMillis > 0) Thread.sleep(delayMillis);
                if (apolloStatus != 200) { respond(exchange, apolloStatus, "PRIVATE_UPSTREAM_ERROR", false); return; }
                String name = path.substring(path.lastIndexOf('/') + 1);
                Map<String, String> values = name.endsWith(".yaml") || name.endsWith(".yml")
                        ? Map.of("content", YAML) : Map.of("app.name", "订单", "app.enabled", "true", "app.code", "00123");
                respond(exchange, 200, json.writeValueAsString(Map.of("configurations", values, "releaseKey", "release-1")), true);
            } else if (path.equals("/nacos/v1/auth/login")) {
                int count = logins.incrementAndGet();
                respond(exchange, loginStatus, json.writeValueAsString(Map.of("accessToken", "test-token-" + count, "tokenTtl", tokenTtl)), true);
            } else if (path.equals("/nacos/v1/cs/configs")) {
                if (exchange.getRequestMethod().equals("POST")) {
                    writes.incrementAndGet();
                    lastPublish = form;
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    if (publishStatus == 200 && publishReply.equals("true")) configurations.put(key(form), form.get("content"));
                    respond(exchange, publishStatus, publishReply, false);
                } else {
                    int count = configReads.incrementAndGet();
                    if (rejectFirstRead && count == 1) { respond(exchange, 403, "expired", false); return; }
                    if (delayMillis > 0) Thread.sleep(delayMillis);
                    if (readStatus != 200) { respond(exchange, readStatus, "PRIVATE_UPSTREAM_ERROR", false); return; }
                    String content = configurations.get(key(query));
                    respond(exchange, content == null ? 404 : 200, content == null ? "not found" : content, false);
                }
            } else respond(exchange, 501, "Endpoint not modeled", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    /** Builds a storage key from the full Nacos namespace, group, and dataId identity. */
    public static String key(Map<String, String> args) {
        return args.getOrDefault("tenant", "") + "|" + args.get("group") + "|" + args.get("dataId");
    }

    /** Writes a UTF-8 response while preserving the difference between empty content and missing content. */
    private void respond(HttpExchange exchange, int status, String body, boolean jsonBody) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", (jsonBody ? "application/json" : "text/plain") + ";charset=UTF-8");
        // A length of zero means chunked, preserving an empty configuration response.
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Decodes query strings and form bodies so tests observe the actual transmitted values. */
    private static Map<String, String> decode(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw != null && !raw.isEmpty()) for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    /** Stops the fixture immediately and cancels outstanding synthetic requests. */
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
