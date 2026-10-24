package com.configsmigrate.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class StdioEnvironmentPostProcessorTest {
    /** Leaves all HTTP settings alone when stdio is absent or disabled. */
    @Test void httpKeepsItsExistingConfiguration() {
        for (boolean explicit : new boolean[]{false, true}) {
            var environment = new MockEnvironment().withProperty("logging.config", "custom.xml");
            if (explicit) environment.setProperty("spring.ai.mcp.server.stdio", "false");
            new StdioEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
            assertThat(environment.getPropertySources().contains("stdioTransportSettings")).isFalse();
            assertThat(environment.getProperty("logging.config")).isEqualTo("custom.xml");
        }
    }

    /** Protects stdio invariants from conflicting CLI values without adding another Banner setting. */
    @Test void stdioOverridesWebAndLoggingButLeavesCentralBannerHandlingAlone() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of(
                "spring.ai.mcp.server.stdio", true,
                "spring.main.web-application-type", "servlet", "logging.config", "custom.xml")));
        new StdioEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(environment.getProperty("logging.config")).isEqualTo("classpath:logback-stdio.xml");
        assertThat(environment.getProperty("spring.main.banner-mode")).isNull();
        assertThat(environment.getProperty("configs.migrate.stdio.process-id"))
                .isEqualTo(Long.toString(ProcessHandle.current().pid()));
        assertThat(environment.getProperty("configs.migrate.stdio.log-directory"))
                .isEqualTo(StdioEnvironmentPostProcessor.deploymentDirectory().resolve("log").toString());
    }

    /** Resolves archive locations with spaces through a JAR connection instead of the client's cwd. */
    @Test void jarLocationResolvesItsDeploymentDirectory() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "stdio archive ").toAbsolutePath();
        Path archive = directory.resolve("location-fixture.zip");
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) { }
        var source = URI.create("jar:" + archive.toUri() + "!/").toURL();
        assertThat(StdioEnvironmentPostProcessor.deploymentDirectory(source)).isEqualTo(directory);
        assertThat(StdioEnvironmentPostProcessor.deploymentDirectory(archive.toUri().toURL())).isEqualTo(directory);
    }
}
