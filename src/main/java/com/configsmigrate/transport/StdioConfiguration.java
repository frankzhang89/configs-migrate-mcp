package com.configsmigrate.transport;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Reuses the SDK stdio protocol while making stdin closure end the owning application. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
public class StdioConfiguration {
    /** Creates independent lifecycle state for this process only. */
    @Bean
    StdioLifecycle stdioLifecycle(ConfigurableApplicationContext context) {
        return new StdioLifecycle(context);
    }

    /** Registers the shared tools on stdin/stdout without starting an HTTP transport. */
    @Bean
    McpServerTransportProviderBase stdioServerTransport(StdioLifecycle lifecycle,
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper) {
        // Observe the SDK's own stream; a second reader would consume protocol messages.
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(mapper),
                lifecycle.observe(System.in), System.out);
    }
}
