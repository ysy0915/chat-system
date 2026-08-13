package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Locale;

/**
 * <h2>业务域类型</h2>
 *
 * <p>用于 API Gateway 路由分发 &amp; 流控/计费维度。</p>
 *
 * <pre>
 *   CHAT  — 对话补全 (默认)
 *   MEDIA — 多模态 (图片/视频/音频生成)
 *   GAMES — 游戏场景
 *   RAG   — 检索增强生成
 *   GRAPH — LangGraph 图编排
 * </pre>
 */
@Schema(description = "业务域类型")
public enum BizType {

    CHAT,

    MEDIA,

    GAMES,

    RAG,

    GRAPH;

    /**
     * 从字符串安全解析，未知/空/null 均返回 CHAT。
     */
    public static BizType from(String name) {
        if (name == null || name.isBlank()) {
            return CHAT;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CHAT;
        }
    }
}
