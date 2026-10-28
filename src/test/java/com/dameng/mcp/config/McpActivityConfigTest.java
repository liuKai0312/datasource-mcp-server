package com.dameng.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.assertThat;

class McpActivityConfigTest {
    @Test void exampleConfigurationsHaveValidHttpAndStdioDefaults() throws Exception {
        for (String file : new String[]{"application.yml.example", "application-demo.yml"}) {
            var sources = new YamlPropertySourceLoader().load(file,
                    new FileSystemResource("src/main/resources/" + file));
            assertThat(sources.get(0).getProperty("mcp.activity.confirmation-enabled")).isEqualTo(true);
            assertThat(sources.get(0).getProperty("mcp.activity.directory")).isEqualTo("./datasource-mcp/activity");
            assertThat(sources.get(0).getProperty("spring.ai.mcp.server.name")).isEqualTo("dameng-mcp-server");
            assertThat(sources).anySatisfy(source -> {
                assertThat(source.getProperty("spring.config.activate.on-profile")).isEqualTo("stdio");
                assertThat(source.getProperty("mcp.activity.confirmation-enabled")).isEqualTo(false);
            });
        }
    }
}
