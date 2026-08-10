package com.example.chat.entity;

import java.time.LocalDateTime;

public class OnlineCountRecord {
    public Long id;
    public String page;
    public int count;
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
