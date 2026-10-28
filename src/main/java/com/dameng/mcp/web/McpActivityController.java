package com.dameng.mcp.web;

import com.dameng.mcp.service.McpCallLogService;
import com.dameng.mcp.service.SqlApprovalService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

@RestController
@Profile("http")
@RequestMapping("/api/activity")
public class McpActivityController {
    private final McpCallLogService logs;
    private final SqlApprovalService approvals;

    public McpActivityController(McpCallLogService logs, SqlApprovalService approvals) {
        this.logs = logs;
        this.approvals = approvals;
    }

    @GetMapping("/logs")
    public ApiResponse<?> logs(@RequestParam(required = false) String date,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestParam(defaultValue = "false") boolean detail) throws IOException {
        try {
            return ApiResponse.ok("ok", logs.list(date == null ? LocalDate.now() : LocalDate.parse(date), page, size, detail));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的日期或分页参数", e);
        }
    }

    @GetMapping("/confirmation")
    public ApiResponse<?> settings() {
        return ApiResponse.ok("ok", Map.of("enabled", approvals.isEnabled(), "pending", approvals.pending()));
    }

    public record Mode(Boolean enabled) {}
    @PutMapping("/confirmation")
    public ApiResponse<?> mode(@RequestBody Mode mode) throws IOException {
        if (mode.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 必填");
        approvals.setEnabled(mode.enabled());
        return settings();
    }

    public record Decision(Boolean approved) {}
    @PostMapping("/confirmation/{id}")
    public ApiResponse<?> decide(@PathVariable String id, @RequestBody Decision decision) {
        if (decision.approved() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "approved 必填");
        if (!approvals.decide(id, decision.approved())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求已处理或已过期");
        }
        return ApiResponse.ok("已处理");
    }
}
