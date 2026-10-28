package com.dameng.mcp.service;

import com.dameng.mcp.adapter.DataSourceRegistry;
import com.dameng.mcp.adapter.elasticsearch.ElasticsearchRestClient;
import com.dameng.mcp.config.ElasticsearchExportProperties;
import com.dameng.mcp.model.mcp.ToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Streams large Elasticsearch result sets to server-side NDJSON files.
 */
@Slf4j
@Service
public class ElasticsearchExportService {

    private static final Pattern INDEX_PATTERN = Pattern.compile("[A-Za-z0-9_.*-]+");
    private static final Set<String> FORBIDDEN_DSL_KEYS = Set.of(
            "from", "size", "sort", "search_after", "pit", "scroll",
            "aggs", "aggregations", "suggest", "collapse", "rescore", "terminate_after");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final DataSourceRegistry registry;
    private final ElasticsearchExportProperties properties;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ExportTask> tasks = new ConcurrentHashMap<>();
    private final Path exportRoot;

    public ElasticsearchExportService(DataSourceRegistry registry,
                                      ElasticsearchExportProperties properties,
                                      ObjectMapper mapper) {
        this.registry = registry;
        this.properties = properties;
        this.mapper = mapper;
        this.exportRoot = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "es-export-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(properties.getMaxConcurrentTasks(), factory);
    }

    public synchronized Map<String, Object> start(String datasource, String index, String query,
                                                   Integer requestedPageSize, Long requestedMaxRows) {
        ensureEnabled();
        validateIndex(index);
        long active = tasks.values().stream().filter(ExportTask::isActive).count();
        if (active >= properties.getMaxConcurrentTasks()) {
            throw new ToolException("EXPORT_CAPACITY_REACHED",
                    "已有 " + active + " 个导出任务正在运行，请等待任务完成后重试", true, null);
        }

        ElasticsearchRestClient client = registry.getElasticsearch(datasource);
        ObjectNode queryBody = parseAndValidateQuery(query);
        int pageSize = properties.normalizePageSize(requestedPageSize);
        long maxRows = properties.normalizeMaxRows(requestedMaxRows);
        String exportId = ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path taskDirectory = exportRoot.resolve(exportId).normalize();
        if (!taskDirectory.startsWith(exportRoot)) {
            throw new SecurityException("非法导出目录");
        }
        try {
            Files.createDirectories(taskDirectory);
        } catch (IOException e) {
            throw new ToolException("EXPORT_STORAGE_FAILED", "无法创建导出目录: " + e.getMessage(), false, e);
        }

        ExportTask task = new ExportTask(exportId, displayDatasource(datasource), index,
                queryBody, pageSize, maxRows, taskDirectory);
        tasks.put(exportId, task);
        writeManifestQuietly(task);
        Future<?> future = executor.submit(() -> runExport(task, client));
        task.future = future;
        return task.snapshot();
    }

    public Map<String, Object> status(String exportId) {
        return requireTask(exportId).snapshot();
    }

