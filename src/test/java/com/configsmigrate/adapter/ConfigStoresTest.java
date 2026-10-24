package com.configsmigrate.adapter;

import com.configsmigrate.config.MigrationProperties;
import com.configsmigrate.core.*;
import com.configsmigrate.core.ConfigModels.*;
import com.configsmigrate.format.ConfigFormatService;
import com.configsmigrate.support.UpstreamFixture;
import org.junit.jupiter.api.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ConfigStoresTest {
    private UpstreamFixture upstream;
    private ConfigService service;

    /** Creates isolated HTTP peers and adapters for each scenario. */
    @BeforeEach void start() throws Exception {
        upstream = new UpstreamFixture();
        service = service(Duration.ofSeconds(2));
    }
    /** Stops the HTTP peers and releases their executor after each scenario. */
    @AfterEach void stop() { upstream.close(); }

    /** Builds the real adapters against local peers with a scenario-specific read timeout. */
    private ConfigService service(Duration timeout) {
        var properties = new MigrationProperties(
                new MigrationProperties.From(new MigrationProperties.Apollo(upstream.apollo(), "source-app", null, null, "properties", "test-secret")),
                new MigrationProperties.To(new MigrationProperties.Nacos(upstream.base(), "/nacos", "user", "password", "configured-ns", "GROUP", "yaml")),
                new MigrationProperties.Http(Duration.ofSeconds(1), timeout));
        var formats = new ConfigFormatService();
        var transport = new HttpTransport(properties);
        return new ConfigService(properties, formats, List.of(new ApolloConfigStore(transport, properties, formats), new NacosConfigStore(transport, properties)));
    }

    /** Checks discovery, published properties, release metadata, and the exact Apollo signature. */
    @Test void apolloDiscoversReadsAndSignsPublishedConfig() throws Exception {
        ReadResult read = service.apolloRead(null, null, null, null);
        assertThat(read.content()).contains("app.name=订单");
        assertThat(read.releaseKey()).isEqualTo("release-1");
        assertThat(upstream.lastApolloPath).isEqualTo("/apollo/configs/source-app/default/application");
        String message = upstream.lastTimestamp + "\n" + upstream.lastApolloPath;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        assertThat(upstream.lastAuthorization).isEqualTo("Apollo source-app:" + Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))));
    }

    /** Checks YAML namespace suffix resolution and original-content preservation. */
    @Test void apolloYamlUsesActualSuffixedNamespaceAndOriginalContent() {
        ReadResult read = service.apolloRead("other-app", "cluster-a", "config.yml", "yml");
        assertThat(read.content()).isEqualTo(UpstreamFixture.YAML);
        assertThat(read.location().namespace()).isEqualTo("config.yml");
        assertThat(service.apolloRead(null, null, "config", "yaml").location().namespace()).isEqualTo("config.yaml");
    }

    /** Checks read failover and a distinct error for an unpublished or missing namespace. */
    @Test void apolloFailsOverDiscoveredInstancesAndReportsMissingPublishedNamespace() {
        upstream.discoveryBody = "[{\"homepageUrl\":\"http://127.0.0.1:1\"},{\"homepageUrl\":\"" + upstream.apollo() + "\"}]";
        assertThat(service.apolloRead(null, null, null, null).releaseKey()).isEqualTo("release-1");
        upstream.apolloStatus = 404;
        assertThatThrownBy(() -> service.apolloRead(null, null, null, null)).hasMessageContaining("NOT_FOUND");
    }

    /** Checks Nacos creation, replacement, token reuse, and unchanged agent-converted YAML. */
    @Test void nacosCreatesOverwritesAndPreservesPreconvertedContentExactly() {
        var first = service.nacosPublish("order.yaml", UpstreamFixture.YAML, null, null, null);
        assertThat(first.published()).isTrue();
        assertThat(upstream.lastPublish).containsEntry("type", "yaml").containsEntry("tenant", "configured-ns");
        assertThat(service.nacosRead("order.yaml", null, null, null).content()).isEqualTo(UpstreamFixture.YAML);
        String replacement = "app:\n  name: 'changed'\n";
        service.nacosPublish("order.yaml", replacement, null, null, null);
        assertThat(service.nacosRead("order.yaml", null, null, null).content()).isEqualTo(replacement);
        assertThat(upstream.logins).hasValue(1);
    }

    /** Checks explicit public selection and reversible encoding of identifiers and document characters. */
    @Test void explicitPublicOverridesConfiguredNamespaceAndFormEncodingPreservesCharacters() {
        String content = "app:\n  value: '中文 & + = % ${HOST:localhost}'\n";
        service.nacosPublish("app + &.yaml", content, "group + &", "", null);
        assertThat(upstream.lastPublish).doesNotContainKey("tenant");
        assertThat(service.nacosRead("app + &.yaml", "group + &", "public", null).content()).isEqualTo(content);
    }

    /** Ensures invalid target syntax is rejected before any authentication or publication request. */
    @Test void rejectsFormatMismatchBeforeAnyLoginOrWrite() {
        assertThatThrownBy(() -> service.nacosPublish("a.yaml", "app.name=hello", null, null, null))
                .hasMessageContaining("INVALID_FORMAT");
        assertThat(upstream.writes).hasValue(0);
        assertThat(upstream.logins).hasValue(0);
    }

    /** Checks that an explicit properties target retains its original content and wire type. */
    @Test void supportsPropertiesTargetOverrideWithoutImplicitYamlConversion() {
        String content = "# original\r\napp.name=订单\r\napp.enabled=true\r\n";
        service.nacosPublish("order.properties", content, null, null, "properties");
        assertThat(upstream.lastPublish).containsEntry("type", "properties").containsEntry("content", content);
        assertThat(service.nacosRead("order.properties", null, null, "properties").content()).isEqualTo(content);
    }

    /** Distinguishes a missing configuration from an existing empty document. */
    @Test void missingAndEmptyAreDifferent() {
        assertThatThrownBy(() -> service.nacosRead("missing", null, null, null)).hasMessageContaining("NOT_FOUND");
        service.nacosPublish("empty", "", null, null, null);
        assertThat(service.nacosRead("empty", null, null, null).content()).isEmpty();
    }

    /** Checks that an expired cached token causes the next request to log in again. */
    @Test void refreshesExpiredTokensOnReadAndBeforeTtlExpires() throws Exception {
        upstream.tokenTtl = 1;
        service.nacosPublish("a", "a: 'x'", null, null, null);
        Thread.sleep(1100);
        service.nacosRead("a", null, null, null);
        assertThat(upstream.logins).hasValue(2);
    }

    /** Checks one authentication refresh for reads and no automatic replay of a rejected publication. */
    @Test void retriesReadOnceAfterDeniedTokenButNeverReplaysWrite() {
        service.nacosPublish("a", "a: 'x'", null, null, null);
        upstream.rejectFirstRead = true;
        service.nacosRead("a", null, null, null);
        assertThat(upstream.logins).hasValue(2);
        upstream.publishStatus = 403;
        assertThatThrownBy(() -> service.nacosPublish("a", "a: 'y'", null, null, null)).hasMessageContaining("AUTHENTICATION_FAILED");
        assertThat(upstream.writes).hasValue(2);
    }

    /** Checks stable errors for login failure, false acknowledgement, and upstream failures. */
    @Test void propagatesAuthenticationErrorsFalsePublicationAndUpstreamErrorsSafely() {
        upstream.loginStatus = 403;
        assertThatThrownBy(() -> service.nacosRead("a", null, null, null)).hasMessageContaining("AUTHENTICATION_FAILED");
        upstream.loginStatus = 200;
        upstream.publishReply = "false";
        assertThatThrownBy(() -> service.nacosPublish("a", "a: x", null, null, null)).hasMessageContaining("PUBLISH_FAILED");
        upstream.readStatus = 500;
        assertThatThrownBy(() -> service.nacosRead("a", null, null, null)).hasMessageContaining("UPSTREAM_ERROR").hasMessageNotContaining("PRIVATE_UPSTREAM_ERROR");
    }

    /** Distinguishes a failed read from an uncertain publication without duplicating the write. */
    @Test void readTimeoutIsDistinctFromUnknownWriteResultAndNoAutomaticWriteRetry() {
        ConfigService fast = service(Duration.ofMillis(150));
        upstream.delayMillis = 500;
        assertThatThrownBy(() -> fast.apolloRead(null, null, null, null)).hasMessageContaining("TIMEOUT");
        assertThatThrownBy(() -> fast.nacosPublish("slow", "a: x", null, null, null)).hasMessageContaining("WRITE_RESULT_UNKNOWN");
        assertThat(upstream.writes).hasValue(1);
    }

    /** Checks capability boundaries and required target identifiers. */
    @Test void futureCapabilitiesAreExplicitlyUnsupported() {
        assertThatThrownBy(() -> service.writable(Provider.APOLLO)).hasMessageContaining("UNSUPPORTED_CAPABILITY");
        assertThatThrownBy(() -> service.readable(Provider.ETCD)).hasMessageContaining("UNSUPPORTED_CAPABILITY");
        assertThatThrownBy(() -> service.nacosRead("", null, null, null)).hasMessageContaining("INVALID_ARGUMENT");
    }

    /** Exercises signing with an encoded query and the optional unsigned-header path. */
    @Test void signatureIncludesEncodedQueryAndCanBeDisabled() {
        URI uri = URI.create("http://localhost/configs/a/default/b?ip=1%202");
        assertThat(ApolloConfigStore.signature(uri, "a", null, 10).isEmpty()).isTrue();
        assertThat(ApolloConfigStore.signature(uri, "a", "key", 10).getFirst("Timestamp")).isEqualTo("10");
    }
}
