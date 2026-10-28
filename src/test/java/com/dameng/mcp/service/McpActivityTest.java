package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.DatabaseAdapter;
import com.dameng.mcp.config.*;
import com.dameng.mcp.model.mcp.ToolException;
import com.dameng.mcp.model.mcp.ToolResponse;
import com.dameng.mcp.security.SqlSecurityValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpActivityTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();
    McpActivityProperties properties;
    SqlApprovalService approvals;
    McpCallLogService logs;
    DataSourceRegistry registry;
    McpToolSpecFactory factory;
    ExecutorService executor;
    final AtomicInteger executed = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        properties = new McpActivityProperties();
        properties.setDirectory(temp.toString());
        properties.setConfirmationTimeoutSeconds(2);
        approvals = new SqlApprovalService(properties, mapper);
        logs = new McpCallLogService(properties, mapper);
        registry = mock(DataSourceRegistry.class);
        when(registry.getDefaultName()).thenReturn("test-db");
        when(registry.getAdapter("test-db")).thenReturn(mock(DatabaseAdapter.class));
        factory = new McpToolSpecFactory(mapper, new McpToolProperties(), logs, approvals, registry, new SqlSecurityValidator());
        executor = Executors.newFixedThreadPool(3);
    }

    @AfterEach void cleanup() { approvals.close(); executor.shutdownNow(); }

    private McpSchema.CallToolResult call(String tool) {
        var spec = factory.create(tool, "test", "test", McpToolSpecFactory.inputSchema(Map.of()),
                McpToolSpecFactory.objectData(Map.of()), tool.equals("db_query"), args -> {
                    executed.incrementAndGet();
                    assertThat(args).containsEntry("datasource", "test-db");
                    return ToolResponse.ok(Map.of("rows", List.of("中文响应")), List.of(), Map.of());
                });
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation("测试客户端", "1.2"));
        String sql = switch(tool) {
            case "db_insert" -> "INSERT INTO t(id) VALUES (1)";
            case "db_delete" -> "DELETE FROM t WHERE id=1";
            case "db_execute_sql" -> "CREATE TABLE t(id INT)";
            case "db_query" -> "SELECT * FROM t";
            default -> "UPDATE t SET name='中文' WHERE id=1";
        };
        return spec.callHandler().apply(exchange, new McpSchema.CallToolRequest(tool, Map.of("sql", sql)));
    }

    private SqlApprovalService.Request waiting() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (approvals.pending().isEmpty() && System.nanoTime() < end) Thread.sleep(5);
        assertThat(approvals.pending()).hasSize(1);
        return approvals.pending().get(0);
    }

    @Test void allWriteToolsWaitForOneShotApprovalAndLogRealResponse() throws Exception {
        for (String tool : List.of("db_insert", "db_update", "db_delete", "db_execute_sql")) {
            int before = executed.get();
            Future<McpSchema.CallToolResult> future = executor.submit(() -> call(tool));
            var request = waiting();
            assertThat(request.client()).isEqualTo("测试客户端");
            assertThat(request.datasource()).isEqualTo("test-db");
            assertThat(executed.get()).isEqualTo(before);
            assertThat(approvals.decide(request.id(), true)).isTrue();
            assertThat(approvals.decide(request.id(), true)).isFalse();
            assertThat(future.get(2, TimeUnit.SECONDS).isError()).isFalse();
            assertThat(executed.get()).isEqualTo(before + 1);
        }
        var details = entries(true);
        assertThat(details).hasSize(4);
        assertThat(details.get(0)).containsEntry("tool", "db_execute_sql").containsEntry("clientVersion", "1.2");
        assertThat(mapper.writeValueAsString(details.get(0).get("response"))).contains("中文响应");
        assertThat(entries(false).get(0)).doesNotContainKeys("request", "response");
        var reloaded = new McpCallLogService(properties, mapper);
        assertThat(reloaded.list(LocalDate.now(), 0, 20, true).toString()).contains("中文响应");
    }

    @Test void rejectionAndTimeoutNeverExecute() throws Exception {
        var future = executor.submit(() -> call("db_update"));
        var request = waiting();
        approvals.decide(request.id(), false);
        assertThat(future.get().toString()).contains("SQL_REJECTED");
        assertThat(call("db_delete").toString()).contains("APPROVAL_TIMEOUT");
        assertThat(executed.get()).isZero();
        assertThat(approvals.pending()).isEmpty();
        assertThat(approvals.decide(request.id(), true)).isFalse();
        assertThat(entries(true)).allSatisfy(e -> assertThat(e.get("status")).isEqualTo("error"));
    }

    @Test void disabledModeIsPersistedAndDoesNotReleaseAlreadyPendingSql() throws Exception {
        var future = executor.submit(() -> call("db_update"));
        var request = waiting();
        approvals.setEnabled(false);
        assertThat(new SqlApprovalService(properties, mapper).isEnabled()).isFalse();
        assertThat(future.isDone()).isFalse();
        assertThat(call("db_delete").isError()).isFalse();
        approvals.decide(request.id(), false);
        future.get();
        assertThat(executed.get()).isEqualTo(1);
    }

    @Test void readsBypassApprovalAndReadonlyWritesAreRejectedBeforePrompt() {
        assertThat(call("db_query").isError()).isFalse();
        when(registry.isReadonly("test-db")).thenReturn(true);
        assertThat(call("db_update").toString()).contains("READ_ONLY");
        assertThat(executed.get()).isEqualTo(1);
        assertThat(approvals.pending()).isEmpty();
    }

    @Test void queueLimitAndInterruptionNeverAuthorizeWrites() throws Exception {
        properties.setMaxPending(1);
        var future = executor.submit(() -> call("db_update"));
        waiting();
        assertThat(call("db_delete").toString()).contains("APPROVAL_QUEUE_FULL");
        future.cancel(true);
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isZero();
        assertThat(approvals.pending()).isEmpty();
        assertThat(entries(true).toString()).contains("APPROVAL_CANCELLED");
    }

    @Test void concurrentRequestsAreIndependent() throws Exception {
        var first = executor.submit(() -> call("db_update"));
        var a = waiting();
        var second = executor.submit(() -> call("db_delete"));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (approvals.pending().size() < 2 && System.nanoTime() < end) Thread.sleep(5);
        assertThat(approvals.pending()).hasSize(2);
        var b = approvals.pending().stream().filter(r -> !r.id().equals(a.id())).findFirst().orElseThrow();
        approvals.decide(a.id(), true);
        approvals.decide(b.id(), false);
        assertThat(first.get().isError()).isFalse();
        assertThat(second.get().isError()).isTrue();
        assertThat(executed.get()).isEqualTo(1);
    }

    @Test void logPaginationHandlesUnicodeLargeBlocksCorruptTailAndRetention() throws Exception {
        Path calls = temp.resolve("calls");
        Path old = calls.resolve(LocalDate.now().minusDays(40) + ".jsonl");
        Files.writeString(old, "{}\n");
        for (int i = 0; i < 5; i++) logs.append(new LinkedHashMap<>(Map.of(
                "id", i, "request", "中文".repeat(5000), "response", Map.of("value", "结果"))));
        Files.writeString(calls.resolve(LocalDate.now() + ".jsonl"), "{broken", StandardOpenOption.APPEND);
        var first = logs.list(LocalDate.now(), 0, 2, true);
        assertThat(first.get("hasMore")).isEqualTo(true);
        assertThat(((List<Map<String,Object>>)first.get("items")).get(0).get("id")).isEqualTo(4);
        var last = logs.list(LocalDate.now(), 2, 2, true);
        assertThat(last.get("hasMore")).isEqualTo(false);
        assertThat(((List<?>)last.get("items"))).hasSize(1);
        assertThat(Files.exists(old)).isFalse();
        logs.append(new LinkedHashMap<>(Map.of("id", 5, "request", "恢复后的请求")));
        assertThat(entries(true).get(0)).containsEntry("id", 5).containsEntry("request", "恢复后的请求");
        assertThatThrownBy(() -> logs.list(LocalDate.now(), -1, 20, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void loggingFailureDoesNotReportAnExecutedWriteAsFailed() throws Exception {
        approvals.setEnabled(false);
        Files.delete(temp.resolve("calls"));
        Files.writeString(temp.resolve("calls"), "not a directory");
        assertThat(call("db_update").isError()).isFalse();
        assertThat(executed.get()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked") private List<Map<String,Object>> entries(boolean detail) throws Exception {
        return (List<Map<String,Object>>) logs.list(LocalDate.now(), 0, 20, detail).get("items");
    }
}
