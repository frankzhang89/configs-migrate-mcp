package com.configsmigrate.adapter;

import com.configsmigrate.config.MigrationProperties;
import com.configsmigrate.core.*;
import com.configsmigrate.core.ConfigModels.*;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.configsmigrate.core.ConfigModels.sha256;
import static com.configsmigrate.core.MigrationException.Code.*;

@Component
public class NacosConfigStore implements ConfigStore.Readable, ConfigStore.Writable {
    private final HttpTransport http;
    private final MigrationProperties.Nacos settings;
    private final String base;
    private volatile Token token;

    private record Token(String value, Instant refreshAt) { }

    /** Binds the Nacos endpoint and context path without connecting during application startup. */
    public NacosConfigStore(HttpTransport http, MigrationProperties properties) {
        this.http = http;
        this.settings = properties.to().nacos();
        String context = settings.contextPath();
        if (!context.isEmpty() && !context.startsWith("/")) context = "/" + context;
        this.base = HttpTransport.baseUrl(settings.serverAddr()) + context.replaceAll("/+$", "");
    }

    /** Identifies this adapter as the Nacos provider. */
    @Override public Provider provider() { return Provider.NACOS; }
    /** Advertises implemented Nacos document reads and whole-document writes. */
    @Override public Set<Capability> capabilities() { return Set.of(Capability.READ, Capability.WRITE); }

    /** Reads exact content, refreshing a denied token and retrying the read at most once. */
    @Override
    public ReadResult read(ReadRequest request) {
        Map<String, Object> query = parameters(request.location());
        addToken(query);
        HttpTransport.Response response = http.get(HttpTransport.uri(base, "/v1/cs/configs", query), new HttpHeaders());
        if (response.status() == 401 || response.status() == 403) {
            // A read has no mutation to replay; refresh once and avoid an unbounded retry loop.
            invalidateToken();
            query.remove("accessToken");
            addToken(query);
            response = http.get(HttpTransport.uri(base, "/v1/cs/configs", query), new HttpHeaders());
        }
        http.requireSuccess(response);
        return new ReadResult(request.location(), request.fileType().value(), response.body(), sha256(response.body()), null);
    }

    /** Publishes already-validated target content once and requires a positive Nacos acknowledgement. */
    @Override
    public PublishResult write(WriteRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        parameters(request.location()).forEach((key, value) -> form.add(key, value.toString()));
        form.add("content", request.content());
        // Publish the target declaration independently from any configured Apollo source format.
        form.add("type", request.fileType().value());
        String accessToken = accessToken();
        if (accessToken != null) form.add("accessToken", accessToken);
        HttpTransport.Response response = http.post(HttpTransport.uri(base, "/v1/cs/configs", Map.of()), form, true);
        // Never replay a publish, even when authentication fails: let the agent read and decide.
        if (response.status() == 401 || response.status() == 403) invalidateToken();
        http.requireSuccess(response);
        if (!"true".equals(response.body().trim())) {
            // HTTP 200 alone is insufficient: the v1 publication API acknowledges with true.
            throw new MigrationException(PUBLISH_FAILED, "Nacos did not acknowledge publication");
        }
        return new PublishResult(request.location(), request.fileType().value(), true, sha256(request.content()));
    }

    /** Maps the full Nacos identity to v1 API parameters, omitting tenant for public. */
    private Map<String, Object> parameters(Location location) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataId", location.dataId());
        result.put("group", location.group());
        if (!location.namespace().isEmpty()) result.put("tenant", location.namespace());
        return result;
    }

    /** Adds a usable access token when server-side credentials enable authentication. */
    private void addToken(Map<String, Object> query) {
        String value = accessToken();
        if (value != null) query.put("accessToken", value);
    }

    /** Clears a rejected token so the next eligible request obtains fresh credentials. */
    private synchronized void invalidateToken() { token = null; }

    /** Reuses a valid token or serializes login and refresh across concurrent tool requests. */
    private synchronized String accessToken() {
        if (settings.username() == null || settings.username().isBlank()) return null;
        if (token != null && Instant.now().isBefore(token.refreshAt())) return token.value();
        if (settings.password() == null || settings.password().isEmpty()) {
            throw new MigrationException(AUTHENTICATION_FAILED, "Nacos password is required when username is configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", settings.username());
        form.add("password", settings.password());
        HttpTransport.Response response = http.post(HttpTransport.uri(base, "/v1/auth/login", Map.of()), form, false);
        if (response.status() == 400 || response.status() == 401 || response.status() == 403) {
            throw new MigrationException(AUTHENTICATION_FAILED, "Nacos login failed");
        }
        http.requireSuccess(response);
        JsonNode body = http.json(response.body());
        JsonNode value = body.get("accessToken");
        JsonNode ttlNode = body.get("tokenTtl");
        long ttl = ttlNode != null && ttlNode.isNumber() ? ttlNode.asLong() : 0;
        if (value == null || !value.isString() || value.asString().isBlank() || ttl <= 0) {
            throw new MigrationException(AUTHENTICATION_FAILED, "Nacos login returned no usable token");
        }
        // Leave a small expiry margin so normal requests do not start with an almost-expired token.
        token = new Token(value.asString(), Instant.now().plusSeconds(Math.max(1, ttl - Math.min(30, ttl / 10))));
        return token.value();
    }
}
