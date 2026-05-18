package com.serviceticketrouter;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=8080"
        }
)
class ServiceTicketMcpServerTest {

    @Test
    void shouldExposeServiceTicketToolsOverMcp() {
        try (McpSyncClient client = createMcpClient("http://localhost:8080")) {
            var tools = client.listTools();

            assertThat(tools.tools())
                    .anyMatch(tool -> tool.name().equals("predictRoute"))
                    .anyMatch(tool -> tool.name().equals("create311QueueItem"));
        }
    }

    private McpSyncClient createMcpClient(String baseUrl) {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
        client.initialize();
        return client;
    }
}
