package com.configsmigrate.launcher;

import com.configsmigrate.MigrationMcpApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LauncherLifecycleListenerTest {
    private final LauncherLifecycleListener listener = new LauncherLifecycleListener();
    private final SpringApplication application = new SpringApplication(MigrationMcpApplication.class);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private PrintStream previousOutput;
    private PrintStream previousErrors;
    private String previousMarker;

    /** Captures only the lifecycle signals and preserves process-wide settings for other tests. */
    @BeforeEach
    void captureStreams() {
        previousOutput = System.out;
        previousErrors = System.err;
        previousMarker = System.getProperty("configs.migrate.launcher");
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        System.clearProperty("configs.migrate.launcher");
    }

    /** Restores global streams and the original launcher marker even when an assertion fails. */
    @AfterEach
    void restoreStreams() {
        System.setOut(previousOutput);
        System.setErr(previousErrors);
        if (previousMarker == null) System.clearProperty("configs.migrate.launcher");
        else System.setProperty("configs.migrate.launcher", previousMarker);
    }

    /** Ensures normal IDE, Maven, and externally managed starts receive no launcher-only output. */
    @Test
    void remainsSilentWithoutLauncherMarker() {
        listener.onApplicationEvent(new ApplicationReadyEvent(application, new String[0], new TestContext(), Duration.ZERO));
        listener.onApplicationEvent(new ApplicationFailedEvent(application, new String[0], null, new PortInUseException(8284)));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /** Reports the server's actual bound port rather than its possibly-zero configured value. */
    @Test
    void reportsActualPidAndBoundPortForManagedStarts() {
        System.setProperty("configs.migrate.launcher", "/test/deployment");
        listener.onApplicationEvent(new ApplicationReadyEvent(application, new String[0], new TestContext(), Duration.ZERO));
        assertThat(output.toString(StandardCharsets.UTF_8)).contains(
                "CONFIGS_MIGRATE_READY pid=" + ProcessHandle.current().pid() + " port=43210");
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /** Extracts a nested bind conflict without disclosing the surrounding exception details. */
    @Test
    void reportsNestedPortConflictWithoutLeakingExceptionContent() {
        System.setProperty("configs.migrate.launcher", "/test/deployment");
        listener.onApplicationEvent(new ApplicationFailedEvent(application, new String[0], null,
                new IllegalStateException("PRIVATE_EXCEPTION_DETAILS", new PortInUseException(8285))));
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("CONFIGS_MIGRATE_PORT_CONFLICT port=8285")
                .doesNotContain("PRIVATE_EXCEPTION_DETAILS");
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /** Avoids inventing a port for non-web contexts or misclassifying unrelated startup failures. */
    @Test
    void ignoresNonWebReadinessAndUnrelatedFailures() {
        System.setProperty("configs.migrate.launcher", "/test/deployment");
        listener.onApplicationEvent(new ApplicationReadyEvent(application, new String[0], new GenericApplicationContext(), Duration.ZERO));
        listener.onApplicationEvent(new ApplicationFailedEvent(application, new String[0], null, new IllegalArgumentException("invalid settings")));
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    private static final class TestContext extends GenericApplicationContext implements WebServerApplicationContext {
        /** Supplies a resolved server port without binding a real socket in this event contract test. */
        @Override
        public WebServer getWebServer() {
            return new WebServer() {
                /** Keeps this synthetic server inert; only its resolved port is relevant. */
                @Override public void start() { }
                /** Requires no cleanup because the synthetic server owns no resources. */
                @Override public void stop() { }
                /** Returns the actual port used to verify the launcher signal contract. */
                @Override public int getPort() { return 43210; }
            };
        }

        /** Identifies this fixture as the primary application server rather than a management server. */
        @Override public String getServerNamespace() { return null; }
    }
}
