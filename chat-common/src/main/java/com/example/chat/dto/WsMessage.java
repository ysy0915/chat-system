package com.example.chat.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 推送消息 — 替代散落的 Map.of("type", ..., ...)
 *
 * 统一所有前端推送的消息格式。
 * 支持链式添加额外字段，与原有 Map.of 的灵活度保持一致。
 *
 * <pre>{@code
 *   // 简单消息
 *   WsMessage.done().withReqId(reqId).with("answer", text).toMap()
 *
 *   // 复杂消息
 *   WsMessage.of("stream_start").withReqId(reqId)
 *       .with("model", modelName)
 *       .with("temperature", 0.7)
 *       .toMap()
 * }</pre>
 */
public class WsMessage {

    // ====== 消息类型常量 ======

    public static final String TYPE_DONE          = "done";
    public static final String TYPE_STREAM_START  = "stream_start";
    public static final String TYPE_STREAM_TOKEN  = "stream_token";
    public static final String TYPE_STREAM_END    = "stream_end";
    public static final String TYPE_ERROR         = "error";
    public static final String TYPE_STOPPED       = "stopped";
    public static final String TYPE_TYPING        = "typing";
    public static final String TYPE_MESSAGE       = "message";
    public static final String TYPE_ANSWER        = "answer";
    public static final String TYPE_MODEL_CHANGE  = "model_change";
    public static final String TYPE_ROUND_START   = "round_start";
    public static final String TYPE_ROUND_END     = "round_end";
    public static final String TYPE_DEBATE_START  = "debate_start";
    public static final String TYPE_MODEL_START   = "model_start";
    public static final String TYPE_MODEL_END     = "model_end";

    // ====== 字段 ======

    private String type;
    private String reqId;
    private Long time;
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public WsMessage() {
        this.time = System.currentTimeMillis();
    }

    public WsMessage(String type) {
        this.type = type;
        this.time = System.currentTimeMillis();
    }

    // ====== 静态工厂 ======

    /** 创建带 type 的基础消息（后续链式添加字段） */
    public static WsMessage of(String type) {
        return new WsMessage(type);
    }

    public static WsMessage done() {
        return new WsMessage(TYPE_DONE);
    }

    public static WsMessage streamStart(String model) {
        WsMessage msg = new WsMessage(TYPE_STREAM_START);
        msg.extra.put("model", model);
        return msg;
    }

    public static WsMessage streamToken(String token) {
        WsMessage msg = new WsMessage(TYPE_STREAM_TOKEN);
        msg.extra.put("token", token);
        return msg;
    }

    public static WsMessage streamEnd() {
        return new WsMessage(TYPE_STREAM_END);
    }

    public static WsMessage error(String message) {
        WsMessage msg = new WsMessage(TYPE_ERROR);
        msg.extra.put("message", message);
        return msg;
    }

    public static WsMessage stopped(String answer) {
        WsMessage msg = new WsMessage(TYPE_STOPPED);
        msg.extra.put("answer", answer);
        return msg;
    }

    public static WsMessage typing(boolean typing) {
        WsMessage msg = new WsMessage(TYPE_TYPING);
        msg.extra.put("typing", typing);
        return msg;
    }

    // ====== 链式追加字段 ======

    public WsMessage withReqId(String reqId) {
        this.reqId = reqId;
        return this;
    }

    public WsMessage with(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }

    // ====== 转换 ======

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(extra);
        map.put("type", type);
        if (reqId != null) map.put("req_id", reqId);
        if (time != null) map.put("time", time);
        return map;
    }

    // ====== getters / setters ======

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getReqId() { return reqId; }
    public void setReqId(String reqId) { this.reqId = reqId; }

    public Long getTime() { return time; }
    public void setTime(Long time) { this.time = time; }

    @Override
    public String toString() {
        return "WsMessage{type='" + type + "', reqId='" + reqId + "'}";
    }
}
