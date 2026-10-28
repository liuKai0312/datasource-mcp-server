package com.dameng.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side Elasticsearch export limits and storage settings.
 */
@ConfigurationProperties(prefix = "mcp.elasticsearch-export")
public class ElasticsearchExportProperties {

    private boolean enabled = true;
    private String directory = "./data/elasticsearch-exports";
    private int maxConcurrentTasks = 2;
    private int defaultPageSize = 1000;
    private int maxPageSize = 5000;
    private int rowsPerFile = 200000;
    private long maxRows = 10000000L;
    private int maxQueryChars = 65536;
    private int maxRuntimeMinutes = 360;
    private String pitKeepAlive = "5m";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("mcp.elasticsearch-export.directory must not be blank");
        }
        this.directory = directory;
    }

    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public void setMaxConcurrentTasks(int value) {
        this.maxConcurrentTasks = positive(value, "max-concurrent-tasks");
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int value) {
        this.defaultPageSize = positive(value, "default-page-size");
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int value) {
        this.maxPageSize = positive(value, "max-page-size");
    }

    public int getRowsPerFile() {
        return rowsPerFile;
    }

    public void setRowsPerFile(int value) {
        this.rowsPerFile = positive(value, "rows-per-file");
    }

    public long getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("mcp.elasticsearch-export.max-rows must be positive");
        }
        this.maxRows = value;
    }

    public int getMaxQueryChars() {
        return maxQueryChars;
    }

    public void setMaxQueryChars(int value) {
        this.maxQueryChars = positive(value, "max-query-chars");
    }

    public int getMaxRuntimeMinutes() {
        return maxRuntimeMinutes;
    }

    public void setMaxRuntimeMinutes(int value) {
        this.maxRuntimeMinutes = positive(value, "max-runtime-minutes");
    }

    public String getPitKeepAlive() {
        return pitKeepAlive;
    }

    public void setPitKeepAlive(String value) {
        if (value == null || !value.matches("[1-9][0-9]*(ms|s|m|h|d)")) {
            throw new IllegalArgumentException("mcp.elasticsearch-export.pit-keep-alive has an invalid duration");
        }
        this.pitKeepAlive = value;
    }

    public int normalizePageSize(Integer requested) {
        int value = requested == null ? defaultPageSize : requested;
        if (value <= 0) {
            throw new IllegalArgumentException("page_size must be positive");
        }
        return Math.min(value, maxPageSize);
    }

    public long normalizeMaxRows(Long requested) {
        long value = requested == null ? maxRows : requested;
        if (value <= 0) {
            throw new IllegalArgumentException("max_rows must be positive");
        }
        if (value > maxRows) {
            throw new IllegalArgumentException("max_rows exceeds server limit " + maxRows);
        }
        return value;
    }

    private int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("mcp.elasticsearch-export." + name + " must be positive");
        }
        return value;
    }
}
