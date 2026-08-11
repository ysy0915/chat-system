package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * <h2>图执行流式事件 DTO</h2>
 *
 * <p>chat-llm 图引擎 SSE 事件的结构化描述，供 chat-core 解析。</p>
 *
 * <p>事件类型：</p>
 * <ul>
 *   <li>{@link #TYPE_NODE_START} — 节点开始 (nodeId)</li>
 *   <li>{@link #TYPE_BRANCH_START} — 分支开始 (nodeId+branchId)</li>
 *   <li>{@link #TYPE_DELTA} — 流式 token (nodeId+branchId+data)</li>
 *   <li>{@link #TYPE_BRANCH_END} — 分支结束 (nodeId+branchId+data=完整输出)</li>
 *   <li>{@link #TYPE_NODE_END} — 节点结束 (nodeId)</li>
 *   <li>{@link #TYPE_DONE} — 图执行完成 (data=true/false)</li>
 * </ul>
 */
@Schema(description = "图执行流式事件")
public class GraphStreamEventDto {

    public static final String TYPE_NODE_START   = "node_start";
    public static final String TYPE_BRANCH_START = "branch_start";
    public static final String TYPE_DELTA        = "delta";
    public static final String TYPE_BRANCH_END   = "branch_end";
    public static final String TYPE_NODE_END     = "node_end";
    public static final String TYPE_DONE         = "done";

    @Schema(description = "事件类型")
    private String type;

    @Schema(description = "节点 ID")
    private String nodeId;

    @Schema(description = "分支 ID（分支事件时非空）")
    private String branchId;

    @Schema(description = "事件负载（delta token / 完整输出 / true|false）")
    private String data;

    public GraphStreamEventDto() {}

    public GraphStreamEventDto(String type, String nodeId, String branchId, String data) {
        this.type = type;
        this.nodeId = nodeId;
        this.branchId = branchId;
        this.data = data;
    }

    public boolean is(String t) { return t.equals(type); }

    // getters / setters

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
}