    public List<Map<String, Object>> list(int requestedLimit) {
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 100);
        return tasks.values().stream()
                .sorted(Comparator.comparing((ExportTask task) -> task.createdAt).reversed())
                .limit(limit)
                .map(ExportTask::snapshot)
                .toList();
    }

    public Map<String, Object> cancel(String exportId) {
        ExportTask task = requireTask(exportId);
        synchronized (task) {
            if (!task.isActive()) {
                return task.snapshot();
            }
            task.cancelRequested = true;
            boolean queued = task.status == ExportStatus.QUEUED;
            if (task.future != null) {
                task.future.cancel(true);
            }
            if (queued) {
                task.status = ExportStatus.CANCELLED;
                task.completedAt = Instant.now();
            }
        }
        writeManifestQuietly(task);
        return task.snapshot();
    }

    public Path resolveDownload(String exportId, String fileName) {
        ExportTask task = requireTask(exportId);
        if (fileName == null || fileName.isBlank() || !fileName.equals(Path.of(fileName).getFileName().toString())) {
            throw new IllegalArgumentException("非法文件名");
        }
        boolean allowed = "manifest.json".equals(fileName)
                || task.files.stream().anyMatch(file -> file.name().equals(fileName));
        if (!allowed) {
            throw new IllegalArgumentException("导出任务中不存在文件: " + fileName);
        }
        Path path = task.directory.resolve(fileName).normalize();
        if (!path.startsWith(task.directory) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("导出文件不存在: " + fileName);
        }
        return path;
    }

    private void runExport(ExportTask task, ElasticsearchRestClient client) {
        String pitId = null;
        PartWriter writer = null;
        try {
            synchronized (task) {
                if (task.cancelRequested) {
                    task.status = ExportStatus.CANCELLED;
                    task.completedAt = Instant.now();
                    return;
                }
                task.status = ExportStatus.RUNNING;
                task.startedAt = Instant.now();
            }
            writeManifest(task);

            pitId = client.openPointInTime(task.index, properties.getPitKeepAlive());
            ArrayNode searchAfter = null;
            int partNumber = 0;
            Instant deadline = task.startedAt.plus(Duration.ofMinutes(properties.getMaxRuntimeMinutes()));

            while (task.rowsWritten < task.maxRows) {
                checkContinue(task, deadline);
                ObjectNode search = buildSearchBody(task, pitId, searchAfter);
                JsonNode result = client.searchPointInTime(search.toString());
                if (result.hasNonNull("pit_id")) {
                    pitId = result.path("pit_id").asText();
                }
                if (task.totalHits == null) {
                    task.totalHits = readTotalHits(result);
                }
                JsonNode hits = result.path("hits").path("hits");
                if (!hits.isArray() || hits.isEmpty()) {
                    break;
                }

                for (JsonNode hit : hits) {
                    checkContinue(task, deadline);
                    if (task.rowsWritten >= task.maxRows) {
                        break;
                    }
                    if (writer == null) {
                        writer = new PartWriter(task.directory, ++partNumber, mapper);
                    }
                    writer.write(hit);
                    task.rowsWritten++;
                    if (writer.rows() >= properties.getRowsPerFile()) {
                        ExportFile file = writer.finish();
                        task.files.add(file);
                        task.bytesWritten += file.bytes();
                        writer = null;
                        writeManifest(task);
                    }
                }

                JsonNode last = hits.get(hits.size() - 1);
                if (!last.path("sort").isArray()) {
                    throw new IllegalStateException("Elasticsearch PIT 响应缺少 sort，无法继续 search_after 分页");
                }
                searchAfter = (ArrayNode) last.path("sort").deepCopy();
            }

            if (writer != null) {
                ExportFile file = writer.finish();
                task.files.add(file);
                task.bytesWritten += file.bytes();
                writer = null;
            }
            task.truncated = task.totalHits != null && task.rowsWritten < task.totalHits
                    && task.rowsWritten >= task.maxRows;
            task.status = ExportStatus.COMPLETED;
        } catch (ExportCancelledException e) {
            closePartQuietly(writer);
            writer = null;
            task.status = ExportStatus.CANCELLED;
        } catch (Exception e) {
            closePartQuietly(writer);
            writer = null;
            task.status = task.cancelRequested ? ExportStatus.CANCELLED : ExportStatus.FAILED;
            if (!task.cancelRequested) {
                task.error = safeMessage(e);
                log.error("Elasticsearch export failed: exportId={}, datasource={}, index={}",
                        task.exportId, task.datasource, task.index, e);
            }
        } finally {
            if (pitId != null && !pitId.isBlank()) {
                try {
                    client.closePointInTime(pitId);
                } catch (Exception e) {
                    log.warn("Failed to close Elasticsearch PIT for export {}: {}", task.exportId, safeMessage(e));
                }
            }
            task.completedAt = Instant.now();
            writeManifestQuietly(task);
        }
    }

    private ObjectNode buildSearchBody(ExportTask task, String pitId, ArrayNode searchAfter) {
        ObjectNode body = task.query.deepCopy();
        body.put("size", task.pageSize);
        body.put("track_total_hits", true);
        body.put("_source", true);
        ArrayNode sort = body.putArray("sort");
        sort.addObject().put("_shard_doc", "asc");
        ObjectNode pit = body.putObject("pit");
        pit.put("id", pitId);
        pit.put("keep_alive", properties.getPitKeepAlive());
        if (searchAfter != null) {
            body.set("search_after", searchAfter);
        }
        return body;
    }

    private ObjectNode parseAndValidateQuery(String query) {
        if (query == null || query.isBlank()) {
            ObjectNode root = mapper.createObjectNode();
            root.set("query", mapper.createObjectNode().set("match_all", mapper.createObjectNode()));
            return root;
        }
        if (query.length() > properties.getMaxQueryChars()) {
            throw new IllegalArgumentException("query 超过服务端限制 " + properties.getMaxQueryChars() + " 字符");
        }
        try {
            JsonNode parsed = mapper.readTree(query);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException("query 必须是 JSON 对象");
            }
            ObjectNode body = (ObjectNode) parsed.deepCopy();
            for (String key : FORBIDDEN_DSL_KEYS) {
                if (body.has(key)) {
                    throw new IllegalArgumentException("query 不允许包含由导出器控制的字段: " + key);
                }
            }
            return body;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("query 必须是合法的 Elasticsearch Query DSL JSON", e);
        }
    }

    private long readTotalHits(JsonNode result) {
        JsonNode total = result.path("hits").path("total");
        return total.isObject() ? total.path("value").asLong() : total.asLong();
    }

    private void checkContinue(ExportTask task, Instant deadline) {
        if (task.cancelRequested || Thread.currentThread().isInterrupted()) {
            throw new ExportCancelledException();
        }
        if (Instant.now().isAfter(deadline)) {
            throw new ToolException("EXPORT_TIMEOUT",
                    "导出超过最大运行时间 " + properties.getMaxRuntimeMinutes() + " 分钟");
        }
    }

    private ExportTask requireTask(String exportId) {
        if (exportId == null || exportId.isBlank()) {
            throw new IllegalArgumentException("export_id 不能为空");
        }
        ExportTask task = tasks.get(exportId);
        if (task == null) {
            throw new ToolException("EXPORT_NOT_FOUND", "未找到导出任务: " + exportId);
        }
        return task;
    }

    private void validateIndex(String index) {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("index 不能为空");
        }
        if (index.length() > 255 || !INDEX_PATTERN.matcher(index).matches()
                || index.contains("..") || index.startsWith("-") || index.startsWith("_")) {
            throw new IllegalArgumentException("index 包含非法字符或不受支持的索引表达式");
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ToolException("EXPORT_DISABLED", "Elasticsearch 导出功能已被服务端禁用");
        }
    }

    private String displayDatasource(String datasource) {
        return datasource == null || datasource.isBlank() ? "default" : datasource;
    }

    private void writeManifestQuietly(ExportTask task) {
        try {
            writeManifest(task);
        } catch (Exception e) {
            log.warn("Failed to write export manifest {}: {}", task.exportId, safeMessage(e));
        }
    }

    private void writeManifest(ExportTask task) throws IOException {
        synchronized (task) {
            ObjectNode root = mapper.valueToTree(task.snapshot());
            root.put("format", "ndjson");
            root.put("pit_keep_alive", properties.getPitKeepAlive());
            root.put("rows_per_file", properties.getRowsPerFile());
            root.set("query", task.query);
            root.set("files", mapper.valueToTree(task.files));
            Path temporary = task.directory.resolve("manifest.json.tmp");
            Path manifest = task.directory.resolve("manifest.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            moveAtomically(temporary, manifest);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closePartQuietly(PartWriter writer) {
        if (writer != null) {
            try {
                writer.closeIncomplete();
            } catch (IOException ignored) {
                // Preserve the .part file for diagnostics.
            }
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "Unknown error" : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }

    @PreDestroy
    public void close() {
        tasks.values().stream().filter(ExportTask::isActive).forEach(task -> task.cancelRequested = true);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum ExportStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private static final class ExportCancelledException extends RuntimeException {
    }

    private static final class ExportTask {
        private final String exportId;
        private final String datasource;
        private final String index;
        private final ObjectNode query;
        private final int pageSize;
        private final long maxRows;
        private final Path directory;
        private final Instant createdAt = Instant.now();
        private final List<ExportFile> files = new CopyOnWriteArrayList<>();

        private volatile ExportStatus status = ExportStatus.QUEUED;
        private volatile Instant startedAt;
        private volatile Instant completedAt;
        private volatile long rowsWritten;
        private volatile long bytesWritten;
        private volatile Long totalHits;
        private volatile boolean truncated;
        private volatile boolean cancelRequested;
        private volatile String error;
        private volatile Future<?> future;

        private ExportTask(String exportId, String datasource, String index, ObjectNode query,
                           int pageSize, long maxRows, Path directory) {
            this.exportId = exportId;
            this.datasource = datasource;
            this.index = index;
            this.query = query;
            this.pageSize = pageSize;
            this.maxRows = maxRows;
            this.directory = directory;
        }

        private boolean isActive() {
            return status == ExportStatus.QUEUED || status == ExportStatus.RUNNING;
        }

        private synchronized Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("export_id", exportId);
            result.put("status", status.name().toLowerCase());
            result.put("datasource", datasource);
            result.put("index", index);
            result.put("page_size", pageSize);
            result.put("max_rows", maxRows);
            result.put("total_hits", totalHits);
            result.put("rows_written", rowsWritten);
            result.put("bytes_written", bytesWritten);
            result.put("file_count", files.size());
            result.put("files", files.stream().map(ExportFile::name).toList());
            result.put("truncated", truncated);
            result.put("cancel_requested", cancelRequested);
            result.put("created_at", createdAt.toString());
            result.put("started_at", startedAt == null ? null : startedAt.toString());
            result.put("completed_at", completedAt == null ? null : completedAt.toString());
            result.put("output_directory", directory.toString());
            result.put("manifest_path", directory.resolve("manifest.json").toString());
            result.put("download_path", "/api/es-exports/" + exportId + "/files/{file_name}");
            result.put("error", error);
            return result;
        }
    }

    private record ExportFile(String name, long rows, long bytes, String sha256) {
    }

    private static final class PartWriter {
        private final ObjectMapper mapper;
        private final Path partial;
        private final Path completed;
        private final MessageDigest digest;
        private final BufferedWriter writer;
        private long rows;

        private PartWriter(Path directory, int partNumber, ObjectMapper mapper) throws IOException {
            this.mapper = mapper;
            String baseName = String.format("part-%05d.ndjson", partNumber);
            this.partial = directory.resolve(baseName + ".part");
            this.completed = directory.resolve(baseName);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
            DigestOutputStream digestStream = new DigestOutputStream(Files.newOutputStream(partial), digest);
            this.writer = new BufferedWriter(new OutputStreamWriter(digestStream, StandardCharsets.UTF_8), 1024 * 1024);
        }

        private void write(JsonNode hit) throws IOException {
            ObjectNode row = mapper.createObjectNode();
            row.put("_index", hit.path("_index").asText());
            row.put("_id", hit.path("_id").asText());
            JsonNode source = hit.get("_source");
            row.set("_source", source == null ? mapper.nullNode() : source);
            writer.write(mapper.writeValueAsString(row));
            writer.newLine();
            rows++;
        }

        private long rows() {
            return rows;
        }

        private ExportFile finish() throws IOException {
            writer.close();
            moveAtomically(partial, completed);
            return new ExportFile(completed.getFileName().toString(), rows, Files.size(completed),
                    HexFormat.of().formatHex(digest.digest()));
        }

        private void closeIncomplete() throws IOException {
            writer.close();
        }
    }
}
