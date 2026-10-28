package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.elasticsearch.ElasticsearchRestClient;
import com.dameng.mcp.config.ElasticsearchExportProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchExportServiceTest {

    @TempDir
    Path tempDir;

    private ElasticsearchExportService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void exportsCompleteSourcesWithPitAndSearchAfter() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ElasticsearchRestClient client = mock(ElasticsearchRestClient.class);
        DataSourceRegistry registry = mock(DataSourceRegistry.class);
        when(registry.getElasticsearch("logs")).thenReturn(client);
        when(client.openPointInTime("app_access_log", "5m")).thenReturn("pit-1");

        JsonNode firstPage = mapper.readTree("""
                {"pit_id":"pit-2","hits":{"total":{"value":2,"relation":"eq"},"hits":[
                  {"_index":"app_access_log","_id":"a","_source":{"event":"login"},"sort":[1]},
                  {"_index":"app_access_log","_id":"b","_source":{"event":"logout"},"sort":[2]}
                ]}}
                """);
        JsonNode emptyPage = mapper.readTree("""
                {"pit_id":"pit-3","hits":{"total":{"value":2,"relation":"eq"},"hits":[]}}
                """);
        when(client.searchPointInTime(anyString())).thenReturn(firstPage, emptyPage);

        ElasticsearchExportProperties properties = properties();
        service = new ElasticsearchExportService(registry, properties, mapper);
        Map<String, Object> started = service.start("logs", "app_access_log",
                "{\"query\":{\"term\":{\"type\":\"security\"}}}", 100, 1000L);
        Map<String, Object> completed = awaitTerminal((String) started.get("export_id"));

        assertThat(completed.get("status")).isEqualTo("completed");
        assertThat(completed.get("rows_written")).isEqualTo(2L);
        assertThat(completed.get("file_count")).isEqualTo(1);
        Path output = Path.of((String) completed.get("output_directory"));
        List<String> lines = Files.readAllLines(output.resolve("part-00001.ndjson"));
        assertThat(lines).hasSize(2);
        assertThat(mapper.readTree(lines.get(0)).path("_source").path("event").asText()).isEqualTo("login");
        assertThat(Files.readString(output.resolve("manifest.json"))).contains("sha256", "completed");

        ArgumentCaptor<String> searches = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).searchPointInTime(searches.capture());
        assertThat(searches.getAllValues().get(0)).contains("\"pit\"", "\"_shard_doc\"", "\"_source\":true");
        assertThat(searches.getAllValues().get(1)).contains("\"search_after\":[2]");
        verify(client).closePointInTime("pit-3");
    }

    @Test
    void rejectsPaginationControlsAndUnsafeIndexNames() {
        ObjectMapper mapper = new ObjectMapper();
        DataSourceRegistry registry = mock(DataSourceRegistry.class);
        when(registry.getElasticsearch(anyString())).thenReturn(mock(ElasticsearchRestClient.class));
        service = new ElasticsearchExportService(registry, properties(), mapper);

        assertThatThrownBy(() -> service.start("logs", "../secret", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start("logs", "app_access_log", "{\"size\":10}", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    private ElasticsearchExportProperties properties() {
        ElasticsearchExportProperties properties = new ElasticsearchExportProperties();
        properties.setDirectory(tempDir.toString());
        properties.setRowsPerFile(10);
        return properties;
    }

    private Map<String, Object> awaitTerminal(String exportId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> status = service.status(exportId);
            if (List.of("completed", "failed", "cancelled").contains(status.get("status"))) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("export did not finish in time");
    }
}
