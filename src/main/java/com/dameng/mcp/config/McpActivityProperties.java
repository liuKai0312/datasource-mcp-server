package com.dameng.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mcp.activity")
public class McpActivityProperties {
    private String directory = "./datasource-mcp/activity";
    private boolean confirmationEnabled = true;
    private long confirmationTimeoutSeconds = 60;
    private int maxPending = 32;
    private int retentionDays = 30;
}
