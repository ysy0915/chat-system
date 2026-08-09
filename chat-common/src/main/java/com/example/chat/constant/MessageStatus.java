package com.example.chat.constant;

/**
 * 消息状态常量（统一替代散布在各模块中的字符串魔法值 "done"/"error" 等）
 */
public final class MessageStatus {

    public static final String DONE = "done";
    public static final String ERROR = "error";
    public static final String RUNNING = "running";
    public static final String PENDING = "pending";
    public static final String STOPPED = "stopped";
    public static final String QUEUED = "queued";

    private MessageStatus() {
    }
}
