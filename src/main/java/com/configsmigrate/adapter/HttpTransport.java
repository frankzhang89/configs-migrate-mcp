package com.configsmigrate.adapter;

import com.configsmigrate.config.MigrationProperties;
import com.configsmigrate.core.MigrationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.configsmigrate.core.MigrationException.Code.*;

@Component
public class HttpTransport {
    private final RestClient client;
    private final JsonMapper json = new JsonMapper();

    /** Creates a bounded HTTP client with redirects disabled so credentials stay on the intended request. */
    public HttpTransport(MigrationProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.http().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.http().readTimeout());
        client = RestClient.builder().requestFactory(factory).build();
    }

    public record Response(int status, String body) { }

    /** Executes a read request while preserving its status for provider-specific handling. */
    public Response get(URI uri, HttpHeaders headers) {
        return request(HttpMethod.GET, uri, headers, null, false);
    }

    /** Posts form data and distinguishes configuration writes from authentication requests. */
    public Response post(URI uri, MultiValueMap<String, String> form, boolean configWrite) {
        return request(HttpMethod.POST, uri, new HttpHeaders(), form, configWrite);
    }

    /** Exchanges UTF-8 content and maps transport failures to safe read or unknown-write errors. */
    private Response request(HttpMethod method, URI uri, HttpHeaders headers,
                             MultiValueMap<String, String> form, boolean configWrite) {
        try {
            RestClient.RequestBodySpec request = client.method(method).uri(uri).headers(h -> h.addAll(headers));
            if (form != null) request.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form);
            return request.exchange((req, response) -> new Response(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            // A transport failure cannot tell us whether the server committed the document.
            if (configWrite) throw new MigrationException(WRITE_RESULT_UNKNOWN,
                    "Publish transport failed; target may have changed. Read it back before retrying");
            boolean timeout = false;
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                    timeout = true;
                }
            }
            throw new MigrationException(timeout ? TIMEOUT : NETWORK_ERROR, "Upstream HTTP request failed");
        } catch (RestClientException e) {
            throw new MigrationException(configWrite ? WRITE_RESULT_UNKNOWN : UPSTREAM_ERROR, "Upstream HTTP exchange failed");
        }
    }

    /** Maps upstream HTTP failures to stable application error codes without exposing response bodies. */
    public void requireSuccess(Response response) {
        switch (response.status()) {
            case 200 -> { }
            case 401, 403 -> throw new MigrationException(AUTHENTICATION_FAILED, "Upstream denied access");
            case 404 -> throw new MigrationException(NOT_FOUND, "Configuration or upstream endpoint does not exist");
            default -> throw new MigrationException(UPSTREAM_ERROR, "Upstream HTTP status " + response.status());
        }
    }

    /** Parses upstream JSON and suppresses parser diagnostics that could contain sensitive content. */
    public JsonNode json(String body) {
        try {
            JsonNode value = json.readTree(body);
            if (value == null || value.isMissingNode()) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) {
            throw new MigrationException(UPSTREAM_ERROR, "Upstream returned invalid JSON");
        }
    }

    /** Normalizes an HTTP(S) address and rejects credentials, queries, or fragments in the URL. */
    public static String baseUrl(String address) {
        if (address == null || address.isBlank()) throw new MigrationException(INVALID_ARGUMENT, "Server address is required");
        String value = address.contains("://") ? address : "http://" + address;
        try {
            URI uri = URI.create(value);
            if (!(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return value.replaceAll("/+$", "");
        } catch (IllegalArgumentException e) {
            throw new MigrationException(INVALID_ARGUMENT, "Server address must be an HTTP(S) URL without credentials or query");
        }
    }

    /** Expands and encodes query values so identifiers and tokens cannot alter URL structure. */
    public static URI uri(String base, String path, Map<String, ?> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base + path);
        // Expand values as URI variables so reserved characters cannot become query separators.
        query.forEach((key, value) -> builder.queryParam(key, "{" + key + "}"));
        return builder.encode().buildAndExpand(query).toUri();
    }
}
