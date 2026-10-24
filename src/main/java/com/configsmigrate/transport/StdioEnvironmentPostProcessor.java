package com.configsmigrate.transport;

import com.configsmigrate.MigrationMcpApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Selects stdio transport invariants before Boot initializes logging and the application context. */
public final class StdioEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    /** Runs after external configuration has been loaded, while logging is still uninitialized. */
    @Override
    public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

    /** Disables inbound HTTP and reserves stdout for MCP when stdio is explicitly enabled. */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("spring.ai.mcp.server.stdio", Boolean.class, false)) return;
        // Highest precedence prevents conflicting CLI options from enabling HTTP or stdout logging.
        // Banner handling remains centralized in MigrationMcpApplication.
        environment.getPropertySources().addFirst(new MapPropertySource("stdioTransportSettings", Map.of(
                "spring.main.web-application-type", "none",
                "logging.config", "classpath:logback-stdio.xml",
                "configs.migrate.stdio.log-directory", deploymentDirectory().resolve("log").toString(),
                "configs.migrate.stdio.process-id", Long.toString(ProcessHandle.current().pid()))));
    }

    /** Resolves logs beside the executable JAR, or inside the classpath directory during development. */
    static Path deploymentDirectory() {
        return deploymentDirectory(MigrationMcpApplication.class.getProtectionDomain().getCodeSource().getLocation());
    }

    /** Handles both file URLs and Spring Boot's executable-JAR URL connection without using the cwd. */
    static Path deploymentDirectory(URL source) {
        try {
            Path location;
            if (source.openConnection() instanceof JarURLConnection jar) {
                // Boot nested JAR connections expose the outer archive's filesystem name here.
                String archive = jar.getJarFile().getName();
                int nested = archive.indexOf("!/");
                location = Path.of(nested < 0 ? archive : archive.substring(0, nested));
            } else location = Path.of(source.toURI());
            location = location.toAbsolutePath().normalize();
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot resolve the application deployment directory", e);
        }
    }
}
