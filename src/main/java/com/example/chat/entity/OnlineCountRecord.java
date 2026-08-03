package com.example.chat.entity;

import java.time.LocalDateTime;

public class OnlineCountRecord {
    public Long id;
    public String page;
    public int count;
    public LocalDateTime recordedAt;

    public Long getId() { return id; }
    public String getPage() { return page; }
    public int getCount() { return count; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
}
