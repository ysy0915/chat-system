package com.example.chat.service;

import com.example.chat.dto.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 流式 token 合并广播器。
 *
 * <p>背景：流式回答每产生一个 token 就同步调用一次 {@link BroadcastService#broadcast}，
 * 每次 broadcast 都含本地消息推送 + RabbitMQ 跨节点 publish（网络往返），
 * 高 token 频率下会拖慢 LLM 流式读取线程，并在双 core 实例下放大 MQ 消息风暴。</p>
 *
 * <p>方案：把 token 缓冲到本地，按 30ms 窗口 或 256 字符阈值 合并后广播一次；
 * 合并窗口内产生的 token 延迟 ≤30ms，前端逐 token 追加渲染无感知，
 * 但 MQ 消息量可降低一个数量级。使用方务必在流式结束
 * （正常完成/停止/异常路径结束前）调用 {@link #close()} 冲刷剩余缓冲。</p>
 */
public final class StreamTokenBatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamTokenBatcher.class);

    /** 合并窗口：缓冲到该时间后强制冲刷一次（毫秒） */
    private static final long FLUSH_INTERVAL_MS = 30L;
    /** 缓冲达到该字符数后立即冲刷，避免单个窗口消息过大 */
    private static final int FLUSH_MAX_CHARS = 256;

    /** 类级共享定时器（单线程守护线程），所有 batcher 实例复用，避免每请求建线程池 */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-batcher-timer");
        t.setDaemon(true);
        return t;
    });

    private final String topic;
    private final String reqId;
    private final String messageType;
    private final BroadcastService broadcastService;

    private final StringBuilder buffer = new StringBuilder();
    private volatile ScheduledFuture<?> pendingFlush;

    /**
     * @param topic            推送目的地，如 /topic/user.123
     * @param reqId            请求ID（透传到消息）
     * @param messageType      WsMessage.TYPE_STREAM_TOKEN / TYPE_THINKING_TOKEN
     * @param broadcastService 广播服务
     */
    public StreamTokenBatcher(String topic, String reqId, String messageType, BroadcastService broadcastService) {
        this.topic = topic;
        this.reqId = reqId;
        this.messageType = messageType;
        this.broadcastService = broadcastService;
    }

    /**
     * 追加一个 token 片段，内部决定立即冲刷或缓冲等待合并。
     * 广播在锁外执行，不阻塞流式 token 处理。
     */
    public void append(String token) {
        String textToSend = null;
        synchronized (this) {
            buffer.append(token);
            if (buffer.length() >= FLUSH_MAX_CHARS) {
                cancelTimerLocked();
                textToSend = drainLocked();
            } else {
                scheduleFlushLocked();
            }
        }
        if (textToSend != null) {
            broadcast(textToSend);
        }
    }

    /** 冲刷当前缓冲（幂等，空缓冲时无操作） */
    public void flush() {
        String textToSend;
        synchronized (this) {
            cancelTimerLocked();
            textToSend = drainLocked();
        }
        if (textToSend != null) {
            broadcast(textToSend);
        }
    }

    @Override
    public void close() {
        flush();
    }

    private void scheduleFlushLocked() {
        if (pendingFlush != null) {
            return;
        }
        pendingFlush = TIMER.schedule(this::flush, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelTimerLocked() {
        ScheduledFuture<?> task = pendingFlush;
        if (task != null) {
            task.cancel(false);
            pendingFlush = null;
        }
    }

    /** 取走缓冲文本并清空（调用方需持锁） */
    private String drainLocked() {
        if (buffer.isEmpty()) {
            return null;
        }
        String text = buffer.toString();
        buffer.setLength(0);
        return text;
    }

    private void broadcast(String text) {
        broadcastService.broadcast(topic,
                WsMessage.of(messageType).withReqId(reqId).with("token", text).toMap());
    }
}
