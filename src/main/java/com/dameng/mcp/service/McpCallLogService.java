package com.dameng.mcp.service;

import com.dameng.mcp.config.McpActivityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
public class McpCallLogService {
    private final Path directory;
    private final ObjectMapper mapper;
    private final McpActivityProperties properties;
    private LocalDate lastPruned;

    public McpCallLogService(McpActivityProperties properties, ObjectMapper mapper) throws IOException {
        this.directory = Path.of(properties.getDirectory()).resolve("calls");
        this.mapper = mapper;
        this.properties = properties;
        Files.createDirectories(directory);
    }

    public synchronized void append(Map<String, Object> entry) {
        try {
            LocalDate today = LocalDate.now();
            // Separate a partial last entry left by a terminated process from the next complete call.
            Path target = file(today);
            if (Files.exists(target) && Files.size(target) > 0) {
                try (RandomAccessFile input = new RandomAccessFile(target.toFile(), "r")) {
                    input.seek(input.length() - 1);
                    if (input.read() != '\n') Files.writeString(target, "\n", StandardOpenOption.APPEND);
                }
            }
            Files.writeString(target, mapper.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!today.equals(lastPruned)) {
                try (var paths = Files.list(directory)) {
                    for (Path path : paths.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl")).toList()) {
                        LocalDate date = LocalDate.parse(path.getFileName().toString().substring(0, 10));
                        if (date.isBefore(today.minusDays(Math.max(1, properties.getRetentionDays()) - 1L))) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
                lastPruned = today;
            }
        } catch (IOException e) {
            // Never turn an executed write into a retryable failure because its log could not be saved.
            log.error("MCP 调用日志写入失败：{}", directory, e);
        }
    }

    private Path file(LocalDate date) { return directory.resolve(date + ".jsonl"); }

    public synchronized Map<String, Object> list(LocalDate date, int page, int size, boolean detail) throws IOException {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page 应为 0–10000，size 应为 1–100");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        Path path = file(date);
        boolean more = false;
        if (Files.exists(path)) {
            // Read backwards so fetching the latest page does not scan a whole day's responses.
            try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
                long position = input.length() - 1;
                byte[] block = new byte[8192];
                long blockStart = -1;
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int seen = 0;
                while (position >= 0) {
                    long start = (position / block.length) * block.length;
                    if (start != blockStart) {
                        input.seek(start);
                        input.readFully(block, 0, (int) Math.min(block.length, input.length() - start));
                        blockStart = start;
                    }
                    int b = block[(int) (position-- - blockStart)] & 0xff;
                    if (b != '\n') line.write(b);
                    if ((b == '\n' || position < 0) && line.size() > 0) {
                        byte[] bytes = line.toByteArray();
                        line.reset();
                        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
                            byte temp = bytes[i]; bytes[i] = bytes[j]; bytes[j] = temp;
                        }
                        Map<String, Object> value;
                        try { value = mapper.readValue(bytes, Map.class); }
                        catch (IOException corruptLine) { continue; } // Tolerate a partial final line after a crash.
                        if (seen++ < page * size) continue;
                        if (items.size() == size) { more = true; break; }
                        if (!detail) { value.remove("request"); value.remove("response"); }
                        items.add(value);
                    }
                }
            }
        }
        return Map.of("items", items, "hasMore", more, "page", page, "date", date.toString());
    }
}
