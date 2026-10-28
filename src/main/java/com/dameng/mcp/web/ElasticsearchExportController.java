package com.dameng.mcp.web;

import com.dameng.mcp.service.ElasticsearchExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams completed Elasticsearch export files to authenticated HTTP clients.
 */
@Slf4j
@Profile("http")
@RestController
@RequestMapping("/api/es-exports")
public class ElasticsearchExportController {

    private final ElasticsearchExportService exportService;

    public ElasticsearchExportController(ElasticsearchExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/{exportId}/files/{fileName:.+}")
    public ResponseEntity<?> download(@PathVariable String exportId, @PathVariable String fileName) {
        try {
            Path file = exportService.resolveDownload(exportId, fileName);
            MediaType contentType = fileName.endsWith(".json")
                    ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("application/x-ndjson");
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(fileName, StandardCharsets.UTF_8).build();
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .contentLength(Files.size(file))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(new InputStreamResource(Files.newInputStream(file)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            log.error("读取 Elasticsearch 导出文件失败: exportId={}, file={}", exportId, fileName, e);
            return ResponseEntity.internalServerError().body(ApiResponse.fail("读取导出文件失败"));
        }
    }
}
