package com.example.chat.llm.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图执行流式事件 — 带 nodeId/branchId 标识，供 core 侧识别是哪个分支的 token。
 *
 * <p>事件类型：</p>
 * <ul>
 *   <li>{@link #TYPE_NODE_START} — 节点开始 (data=nodeId)</li>
 *   <li>{@link #TYPE_BRANCH_START} — 分支开始 (nodeId+branchId)</li>
 *   <li>{@link #TYPE_DELTA} — 流式 token (nodeId+branchId+data)</li>
 *   <li>{@link #TYPE_BRANCH_END} — 分支结束 (nodeId+branchId+data=完整输出)</li>
 *   <li>{@link #TYPE_NODE_END} — 节点结束 (data=nodeId)</li>
 *   <li>{@link #TYPE_DONE} — 图执行完成 (data=true/false)</li>
 * </ul>
 */
public class GraphStreamEvent {

    public static final String TYPE_NODE_START   = "node_start";
    public static final String TYPE_BRANCH_START = "branch_start";
    public static final String TYPE_DELTA        = "delta";
    public static final String TYPE_BRANCH_END   = "branch_end";
    public static final String TYPE_NODE_END     = "node_end";
    public static final String TYPE_DONE         = "done";

    private String type;
    private String nodeId;
    private String branchId;
    private String data;

    public GraphStreamEvent() {}

    public GraphStreamEvent(String type, String nodeId, String branchId, String data) {
        this.type = type;
        this.nodeId = nodeId;
        this.branchId = branchId;
        this.data = data;
    }

    public static GraphStreamEvent nodeStart(String nodeId) {
        return new GraphStreamEvent(TYPE_NODE_START, nodeId, null, nodeId);
    }

    public static GraphStreamEvent branchStart(String nodeId, String branchId) {
        return new GraphStreamEvent(TYPE_BRANCH_START, nodeId, branchId, null);
    }

    public static GraphStreamEvent delta(String nodeId, String branchId, String token) {
        return new GraphStreamEvent(TYPE_DELTA, nodeId, branchId, token);
    }

    public static GraphStreamEvent branchEnd(String nodeId, String branchId, String fullOutput) {
        return new GraphStreamEvent(TYPE_BRANCH_END, nodeId, branchId, fullOutput);
    }

    public static GraphStreamEvent nodeEnd(String nodeId) {
        return new GraphStreamEvent(TYPE_NODE_END, nodeId, null, nodeId);
    }

    public static GraphStreamEvent done(boolean success) {
        return new GraphStreamEvent(TYPE_DONE, null, null, String.valueOf(success));
    }

    /** 转 JSON Map（供 SSE data 发送） */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        if (nodeId != null) map.put("nodeId", nodeId);
        if (branchId != null) map.put("branchId", branchId);
        if (data != null) map.put("data", data);
        return map;
    }

    // getters / setters

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    @Override
    public String toString() {
        return "GraphStreamEvent{type='" + type + "', nodeId='" + nodeId
                + "', branchId='" + branchId + "'}";
    }
}
