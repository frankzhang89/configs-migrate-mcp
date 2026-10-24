package com.configsmigrate.launcher;

import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;

/** Emits launcher-only lifecycle signals independently of application logging configuration. */
public final class LauncherLifecycleListener implements ApplicationListener<SpringApplicationEvent> {
    /** Reports the actual primary HTTP port after startup, or an actionable port bind failure. */
    @Override
    public void onApplicationEvent(SpringApplicationEvent event) {
        if (System.getProperty("configs.migrate.launcher") == null) return;
        // Stdio has no web context, so it never receives stdout readiness markers.
        if (event instanceof ApplicationReadyEvent ready
                && ready.getApplicationContext() instanceof WebServerApplicationContext context) {
            // Read the bound port from the web server so port zero and all Spring overrides are honored.
            System.out.printf("CONFIGS_MIGRATE_READY pid=%d port=%d%n",
                    ProcessHandle.current().pid(), context.getWebServer().getPort());
        } else if (event instanceof ApplicationFailedEvent failed) {
            // Failure listeners are registered before context creation and also run for early bind errors.
            for (Throwable cause = failed.getException(); cause != null; cause = cause.getCause()) {
                if (cause instanceof PortInUseException conflict) {
                    System.err.printf("CONFIGS_MIGRATE_PORT_CONFLICT port=%d%n", conflict.getPort());
                    break;
                }
            }
        }
    }
}
