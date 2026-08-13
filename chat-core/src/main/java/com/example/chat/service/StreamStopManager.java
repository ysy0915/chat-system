package com.example.chat.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式生成停止管理器
 *
 * 用于 ChatProcessor、TreeHoleService 等流式调用场景，
 * 统一管理 reqId -> stop flag 的映射和查询
 */
@Component
public class StreamStopManager {

    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    /** 请求停止某个流式生成 */
    public void requestStop(String reqId) {
        stopFlags.put(reqId, new AtomicBoolean(true));
    }

    /** 判断某个 reqId 是否已请求停止 */
    public boolean isStopped(String reqId) {
        AtomicBoolean flag = stopFlags.get(reqId);
        return flag != null && flag.get();
    }

    /** 获取或创建默认停止标记（默认 false） */
    public AtomicBoolean getOrDefault(String reqId) {
        return stopFlags.getOrDefault(reqId, new AtomicBoolean(false));
    }

    /** 清理某个 reqId 的停止标记 */
    public void remove(String reqId) {
        stopFlags.remove(reqId);
    }

    /** 清理所有停止标记 */
    public void clear() {
        stopFlags.clear();
    }
}
