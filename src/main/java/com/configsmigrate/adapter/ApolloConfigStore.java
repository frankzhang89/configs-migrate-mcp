package com.configsmigrate.adapter;

import com.configsmigrate.config.MigrationProperties;
import com.configsmigrate.core.*;
import com.configsmigrate.core.ConfigModels.*;
import com.configsmigrate.format.ConfigFormatService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.configsmigrate.core.ConfigModels.*;
import static com.configsmigrate.core.MigrationException.Code.*;

@Component
public class ApolloConfigStore implements ConfigStore.Readable {
    private final HttpTransport http;
    private final MigrationProperties.Apollo settings;
    private final ConfigFormatService formats;

    /** Binds the Apollo source settings, HTTP transport, and document codec. */
    public ApolloConfigStore(HttpTransport http, MigrationProperties properties, ConfigFormatService formats) {
        this.http = http;
        this.settings = properties.from().apollo();
        this.formats = formats;
    }

    /** Identifies this adapter as the Apollo provider. */
    @Override public Provider provider() { return Provider.APOLLO; }
    /** Advertises only published configuration reads; Apollo writes remain unimplemented. */
    @Override public Set<Capability> capabilities() { return Set.of(Capability.READ); }

    /** Discovers ConfigService instances and reads one published namespace with safe read failover. */
    @Override
    public ReadResult read(ReadRequest request) {
        Location location = request.location();
        String meta = HttpTransport.baseUrl(settings.meta());
        // Resolve runtime ConfigService endpoints through Meta rather than calling Portal APIs.
        URI discovery = HttpTransport.uri(meta, "/services/config", Map.of("appId", location.appId()));
        HttpTransport.Response discoveryResponse = http.get(discovery, new HttpHeaders());
        http.requireSuccess(discoveryResponse);
        JsonNode services = http.json(discoveryResponse.body());
        if (!services.isArray() || services.isEmpty()) {
            throw new MigrationException(UPSTREAM_ERROR, "Apollo Meta Server returned no ConfigService instances");
        }
        MigrationException last = null;
        for (JsonNode service : services) {
            JsonNode homepage = service.get("homepageUrl");
            if (homepage == null || !homepage.isString()) continue;
            try {
                String path = "/configs/" + segment(location.appId()) + "/" + segment(location.cluster())
                        + "/" + segment(location.namespace());
                URI uri = URI.create(HttpTransport.baseUrl(homepage.asString()) + path);
                HttpHeaders headers = signature(uri, location.appId(), settings.accessKey(), System.currentTimeMillis());
                HttpTransport.Response response = http.get(uri, headers);
                http.requireSuccess(response);
                JsonNode body = http.json(response.body());
                JsonNode configurations = body.get("configurations");
                if (configurations == null || !configurations.isObject()) {
                    throw new MigrationException(UPSTREAM_ERROR, "Apollo response lacks configurations");
                }
                String content;
                if (request.fileType() == FileType.PROPERTIES) {
                    // Apollo returns published pairs here, so Portal comments cannot be reconstructed.
                    Map<String, String> values = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonNode> entry : configurations.properties()) {
                        if (!entry.getValue().isString()) throw new MigrationException(UPSTREAM_ERROR, "Apollo properties value is not a string");
                        values.put(entry.getKey(), entry.getValue().asString());
                    }
                    content = formats.writeProperties(values);
                } else {
                    // File-based namespaces carry one content string; retain its formatting exactly.
                    JsonNode value = configurations.get("content");
                    if (value == null || !value.isString()) throw new MigrationException(UPSTREAM_ERROR, "Apollo YAML response lacks content");
                    content = value.asString();
                }
                formats.validate(content, request.fileType());
                JsonNode release = body.get("releaseKey");
                return new ReadResult(location, request.fileType().value(), content, sha256(content),
                        release != null && release.isString() ? release.asString() : null);
            } catch (MigrationException e) {
                // Retry other instances only for read-side transport or upstream failures.
                if (e.code() != NETWORK_ERROR && e.code() != TIMEOUT && e.code() != UPSTREAM_ERROR) throw e;
                last = e;
            }
        }
        if (last != null) throw last;
        throw new MigrationException(UPSTREAM_ERROR, "Apollo discovery contains no valid homepageUrl");
    }

    /** Encodes one Apollo path segment without treating identifier characters as URL delimiters. */
    private static String segment(String value) { return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8); }

    /** Creates optional Apollo HMAC-SHA1 headers from the timestamp and encoded request path/query. */
    public static HttpHeaders signature(URI uri, String appId, String accessKey, long timestamp) {
        HttpHeaders headers = new HttpHeaders();
        if (accessKey == null || accessKey.isBlank()) return headers;
        String time = Long.toString(timestamp);
        // Sign the encoded path and query exactly as transmitted, excluding scheme and host.
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(accessKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String signature = Base64.getEncoder().encodeToString(mac.doFinal((time + "\n" + pathAndQuery).getBytes(StandardCharsets.UTF_8)));
            headers.set("Authorization", "Apollo " + appId + ":" + signature);
            headers.set("Timestamp", time);
            return headers;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Apollo signature algorithm unavailable");
        }
    }
}
