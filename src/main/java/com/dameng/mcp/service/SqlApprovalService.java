package com.dameng.mcp.service;

import com.dameng.mcp.config.McpActivityProperties;
import com.dameng.mcp.model.mcp.ToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SqlApprovalService {
    public record Request(String id, String client, String tool, String datasource, String sql,
                          String createdAt, String expiresAt) {}
    private record Pending(Request request, CompletableFuture<Boolean> decision) {}
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final McpActivityProperties properties;
    private final ObjectMapper mapper;
    private final Path settings;
    private volatile boolean enabled;

    public SqlApprovalService(McpActivityProperties properties, ObjectMapper mapper) throws IOException {
        this.properties = properties;
        this.mapper = mapper;
        this.settings = Path.of(properties.getDirectory()).resolve("settings.json");
        this.enabled = properties.isConfirmationEnabled();
        if (Files.exists(settings)) {
            var node = mapper.readTree(Files.readString(settings));
            if (!node.path("confirmationEnabled").isBoolean()) {
                throw new IOException("SQL 确认设置文件格式错误：" + settings);
            }
            this.enabled = node.path("confirmationEnabled").asBoolean();
        }
    }

    public boolean isEnabled() { return enabled; }

    public synchronized void setEnabled(boolean value) throws IOException {
        Files.createDirectories(settings.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(settings.toAbsolutePath().getParent(), "settings-", ".tmp");
        try {
            Files.writeString(temp, mapper.writeValueAsString(Map.of("confirmationEnabled", value)));
            try {
                Files.move(temp, settings, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, settings, StandardCopyOption.REPLACE_EXISTING);
            }
            enabled = value;
        } finally {
            Files.deleteIfExists(temp);
        }
        // A mode change never authorizes requests that were already waiting.
    }

    public synchronized List<Request> pending() {
        return pending.values().stream().filter(p -> !p.decision.isDone()
                && Instant.parse(p.request.expiresAt()).isAfter(Instant.now()))
                .map(Pending::request).toList();
    }

    public synchronized boolean decide(String id, boolean approved) {
        Pending item = pending.get(id);
        return item != null && Instant.parse(item.request.expiresAt()).isAfter(Instant.now())
                && item.decision.complete(approved);
    }

    public void await(String id, String client, String tool, String datasource, String sql) {
        Pending item;
        long timeout = Math.max(1, Math.min(600, properties.getConfirmationTimeoutSeconds()));
        synchronized (this) {
            if (!enabled) return;
            if (pending.size() >= Math.max(1, properties.getMaxPending())) {
                throw new ToolException("APPROVAL_QUEUE_FULL", "待确认 SQL 过多，请稍后重试");
            }
            Instant now = Instant.now();
            item = new Pending(new Request(id, client, tool, datasource, sql,
                    now.toString(), now.plusSeconds(timeout).toString()), new CompletableFuture<>());
            pending.put(id, item);
        }
        try {
            if (!item.decision.get(timeout, TimeUnit.SECONDS)) {
                throw new ToolException("SQL_REJECTED", "用户拒绝执行 SQL");
            }
            if (!Instant.parse(item.request.expiresAt()).isAfter(Instant.now())) {
                throw new ToolException("APPROVAL_TIMEOUT", "SQL 确认已过期，未执行");
            }
        } catch (TimeoutException e) {
            throw new ToolException("APPROVAL_TIMEOUT", "SQL 确认超时，未执行。请打开管理页面及时确认");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("APPROVAL_CANCELLED", "SQL 等待被取消，未执行");
        } catch (ExecutionException e) {
            throw new ToolException("APPROVAL_CANCELLED", "SQL 确认失败，未执行");
        } finally {
            synchronized (this) { pending.remove(id); }
        }
    }

    @PreDestroy
    public synchronized void close() {
        pending.values().forEach(p -> p.decision.complete(false));
    }
}
