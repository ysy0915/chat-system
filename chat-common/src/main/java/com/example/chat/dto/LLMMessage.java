package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LLM 对话消息 — 替代散落的 Map.of("role", ..., "content", ...)
 *
 * 支持纯文本和多模态（图片）两种 content 格式。
 */
@Schema(description = "LLM 对话消息")
public class LLMMessage {

    @Schema(description = "角色: system / user / assistant")
    private String role;
    @Schema(description = "消息内容 (String 或 List<ContentPart>)")
    private Object content;
    @Schema(description = "函数调用名称 (可选)")
    private String name;

    public LLMMessage() {}

    public LLMMessage(String role, Object content) {
        this.role = role;
        this.content = content;
    }

    // ========= 静态工厂 =========

    public static LLMMessage system(String content) {
        return new LLMMessage("system", content);
    }

    public static LLMMessage user(String content) {
        return new LLMMessage("user", content);
    }

    public static LLMMessage assistant(String content) {
        return new LLMMessage("assistant", content);
    }

    /** 多模态：文字 + 图片 */
    public static LLMMessage userWithImage(String text, String base64Url, String mimeType) {
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.text(text));
        parts.add(ContentPart.imageUrl("data:" + mimeType + ";base64," + base64Url));
        return new LLMMessage("user", parts);
    }

    // ========= 转换方法 =========

    /** 转为 OpenAI 兼容的 Map 格式（用于 JSON 序列化） */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        if (content instanceof List) {
            List<Map<String, Object>> contentList = new ArrayList<>();
            for (Object part : (List<?>) content) {
                if (part instanceof ContentPart cp) {
                    contentList.add(cp.toMap());
                } else if (part instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) part;
                    contentList.add(m);
                }
            }
            map.put("content", contentList);
        } else {
            map.put("content", content instanceof String ? content : Objects.toString(content, ""));
        }
        if (name != null) map.put("name", name);
        return map;
    }

    /** 批量转换消息列表 */
    public static List<Map<String, Object>> toMapList(List<LLMMessage> messages) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LLMMessage m : messages) {
            list.add(m.toMap());
        }
        return list;
    }

    /** 从 Map 构建 (向后兼容) */
    @SuppressWarnings("unchecked")
    public static LLMMessage fromMap(Map<String, Object> map) {
        String role = (String) map.get("role");
        Object content = map.get("content");
        LLMMessage msg = new LLMMessage(role, content);
        msg.setName((String) map.get("name"));
        return msg;
    }

    // ========= getters / setters =========

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Object getContent() { return content; }
    public void setContent(Object content) { this.content = content; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** 获取纯文本内容（如果是文本）；多模态返回 null */
    public String getTextContent() {
        return content instanceof String ? (String) content : null;
    }

    /** 是否是多模态消息 */
    public boolean isMultimodal() {
        return content instanceof List;
    }

    @Override
    public String toString() {
        return "LLMMessage{role='" + role + "', content=" + 
               (content instanceof String ? 
                   ("'" + ((String) content).substring(0, Math.min(50, ((String) content).length())) + "'") : 
                   "[multimodal]") + "}";
    }
}
