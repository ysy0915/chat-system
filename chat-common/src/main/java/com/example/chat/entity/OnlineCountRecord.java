package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "在线人数记录")
public class OnlineCountRecord {
    @Schema(description = "记录ID")
    public Long id;
    @Schema(description = "页面标识")
    public String page;
    @Schema(description = "在线人数")
    public int count;
    @Schema(description = "记录时间")
    public LocalDateTime recordedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }
}
