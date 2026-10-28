package com.dameng.mcp.web;

import com.dameng.mcp.config.*;
import com.dameng.mcp.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class McpActivityControllerTest {
    @TempDir Path temp;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        var properties = new McpActivityProperties();
        properties.setDirectory(temp.toString());
        var mapper = new ObjectMapper();
        var webProperties = new DataSourceProperties();
        webProperties.getWeb().setAccessToken("test-token");
        mvc = MockMvcBuilders.standaloneSetup(new McpActivityController(
                new McpCallLogService(properties, mapper), new SqlApprovalService(properties, mapper)))
                .addFilters(new AccessTokenFilter(webProperties)).build();
    }

    @Test void allActivityEndpointsRequireTheExistingAccessToken() throws Exception {
        mvc.perform(get("/api/activity/logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/activity/confirmation")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/activity/confirmation").contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/activity/confirmation/test").contentType("application/json").content("{\"approved\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void authenticatedSettingsLogsAndValidation() throws Exception {
        mvc.perform(get("/api/activity/confirmation").header("X-Access-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(put("/api/activity/confirmation").header("X-Access-Token", "test-token")
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(get("/api/activity/logs").header("X-Access-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/activity/logs?date=../../settings").header("X-Access-Token", "test-token"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/activity/confirmation").header("X-Access-Token", "test-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/activity/confirmation/expired").header("X-Access-Token", "test-token")
                        .contentType("application/json").content("{\"approved\":true}"))
                .andExpect(status().isConflict());
    }
}
