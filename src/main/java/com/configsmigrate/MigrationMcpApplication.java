package com.configsmigrate;

import com.configsmigrate.config.MigrationProperties;
import com.configsmigrate.launcher.LauncherLifecycleListener;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationMcpApplication {
    /** Starts the selected MCP transport and binds its server-side migration settings. */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(MigrationMcpApplication.class);
        // Register before startup so detached launchers can observe readiness and early bind failures.
        application.addListeners(new LauncherLifecycleListener());
        application.setBannerMode(Banner.Mode.OFF);
        application.run(args);
    }
}
